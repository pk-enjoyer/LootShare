/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.integration;

import com.communitylootshare.LootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.party.DecisionMessage;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.party.MemberIdentityMessage;
import com.communitylootshare.party.ProposalMessage;
import com.communitylootshare.persistence.LootshareStorage;
import com.communitylootshare.sessions.LootshareEngine;
import com.communitylootshare.sessions.LootshareEngine.DecisionOutcome;
import com.communitylootshare.sessions.LootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.io.File;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@Singleton
@Slf4j
public class LootshareController
{
	private static final int MAX_DEFERRED_ACTIONS = 256;
	private static final Pattern PICKPOCKET_PATTERN = Pattern.compile("You pick (the )?.+'s? pocket.*");

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final LootshareConfig config;
	private final ScheduledExecutorService executor;
	private final LootCaptureService captureService;
	private final LootshareEngine engine;
	private final LootshareCalculator calculator;
	private final LootshareStorage storage;
	private final Object lifecycleLock = new Object();
	private final Object saveLock = new Object();
	private final Deque<Runnable> deferredActions = new ArrayDeque<>();
	private final Map<Long, String> memberNames = new HashMap<>();
	private final Map<File, SaveRequest> saveRequests = new LinkedHashMap<>();
	private final AtomicBoolean saveWorkerRunning = new AtomicBoolean();
	private volatile Runnable stateChangeListener = () -> {
	};

	private boolean started;
	private boolean ready;
	private boolean persistenceWritable;
	private boolean hostSettingsSynchronized;
	private int loadGeneration;
	private int ignoreServerNpcLootTick = Integer.MIN_VALUE;
	private File activeFile;

