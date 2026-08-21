/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.integration;

import com.communitylootshare.CommunityLootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.domain.CommunityLootshareState;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.party.CommunityLootshareDecisionMessage;
import com.communitylootshare.party.CommunityLootshareProposalMessage;
import com.communitylootshare.persistence.CommunityLootshareStorage;
import com.communitylootshare.sessions.CommunityLootshareEngine;
import com.communitylootshare.sessions.CommunityLootshareEngine.DecisionOutcome;
import com.communitylootshare.sessions.CommunityLootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.io.File;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;

@Singleton
@Slf4j
public class CommunityLootshareController
{
	private static final int MAX_DEFERRED_ACTIONS = 256;

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final ScheduledExecutorService executor;
	private final LootCaptureService captureService;
	private final CommunityLootshareEngine engine;
	private final LootshareCalculator calculator;
	private final CommunityLootshareStorage storage;
	private final Object lifecycleLock = new Object();
	private final Object saveLock = new Object();
	private final Deque<Runnable> deferredActions = new ArrayDeque<>();
	private final Map<File, SaveRequest> saveRequests = new LinkedHashMap<>();
	private final AtomicBoolean saveWorkerRunning = new AtomicBoolean();

	private boolean started;
	private boolean ready;
	private boolean persistenceWritable;
	private int loadGeneration;
	private File activeFile;

	@Inject
	public CommunityLootshareController(Client client, ClientThread clientThread, PartyService partyService,
	                                    ScheduledExecutorService executor, LootCaptureService captureService,
	                                    CommunityLootshareEngine engine, LootshareCalculator calculator,
	                                    CommunityLootshareStorage storage)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.executor = executor;
		this.captureService = captureService;
		this.engine = engine;
		this.calculator = calculator;
		this.storage = storage;
	}

	public void start()
	{
		synchronized (lifecycleLock)
		{
			if (started)
			{
				return;
			}
			started = true;
		}
		loadProfile(storage.resolveCurrentFile());
	}

	public void stop()
	{
		queueCurrentStateForSave();
		synchronized (lifecycleLock)
		{
			started = false;
			ready = false;
			loadGeneration++;
			deferredActions.clear();
		}
		captureService.resetDeduplication();
	}

	public void onProfileChanged()
	{
		File nextFile = storage.resolveCurrentFile();
		synchronized (lifecycleLock)
		{
			if (Objects.equals(activeFile, nextFile))
			{
				return;
			}
		}
		queueCurrentStateForSave();
		synchronized (lifecycleLock)
		{
			deferredActions.clear();
		}
		captureService.resetDeduplication();
		loadProfile(nextFile);
	}

	public void onLootReceived(LootReceived received)
	{
		if (!partyService.isInParty())
		{
			return;
		}
		PartyMember localMember = partyService.getLocalMember();
		Player localPlayer = client.getLocalPlayer();
		if (localMember == null || localPlayer == null || localPlayer.getName() == null)
		{
			return;
		}

		String recipient = Text.sanitize(localPlayer.getName()).trim();
		long partyId = partyService.getPartyId();
		Optional<com.communitylootshare.domain.SharedLootEvent> captured = captureService.capture(received, recipient,
			Instant.now(), client.getTickCount(), CommunityLootshareConfig.DEFAULT_MINIMUM_BUNDLE_VALUE);
		if (!captured.isPresent())
		{
			return;
		}
		LootProposal proposal = LootProposal.pending(partyId, localMember.getMemberId(), captured.get());
		executeWhenReady(() -> recordLocalProposal(proposal));
	}

	public void onProposalMessage(CommunityLootshareProposalMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		Optional<LootProposal> decoded = message.decode(partyService.getPartyId());
		decoded.ifPresent(proposal -> executeWhenReady(() -> {
			if (engine.addProposal(proposal) == MutationResult.APPLIED)
			{
				queueCurrentStateForSave();
			}
		}));
	}

	public void onDecisionMessage(CommunityLootshareDecisionMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		Optional<CommunityLootshareDecisionMessage.DecodedDecision> decoded = message.decode();
		decoded.ifPresent(decision -> executeWhenReady(() -> {
			DecisionOutcome outcome = engine.decide(decision.getProposalId(), message.getMemberId(),
				decision.getStatus(), decision.getDecidedAt(), decision.getParticipants());
			if (outcome.getResult() == MutationResult.APPLIED)
			{
				queueCurrentStateForSave();
			}
		}));
	}

	public void onPartyChanged(PartyChanged event)
	{
		captureService.resetDeduplication();
		Long partyId = event == null ? null : event.getPartyId();
		executeWhenReady(() -> {
			MutationResult result = partyId == null
				? engine.leaveParty(Instant.now())
				: engine.enterParty(partyId, UUID.randomUUID().toString(), Instant.now());
			if (result == MutationResult.APPLIED)
			{
				queueCurrentStateForSave();
			}
			if (partyId != null && (result == MutationResult.APPLIED || result == MutationResult.DUPLICATE))
			{
				requestPartySync();
			}
		});
	}

	public void onUserSync(UserSync sync)
	{
		if (sync == null || !isReady() || !partyService.isInParty())
		{
			return;
		}
		PartyMember requester = partyService.getMemberById(sync.getMemberId());
		PartyMember local = partyService.getLocalMember();
		if (requester == null || local == null || local.getMemberId() == requester.getMemberId())
		{
			return;
		}

		long responseLeader = Long.MAX_VALUE;
		for (PartyMember member : partyService.getMembers())
		{
			if (member.getMemberId() != requester.getMemberId())
			{
				responseLeader = Math.min(responseLeader, member.getMemberId());
			}
		}
		if (local.getMemberId() != responseLeader)
		{
			return;
		}

		for (LootProposal proposal : engine.getProposalsForActiveParty())
		{
			partyService.send(new CommunityLootshareProposalMessage(proposal));
			if (proposal.getStatus() != LootProposalStatus.PENDING)
			{
				partyService.send(new CommunityLootshareDecisionMessage(proposal));
			}
		}
	}

	/** Must be invoked on the RuneLite client thread by the future UI controller. */
	public MutationResult approveProposal(String proposalId)
	{
		return decideOwnedProposal(proposalId, LootProposalStatus.ACCEPTED);
	}

	/** Must be invoked on the RuneLite client thread by the future UI controller. */
	public MutationResult rejectProposal(String proposalId)
	{
		return decideOwnedProposal(proposalId, LootProposalStatus.REJECTED);
	}

	public List<LootProposal> getPendingOwnedProposals()
	{
		PartyMember local = partyService.getLocalMember();
		return !isReady() || local == null
			? Collections.emptyList()
			: engine.getPendingOwnedBy(local.getMemberId());
	}

	public LootshareCalculation getActiveCalculation()
	{
		if (!isReady())
		{
			return LootshareCalculation.empty();
		}
		return calculator.calculate(engine.getActiveSession().orElse(null));
	}

	public List<LootshareSession> getHistory()
	{
		return isReady() ? engine.getHistory() : Collections.emptyList();
	}

	public boolean isReady()
	{
		synchronized (lifecycleLock)
		{
			return started && ready;
		}
	}

	private MutationResult decideOwnedProposal(String proposalId, LootProposalStatus decision)
	{
		if (!isReady() || !partyService.isInParty())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		PartyMember local = partyService.getLocalMember();
		LootProposal proposal = engine.getProposal(proposalId).orElse(null);
		if (local == null || proposal == null)
		{
			return proposal == null ? MutationResult.NOT_FOUND : MutationResult.NOT_OWNER;
		}

		List<LootshareParticipant> roster = decision == LootProposalStatus.ACCEPTED
			? snapshotRoster(proposal, local)
			: Collections.emptyList();
		Instant decisionTime = Instant.ofEpochMilli(Instant.now().toEpochMilli());
		DecisionOutcome outcome = engine.decide(proposalId, local.getMemberId(), decision, decisionTime, roster);
		if (outcome.getResult() == MutationResult.APPLIED)
		{
			queueCurrentStateForSave();
			partyService.send(new CommunityLootshareDecisionMessage(outcome.getProposal()));
		}
		return outcome.getResult();
	}

	private List<LootshareParticipant> snapshotRoster(LootProposal proposal, PartyMember local)
	{
		Map<Long, LootshareParticipant> participants = new LinkedHashMap<>();
		participants.put(proposal.getOwnerMemberId(), new LootshareParticipant(proposal.getOwnerMemberId(),
			proposal.getEvent().getRecipient()));
		for (PartyMember member : partyService.getMembers())
		{
			if (participants.size() >= LootProposal.MAX_PARTICIPANTS)
			{
				break;
			}
			if (!member.isLoggedIn() && member.getMemberId() != local.getMemberId())
			{
				continue;
			}
			if (member.getMemberId() == proposal.getOwnerMemberId())
			{
				continue;
			}
			String displayName = displayName(member, proposal);
			participants.put(member.getMemberId(), new LootshareParticipant(member.getMemberId(), displayName));
		}
		List<LootshareParticipant> roster = new ArrayList<>(participants.values());
		roster.sort(Comparator.comparingLong(LootshareParticipant::getMemberId));
		return roster;
	}

	private String displayName(PartyMember member, LootProposal proposal)
	{
		if (member.getMemberId() == proposal.getOwnerMemberId())
		{
			return proposal.getEvent().getRecipient();
		}
		String name = member.getDisplayName();
		if (name == null || name.trim().isEmpty() || "<unknown>".equalsIgnoreCase(name.trim()))
		{
			return "Party member " + member.getMemberId();
		}
		String sanitized = Text.sanitize(name).trim();
		return sanitized.isEmpty() ? "Party member " + member.getMemberId() : sanitized;
	}

	private void recordLocalProposal(LootProposal proposal)
	{
		if (!partyService.isInParty() || partyService.getPartyId() != proposal.getPartyId())
		{
			return;
		}
		if (engine.addProposal(proposal) == MutationResult.APPLIED)
		{
			queueCurrentStateForSave();
			partyService.send(new CommunityLootshareProposalMessage(proposal));
		}
	}

	private void requestPartySync()
	{
		if (partyService.isInParty() && partyService.getLocalMember() != null)
		{
			partyService.send(new UserSync());
		}
	}

	private void loadProfile(File file)
	{
		final int generation;
		synchronized (lifecycleLock)
		{
			if (!started)
			{
				return;
			}
			ready = false;
			persistenceWritable = false;
			activeFile = file;
			generation = ++loadGeneration;
		}

		if (file == null)
		{
			completeLoad(generation, file, storage.load(null));
			return;
		}
		try
		{
			executor.execute(() -> {
				CommunityLootshareStorage.LoadResult result = storage.load(file);
				clientThread.invokeLater(() -> completeLoad(generation, file, result));
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Community Lootshare history load was rejected during shutdown", e);
		}
	}

	private void completeLoad(int generation, File file, CommunityLootshareStorage.LoadResult result)
	{
		List<Runnable> queued;
		synchronized (lifecycleLock)
		{
			if (!started || generation != loadGeneration || !Objects.equals(file, activeFile))
			{
				return;
			}
			engine.restore(result.getState());
			persistenceWritable = result.isWritable();
			ready = true;
			queued = new ArrayList<>(deferredActions);
			deferredActions.clear();
		}

		if (partyService.isInParty())
		{
			MutationResult resultCode = engine.enterParty(partyService.getPartyId(), UUID.randomUUID().toString(), Instant.now());
			if (resultCode == MutationResult.APPLIED)
			{
				queueCurrentStateForSave();
			}
		}
		for (Runnable action : queued)
		{
			executeWhenReady(action);
		}
		requestPartySync();
	}

	private boolean executeWhenReady(Runnable action)
	{
		synchronized (lifecycleLock)
		{
			if (!started)
			{
				return false;
			}
			if (!ready)
			{
				if (deferredActions.size() < MAX_DEFERRED_ACTIONS)
				{
					deferredActions.addLast(action);
				}
				else
				{
					log.debug("Dropping a deferred Community Lootshare action because the load queue is full");
				}
				return false;
			}
			action.run();
			return true;
		}
	}

	private void queueCurrentStateForSave()
	{
		File file;
		boolean writable;
		synchronized (lifecycleLock)
		{
			file = activeFile;
			writable = ready && persistenceWritable;
		}
		if (!writable || file == null)
		{
			return;
		}
		synchronized (saveLock)
		{
			// Keep only the newest full snapshot for each profile while disk I/O catches up.
			saveRequests.put(file, new SaveRequest(file, engine.snapshot()));
		}
		startSaveWorker();
	}

	private void startSaveWorker()
	{
		if (!saveWorkerRunning.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			executor.execute(this::drainSaveRequests);
		}
		catch (RejectedExecutionException e)
		{
			saveWorkerRunning.set(false);
			log.debug("Community Lootshare history save was rejected during shutdown", e);
		}
	}

	private void drainSaveRequests()
	{
		try
		{
			SaveRequest request;
			while ((request = pollSaveRequest()) != null)
			{
				storage.save(request.file, request.state);
			}
		}
		finally
		{
			saveWorkerRunning.set(false);
			if (hasSaveRequests())
			{
				startSaveWorker();
			}
		}
	}

	private SaveRequest pollSaveRequest()
	{
		synchronized (saveLock)
		{
			if (saveRequests.isEmpty())
			{
				return null;
			}
			File file = saveRequests.keySet().iterator().next();
			return saveRequests.remove(file);
		}
	}

	private boolean hasSaveRequests()
	{
		synchronized (saveLock)
		{
			return !saveRequests.isEmpty();
		}
	}

	private static final class SaveRequest
	{
		private final File file;
		private final CommunityLootshareState state;

		private SaveRequest(File file, CommunityLootshareState state)
		{
			this.file = file;
			this.state = state;
		}
	}
}