	@Inject
	public LootshareController(Client client, ClientThread clientThread, PartyService partyService,
	                                    LootshareConfig config, ScheduledExecutorService executor,
	                                    LootCaptureService captureService,
	                                    LootshareEngine engine, LootshareCalculator calculator,
	                                    LootshareStorage storage)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.config = config;
		this.executor = executor;
		this.captureService = captureService;
		this.engine = engine;
		this.calculator = calculator;
		this.storage = storage;
	}

	private static boolean capturesLootType(LootshareSettings settings, LootRecordType lootType)
	{
		switch (lootType)
		{
			case NPC:
				return settings.isCaptureNpcLoot();
			case EVENT:
				return settings.isCaptureEventLoot();
			case PLAYER:
				return false;
			case PICKPOCKET:
				return false;
			case UNKNOWN:
			default:
				return settings.isCaptureUnknownLoot();
		}
	}

	private static boolean isLootKeySource(String sourceLabel)
	{
		return sourceLabel != null && Text.removeTags(sourceLabel).toLowerCase(Locale.ROOT).contains("loot key");
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
			hostSettingsSynchronized = false;
			loadGeneration++;
			deferredActions.clear();
			ignoreServerNpcLootTick = Integer.MIN_VALUE;
		}
		captureService.resetDeduplication();
		memberNames.clear();
	}

	public void setStateChangeListener(Runnable stateChangeListener)
	{
		this.stateChangeListener = stateChangeListener == null ? () -> {
		} : stateChangeListener;
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
			hostSettingsSynchronized = false;
		}
		captureService.resetDeduplication();
		memberNames.clear();
		loadProfile(nextFile);
	}

	public void onLootReceived(LootReceived received)
	{
		if (received == null)
		{
			return;
		}
		LootRecordType lootType = received.getType() == null ? LootRecordType.UNKNOWN : received.getType();
		LootshareSettings settings = effectiveHostSettings();
		if (settings == null || !capturesLootType(settings, lootType))
		{
			return;
		}
		String sourceLabel = received.getName();
		if (sourceLabel == null || sourceLabel.trim().isEmpty())
		{
			sourceLabel = received.getType() == null
				? "Loot"
				: received.getType().name().replace('_', ' ');
		}
		if (isLootKeySource(sourceLabel))
		{
			return;
		}
		captureLocalLoot(sourceLabel, received.getItems(), settings);
	}

	public void onChatMessage(ChatMessage event)
	{
		if (event == null)
		{
			return;
		}
		ChatMessageType type = event.getType();
		String message = event.getMessage();
		if ((type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM
			|| type == ChatMessageType.MESBOX) && message != null
			&& PICKPOCKET_PATTERN.matcher(message).matches())
		{
			// ServerNpcLoot also covers pickpockets. Keep those on LootReceived's existing PICKPOCKET path.
			ignoreServerNpcLootTick = client.getTickCount();
		}
	}

	public void onServerNpcLoot(ServerNpcLoot received)
	{
		if (received == null || received.getComposition() == null
			|| ignoreServerNpcLootTick == client.getTickCount())
		{
			return;
		}
		LootshareSettings settings = effectiveHostSettings();
		if (settings == null || !settings.isCaptureNpcLoot())
		{
			return;
		}
		NPCComposition composition = received.getComposition();
		String sourceLabel = composition.getName();
		if (sourceLabel == null)
		{
			sourceLabel = "NPC";
		}
		else
		{
			sourceLabel = Text.removeTags(sourceLabel).trim();
			if (sourceLabel.isEmpty() || "null".equalsIgnoreCase(sourceLabel))
			{
				sourceLabel = "NPC";
			}
		}
		log.debug("Community Lootshare received NPC loot source={} stackCount={}", sourceLabel,
			received.getItems() == null ? 0 : received.getItems().size());
		captureLocalLoot(sourceLabel, received.getItems(), settings);
	}

	private void captureLocalLoot(String sourceLabel, Collection<ItemStack> items, LootshareSettings settings)
	{
		if (!partyService.isInParty() || settings == null)
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
		Optional<SharedLootEvent> captured = captureService.capture(sourceLabel, items,
			recipient, Instant.now(), client.getTickCount());
		if (!captured.isPresent())
		{
			return;
		}
		SharedLootEvent capturedEvent = captured.get();
		log.debug("Community Lootshare captured loot source={} itemCount={} total={}",
			capturedEvent.getSourceLabel(), capturedEvent.getItems().size(), capturedEvent.getTotal());
		LootProposal proposal = LootProposal.pending(partyId, localMember.getMemberId(), capturedEvent);
		executeWhenReady(() -> recordLocalProposal(proposal));
	}

	private LootshareSettings effectiveHostSettings()
	{
		if (!isReady() || !partyService.isInParty()
			|| engine.getActivePartyId() != partyService.getPartyId())
		{
			return null;
		}
		return getActiveHostSettings().orElse(null);
	}

	public void onProposalMessage(ProposalMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		Optional<ProposalMessage.DecodedProposal> decoded =
			message.decodeWithMetadata(partyService.getPartyId());
		decoded.ifPresent(envelope -> executeWhenReady(() -> {
			LootProposal proposal = envelope.getProposal();
			if (proposal.getOwnerMemberId() != message.getMemberId()
				&& engine.getActiveHostMemberId() != message.getMemberId())
			{
				return;
			}
			if (envelope.isManualGp())
			{
				return;
			}
			rememberMemberName(proposal.getOwnerMemberId(), proposal.getEvent().getRecipient());
			MutationResult result = engine.addProposal(proposal);
			boolean decisionsApplied = (result == MutationResult.APPLIED || result == MutationResult.DUPLICATE)
				&& resolvePendingProposalsAsHost();
			if (result == MutationResult.APPLIED || decisionsApplied)
			{
				queueCurrentStateForSave();
				notifyStateChanged();
			}
		}));
	}

	public void onDecisionMessage(DecisionMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		Optional<DecisionMessage.DecodedDecision> decoded = message.decode();
		decoded.ifPresent(decision -> executeWhenReady(() -> {
			DecisionOutcome outcome = engine.decide(decision.getProposalId(), message.getMemberId(),
				decision.getStatus(), decision.getDecidedAt(), decision.getParticipants());
			if (outcome.getResult() == MutationResult.APPLIED)
			{
				rememberParticipantNames(decision.getParticipants());
				queueCurrentStateForSave();
				notifyStateChanged();
			}
		}));
	}

	public void onMemberIdentityMessage(MemberIdentityMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		message.decode().ifPresent(displayName -> executeWhenReady(() -> {
			if (partyService.isInParty() && partyService.getMemberById(message.getMemberId()) != null)
			{
				rememberMemberName(message.getMemberId(), displayName);
				notifyStateChanged();
			}
		}));
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event != null && event.getGameState() == GameState.LOGGED_IN)
		{
			executeWhenReady(this::announceLocalIdentity);
		}
	}

	public void onHostMessage(HostMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		Optional<HostMessage.DecodedHostState> decoded = message.decode();
		decoded.ifPresent(hostState -> executeWhenReady(() -> {
			if (!partyService.isInParty()
				|| engine.getActivePartyId() != partyService.getPartyId()
				|| partyService.getMemberById(message.getMemberId()) == null
				|| partyService.getMemberById(hostState.getHostMemberId()) == null)
			{
				return;
			}

			boolean hostVacated = vacateMissingHost();
			long activeHostMemberId = engine.getActiveHostMemberId();
			if (!hostSettingsSynchronized && activeHostMemberId > 0L
				&& activeHostMemberId != message.getMemberId()
				&& hostState.getHostMemberId() == message.getMemberId()
				&& hostState.getRevision() > engine.getActiveHostRevision()
				&& engine.vacateHost(activeHostMemberId) == MutationResult.APPLIED)
			{
				hostVacated = true;
			}
			if (engine.getActiveHostMemberId() == 0L && engine.getActiveHostRevision() == 0L
				&& hostState.getRevision() == 1L && !isFirstPartyMember(message.getMemberId()))
			{
				if (hostVacated)
				{
					queueCurrentStateForSave();
					notifyStateChanged();
				}
				return;
			}
			MutationResult result = engine.updateHostState(message.getMemberId(), hostState.getHostMemberId(),
				hostState.getSettings(), hostState.getRevision(), hostState.getApprovalStatuses());
			if (result == MutationResult.APPLIED || result == MutationResult.DUPLICATE)
			{
				hostSettingsSynchronized = true;
				boolean decisionsApplied = resolvePendingProposalsAsHost();
				if (result == MutationResult.APPLIED || decisionsApplied)
				{
					queueCurrentStateForSave();
				}
				notifyStateChanged();
				publishLocalHostConfigurationIfChanged();
			}
			else if (hostVacated)
			{
				queueCurrentStateForSave();
				notifyStateChanged();
			}
		}));
	}

	public void onUserJoin(UserJoin event)
	{
		if (event == null || !partyService.isInParty() || event.getPartyId() != partyService.getPartyId())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		// PartyService emits existing members before the local member when joining an established party.
		if (local == null || event.getMemberId() != local.getMemberId())
		{
			return;
		}
		executeWhenReady(() -> {
			if (!partyService.isInParty() || event.getPartyId() != partyService.getPartyId()
				|| engine.getActivePartyId() != partyService.getPartyId())
			{
				return;
			}
			boolean hostVacated = vacateMissingHost();
			boolean hostClaimed = claimHostIfEligible(false);
			publishLocalHostConfigurationIfChanged();
			if (hostVacated && !hostClaimed)
			{
				queueCurrentStateForSave();
				notifyStateChanged();
			}
		});
	}

	public void onUserPart(UserPart event)
	{
		if (event == null || !partyService.isInParty())
		{
			return;
		}
		executeWhenReady(() -> {
			if (!partyService.isInParty() || engine.getActivePartyId() != partyService.getPartyId())
			{
				return;
			}
			long hostMemberId = engine.getActiveHostMemberId();
			PartyMember local = partyService.getLocalMember();
			if (event.getMemberId() != hostMemberId)
			{
				if (local == null || local.getMemberId() != hostMemberId)
				{
					return;
				}
				boolean decisionsApplied = resolvePendingProposalsAsHost();
				long revision = nextHostRevision();
				MutationResult removal = revision == 0L
					? MutationResult.LIMIT_REACHED
					: engine.updateMemberApprovalStatus(hostMemberId, event.getMemberId(),
					MemberApprovalStatus.PENDING, revision);
				if (removal == MutationResult.APPLIED)
				{
					queueCurrentStateForSave();
					sendCurrentHostState();
					notifyStateChanged();
				}
				else if (decisionsApplied)
				{
					queueCurrentStateForSave();
					notifyStateChanged();
				}
				return;
			}
			MutationResult result = engine.vacateHost(event.getMemberId());
			if (result != MutationResult.APPLIED)
			{
				return;
			}
			hostSettingsSynchronized = false;
			boolean hostClaimed = claimHostIfEligible(true);
			if (!hostClaimed)
			{
				queueCurrentStateForSave();
				notifyStateChanged();
			}
		});
	}

	public void onLocalConfigurationChanged()
	{
		executeWhenReady(this::publishLocalHostConfigurationIfChanged);
	}

	public void onMinimumSharedLootValueChanged()
	{
		onLocalConfigurationChanged();
	}

	public void onPartyChanged(PartyChanged event)
	{
		captureService.resetDeduplication();
		memberNames.clear();
		ignoreServerNpcLootTick = Integer.MIN_VALUE;
		hostSettingsSynchronized = false;
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
				announceLocalIdentity();
				claimHostIfEligible(false);
				publishLocalHostConfigurationIfChanged();
				requestPartySync();
			}
			notifyStateChanged();
		});
	}

	public void onUserSync(UserSync sync)
	{
		if (sync == null || !isReady() || !partyService.isInParty())
		{
			return;
		}
		announceLocalIdentity();
		PartyMember requester = partyService.getMemberById(sync.getMemberId());
		PartyMember local = partyService.getLocalMember();
		if (requester == null || local == null || local.getMemberId() == requester.getMemberId())
		{
			return;
		}
		if (engine.getActiveHostMemberId() != local.getMemberId())
		{
			return;
		}

		sendCurrentHostState();
		for (LootProposal proposal : engine.getProposalsForActiveParty())
		{
			partyService.send(new ProposalMessage(proposal));
			if (proposal.getStatus() != LootProposalStatus.PENDING)
			{
				partyService.send(new DecisionMessage(proposal));
			}
		}
	}

	/**
	 * Retained for callers/tests; automatic host decisions normally finalize proposals first.
	 */
	public MutationResult approveProposal(String proposalId)
	{
		return decideOwnedProposal(proposalId, LootProposalStatus.ACCEPTED);
	}

	/**
	 * Retained for callers/tests; automatic host decisions normally finalize proposals first.
	 */
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

	public List<LootProposal> getActivePartyProposals()
	{
		return isReady() ? engine.getProposalsForActiveParty() : Collections.emptyList();
	}

	public Optional<LootshareSession> getActiveSession()
	{
		return hasLiveActiveParty() ? engine.getActiveSession() : Optional.empty();
	}

	public LootshareCalculation getActiveCalculation()
	{
		if (!hasLiveActiveParty() || !getActiveHostSettings().isPresent())
		{
			return LootshareCalculation.empty();
		}
		return calculator.calculate(engine.getActiveSession().orElse(null));
	}

	private boolean hasLiveActiveParty()
	{
		return isReady() && partyService.isInParty()
			&& engine.getActivePartyId() == partyService.getPartyId();
	}

	public long getActiveHostMemberId()
	{
		return isReady() ? engine.getActiveHostMemberId() : 0L;
	}

	public MemberApprovalStatus getMemberApprovalStatus(long memberId)
	{
		return isReady() ? engine.getActiveMemberApprovalStatus(memberId) : MemberApprovalStatus.PENDING;
	}

	public Optional<LootshareSettings> getActiveHostSettings()
	{
		long hostMemberId = engine.getActiveHostMemberId();
		return !isReady() || !hostSettingsSynchronized || !partyService.isInParty()
			|| engine.getActivePartyId() != partyService.getPartyId()
			|| hostMemberId <= 0L || partyService.getMemberById(hostMemberId) == null
			? Optional.empty()
			: engine.getActiveHostSettings();
	}

	/**
	 * Must be invoked on the RuneLite client thread by the sidebar controller.
	 */
	public MutationResult setMemberApproved(long memberId, boolean approved)
	{
		if (!isReady() || !partyService.isInParty())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || engine.getActiveHostMemberId() != local.getMemberId())
		{
			return MutationResult.NOT_HOST;
		}
		if (partyService.getMemberById(memberId) == null)
		{
			return MutationResult.NOT_FOUND;
		}
		MemberApprovalStatus nextStatus = approved
			? MemberApprovalStatus.APPROVED
			: MemberApprovalStatus.EXCLUDED;
		if (memberId == local.getMemberId() && nextStatus != MemberApprovalStatus.APPROVED)
		{
			return MutationResult.INVALID;
		}

		boolean decisionsApplied = resolvePendingProposalsAsHost();
		if (engine.getActiveMemberApprovalStatus(memberId) == nextStatus)
		{
			if (decisionsApplied)
			{
				queueCurrentStateForSave();
				notifyStateChanged();
			}
			return MutationResult.DUPLICATE;
		}
		long revision = nextHostRevision();
		if (revision == 0L)
		{
			return MutationResult.LIMIT_REACHED;
		}
		MutationResult result = engine.updateMemberApprovalStatus(local.getMemberId(), memberId,
			nextStatus, revision);
		if (result == MutationResult.APPLIED)
		{
			queueCurrentStateForSave();
			sendCurrentHostState();
			notifyStateChanged();
		}
		else if (decisionsApplied)
		{
			queueCurrentStateForSave();
			notifyStateChanged();
		}
		return result;
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
			return proposal == null ? MutationResult.NOT_FOUND : MutationResult.NOT_HOST;
		}
		if (engine.getActiveHostMemberId() != local.getMemberId())
		{
			return MutationResult.NOT_HOST;
		}

		LootshareSettings settings = decision == LootProposalStatus.ACCEPTED ? effectiveHostSettings() : null;
		if (decision == LootProposalStatus.ACCEPTED && settings == null)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (decision == LootProposalStatus.ACCEPTED
			&& engine.getActiveMemberApprovalStatus(proposal.getOwnerMemberId()) != MemberApprovalStatus.APPROVED)
		{
			return MutationResult.INVALID;
		}
		List<LootshareParticipant> roster = decision == LootProposalStatus.ACCEPTED
			? snapshotRoster(proposal, local, settings)
			: Collections.emptyList();
		Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
		Instant decisionTime = now.isBefore(proposal.getEvent().getCapturedAt())
			? proposal.getEvent().getCapturedAt()
			: now;
		DecisionOutcome outcome = engine.decide(proposalId, local.getMemberId(), decision, decisionTime, roster);
		if (outcome.getResult() == MutationResult.APPLIED)
		{
			queueCurrentStateForSave();
			partyService.send(new DecisionMessage(outcome.getProposal()));
			notifyStateChanged();
		}
		return outcome.getResult();
	}

	private List<LootshareParticipant> snapshotRoster(LootProposal proposal, PartyMember local,
	                                                  LootshareSettings settings)
	{
		if (engine.getActiveMemberApprovalStatus(proposal.getOwnerMemberId()) != MemberApprovalStatus.APPROVED
			|| partyService.getMemberById(proposal.getOwnerMemberId()) == null)
		{
			return Collections.emptyList();
		}
		Map<Long, LootshareParticipant> participants = new LinkedHashMap<>();
		String ownerName = normalizedMemberName(proposal.getEvent().getRecipient());
		if (ownerName == null)
		{
			return Collections.emptyList();
		}
		rememberMemberName(proposal.getOwnerMemberId(), ownerName);
		participants.put(proposal.getOwnerMemberId(), new LootshareParticipant(proposal.getOwnerMemberId(), ownerName));
		for (PartyMember member : partyService.getMembers())
		{
			if (participants.size() >= LootProposal.MAX_PARTICIPANTS)
			{
				break;
			}
			if (engine.getActiveMemberApprovalStatus(member.getMemberId()) != MemberApprovalStatus.APPROVED)
			{
				continue;
			}
			if (!settings.isIncludeLoggedOutMembers() && !member.isLoggedIn()
				&& member.getMemberId() != local.getMemberId())
			{
				continue;
			}
			if (member.getMemberId() == proposal.getOwnerMemberId())
			{
				continue;
			}
			String displayName = displayName(member, proposal);
			if (displayName != null)
			{
				participants.put(member.getMemberId(), new LootshareParticipant(member.getMemberId(), displayName));
			}
		}
		List<LootshareParticipant> roster = new ArrayList<>(participants.values());
		roster.sort(Comparator.comparingLong(LootshareParticipant::getMemberId));
		return roster;
	}

	private boolean resolvePendingProposalsAsHost()
	{
		if (!partyService.isInParty())
		{
			return false;
		}
		PartyMember local = partyService.getLocalMember();
		LootshareSettings settings = engine.getActiveHostSettings().orElse(null);
		if (local == null || settings == null || engine.getActiveHostMemberId() != local.getMemberId())
		{
			return false;
		}

		List<LootProposal> pending = engine.getPendingProposalsForActiveParty();
		pending.sort(Comparator.comparing((LootProposal proposal) -> proposal.getEvent().getCapturedAt())
			.thenComparing(LootProposal::getProposalId));
		boolean changed = false;
		for (LootProposal proposal : pending)
		{
			boolean ownerApproved = engine.getActiveMemberApprovalStatus(proposal.getOwnerMemberId())
				== MemberApprovalStatus.APPROVED
				&& partyService.getMemberById(proposal.getOwnerMemberId()) != null;
			LootProposalStatus decision = ownerApproved
				? LootProposalStatus.ACCEPTED
				: LootProposalStatus.REJECTED;
			List<LootshareParticipant> roster = ownerApproved
				? snapshotRoster(proposal, local, settings)
				: Collections.emptyList();
			Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
			Instant decisionTime = now.isBefore(proposal.getEvent().getCapturedAt())
				? proposal.getEvent().getCapturedAt()
				: now;
			DecisionOutcome outcome = engine.decide(proposal.getProposalId(), local.getMemberId(), decision,
				decisionTime, roster);
			if (outcome.getResult() == MutationResult.APPLIED)
			{
				partyService.send(new DecisionMessage(outcome.getProposal()));
				changed = true;
			}
		}
		return changed;
	}

	private String displayName(PartyMember member, LootProposal proposal)
	{
		if (proposal != null && member.getMemberId() == proposal.getOwnerMemberId())
		{
			String ownerName = normalizedMemberName(proposal.getEvent().getRecipient());
			rememberMemberName(member.getMemberId(), ownerName);
			return ownerName;
		}
		rememberMemberName(member.getMemberId(), member.getDisplayName());
		return memberNames.get(member.getMemberId());
	}

	public Optional<String> getKnownMemberName(PartyMember member)
	{
		if (member == null)
		{
			return Optional.empty();
		}
		return Optional.ofNullable(displayName(member, null));
	}

	private void announceLocalIdentity()
	{
		if (!partyService.isInParty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		Player player = client.getLocalPlayer();
		String name = player == null ? null : normalizedMemberName(player.getName());
		if (local == null || name == null)
		{
			return;
		}
		rememberMemberName(local.getMemberId(), name);
		partyService.send(new MemberIdentityMessage(name));
	}

	private void rememberParticipantNames(Collection<LootshareParticipant> participants)
	{
		if (participants == null)
		{
			return;
		}
		for (LootshareParticipant participant : participants)
		{
			if (participant != null)
			{
				rememberMemberName(participant.getMemberId(), participant.getDisplayName());
			}
		}
	}

	private void rememberMemberName(long memberId, String displayName)
	{
		String normalized = normalizedMemberName(displayName);
		if (memberId > 0L && normalized != null)
		{
			memberNames.put(memberId, normalized);
		}
	}

	private static String normalizedMemberName(String value)
	{
		if (value == null)
		{
			return null;
		}
		String normalized = Text.sanitize(Text.removeTags(value)).trim();
		return normalized.isEmpty() || "<unknown>".equalsIgnoreCase(normalized)
			|| normalized.length() > SharedLootEvent.MAX_RECIPIENT_LENGTH ? null : normalized;
	}

	private boolean claimHostIfEligible(boolean deterministicSuccessor)
	{
		if (!partyService.isInParty() || engine.getActivePartyId() != partyService.getPartyId()
			|| engine.getActiveHostMemberId() != 0L)
		{
			return false;
		}
		PartyMember local = partyService.getLocalMember();
		List<PartyMember> members = partyService.getMembers();
		if (local == null || members.isEmpty())
		{
			return false;
		}

		PartyMember candidate = members.get(0);
		if (deterministicSuccessor)
		{
			for (PartyMember member : members)
			{
				if (member.getMemberId() < candidate.getMemberId())
				{
					candidate = member;
				}
			}
		}
		if (candidate.getMemberId() != local.getMemberId())
		{
			return false;
		}

		long revision = nextHostRevision();
		if (revision == 0L)
		{
			return false;
		}
		MutationResult result = engine.updateHostState(local.getMemberId(), local.getMemberId(),
			configuredSettings(), revision);
		if (result != MutationResult.APPLIED)
		{
			return false;
		}
		hostSettingsSynchronized = true;
		queueCurrentStateForSave();
		sendCurrentHostState();
		notifyStateChanged();
		return true;
	}

	private boolean vacateMissingHost()
	{
		long hostMemberId = engine.getActiveHostMemberId();
		boolean vacated = hostMemberId > 0L && partyService.getMemberById(hostMemberId) == null
			&& engine.vacateHost(hostMemberId) == MutationResult.APPLIED;
		if (vacated)
		{
			hostSettingsSynchronized = false;
		}
		return vacated;
	}

	private void publishLocalHostConfigurationIfChanged()
	{
		if (!partyService.isInParty() || engine.getActivePartyId() != partyService.getPartyId())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		LootshareSettings configuredSettings = configuredSettings();
		if (local == null || engine.getActiveHostMemberId() != local.getMemberId())
		{
			return;
		}
		if (!hostSettingsSynchronized && !isFirstPartyMember(local.getMemberId()))
		{
			return;
		}
		hostSettingsSynchronized = true;
		if (engine.getActiveHostSettings().map(configuredSettings::equals).orElse(false))
		{
			return;
		}
		long revision = nextHostRevision();
		if (revision == 0L)
		{
			return;
		}
		MutationResult result = engine.updateHostState(local.getMemberId(), local.getMemberId(),
			configuredSettings, revision);
		if (result == MutationResult.APPLIED)
		{
			queueCurrentStateForSave();
			sendCurrentHostState();
			notifyStateChanged();
		}
	}

	private void sendCurrentHostState()
	{
		long hostMemberId = engine.getActiveHostMemberId();
		long revision = engine.getActiveHostRevision();
		PartyMember local = partyService.getLocalMember();
		if (!partyService.isInParty() || engine.getActivePartyId() != partyService.getPartyId()
			|| hostMemberId <= 0L || revision <= 0L)
		{
			return;
		}
		if (local == null || local.getMemberId() != hostMemberId)
		{
			return;
		}
		engine.getActiveHostSettings().ifPresent(settings -> partyService.send(
			new HostMessage(hostMemberId, settings, revision,
				engine.getActiveMemberApprovalStatuses())));
	}

	private LootshareSettings configuredSettings()
	{
		int configuredValue = config == null
			? LootshareConfig.DEFAULT_MINIMUM_SHARED_LOOT_VALUE
			: config.minimumSharedLootValue();
		long minimumSharedLootValue = configuredValue < 0
			? LootshareConfig.DEFAULT_MINIMUM_SHARED_LOOT_VALUE
			: configuredValue;
		if (config == null)
		{
			return LootshareSettings.defaults(minimumSharedLootValue);
		}
		return new LootshareSettings(minimumSharedLootValue,
			com.communitylootshare.domain.LootValueBasis.GRAND_EXCHANGE,
			config.captureNpcLoot(), config.captureEventLoot(), false,
			false, config.captureUnknownLoot(), config.includeLoggedOutMembers());
	}

	private long nextHostRevision()
	{
		long revision = engine.getActiveHostRevision();
		return revision == Long.MAX_VALUE ? 0L : revision + 1L;
	}

	private boolean isFirstPartyMember(long memberId)
	{
		List<PartyMember> members = partyService.getMembers();
		return !members.isEmpty() && members.get(0).getMemberId() == memberId;
	}

	private void recordLocalProposal(LootProposal proposal)
	{
		if (!partyService.isInParty() || partyService.getPartyId() != proposal.getPartyId())
		{
			return;
		}
		rememberMemberName(proposal.getOwnerMemberId(), proposal.getEvent().getRecipient());
		if (engine.addProposal(proposal) == MutationResult.APPLIED)
		{
			partyService.send(new ProposalMessage(proposal));
			resolvePendingProposalsAsHost();
			queueCurrentStateForSave();
			notifyStateChanged();
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
				LootshareStorage.LoadResult result = storage.load(file);
				clientThread.invokeLater(() -> completeLoad(generation, file, result));
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Community Lootshare history load was rejected during shutdown", e);
		}
	}

	private void completeLoad(int generation, File file, LootshareStorage.LoadResult result)
	{
		List<Runnable> queued;
		synchronized (lifecycleLock)
		{
			if (!started || generation != loadGeneration || !Objects.equals(file, activeFile))
			{
				return;
			}
			engine.restore(result.getState());
			hostSettingsSynchronized = false;
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
			claimHostIfEligible(false);
			PartyMember local = partyService.getLocalMember();
			if (local != null && engine.getActiveHostMemberId() == local.getMemberId())
			{
				// A plugin restart inside an existing Party can trust its own persisted authority.
				hostSettingsSynchronized = true;
			}
			publishLocalHostConfigurationIfChanged();
		}
		for (Runnable action : queued)
		{
			executeWhenReady(action);
		}
		requestPartySync();
		notifyStateChanged();
	}

	private void notifyStateChanged()
	{
		try
		{
			stateChangeListener.run();
		}
		catch (RuntimeException e)
		{
			log.debug("Community Lootshare state listener failed", e);
		}
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
		private final LootshareState state;

		private SaveRequest(File file, LootshareState state)
		{
			this.file = file;
			this.state = state;
		}
	}
}
