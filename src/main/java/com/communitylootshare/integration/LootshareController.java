/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.integration;

import com.communitylootshare.LootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.capture.LootCaptureService.CaptureOrigin;
import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.LootDecisionReceipt;
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
import com.communitylootshare.party.ProposalMessage;
import com.communitylootshare.party.RecoveryMessage;
import com.communitylootshare.persistence.LootshareStorage;
import com.communitylootshare.sessions.LootshareEngine;
import com.communitylootshare.sessions.LootshareEngine.DecisionOutcome;
import com.communitylootshare.sessions.LootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.io.File;
import java.time.Instant;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
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
	private static final String MANUAL_GP_SOURCE_LABEL = "Manual GP";

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
	private final Map<File, SaveRequest> saveRequests = new LinkedHashMap<>();
	private final AtomicBoolean saveWorkerRunning = new AtomicBoolean();
	private volatile Runnable stateChangeListener = () -> {
	};

	private boolean started;
	private boolean ready;
	private boolean replayingDeferredActions;
	private boolean persistenceWritable;
	private boolean hostSettingsSynchronized;
	private int loadGeneration;
	private int ignoreServerNpcLootTick = Integer.MIN_VALUE;
	private File activeFile;
	private KeyPair decisionSigningKey;
	private final Set<Long> recoveryAwaiting = new HashSet<>();
	private ScheduledFuture<?> recoveryTimeout;
	private boolean recoveringHistory;
	private int recoveryGeneration;

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
				return settings.isCapturePlayerLoot();
			case PICKPOCKET:
				return settings.isCapturePickpocketLoot();
			case UNKNOWN:
			default:
				return settings.isCaptureUnknownLoot();
		}
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
			decisionSigningKey = null;
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
			clearHistoryRecovery();
			ignoreServerNpcLootTick = Integer.MIN_VALUE;
		}
		captureService.resetDeduplication();
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
			clearHistoryRecovery();
			decisionSigningKey = null;
		}
		captureService.resetDeduplication();
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
		captureLocalLoot(sourceLabel, received.getItems(), settings,
			lootType == LootRecordType.NPC ? CaptureOrigin.LOOT_RECEIVED : CaptureOrigin.INDEPENDENT);
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
		captureLocalLoot(sourceLabel, received.getItems(), settings, CaptureOrigin.SERVER_NPC);
	}

	private void captureLocalLoot(String sourceLabel, Collection<ItemStack> items, LootshareSettings settings,
	                              CaptureOrigin origin)
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
			recipient, Instant.now(), client.getTickCount(), settings.getLootValueBasis(), origin);
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
			// A host replay includes frozen historical contributions from departed/excluded members.
			// New guest contributions still require the current permission and eligibility policy.
			if (envelope.isManualGp() && message.getMemberId() != engine.getActiveHostMemberId()
				&& !isManualGpAllowed(message.getMemberId(), proposal.getOwnerMemberId()))
			{
				return;
			}
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
			if (message.getMemberId() != engine.getActiveHostMemberId())
			{
				return;
			}
			LootProposal existing = engine.getProposal(decision.getProposalId()).orElse(null);
			if (existing == null)
			{
				return;
			}
			final LootProposal finalized;
			try
			{
				finalized = LootProposal.pending(existing.getPartyId(), existing.getOwnerMemberId(), existing.getEvent())
					.decide(decision.getStatus(), decision.getDecidedAt(), decision.getParticipants())
					.withDecisionReceipt(decision.getReceipt());
			}
			catch (RuntimeException e)
			{
				return;
			}
			if (decision.getReceipt() != null && (!isTrustedReceipt(finalized)
				|| (decision.getReceipt().getSignerMemberId() != message.getMemberId()
					&& !engine.getActiveDecisionAuthorizations().contains(LootDecisionReceipt.decisionId(finalized)))))
			{
				return;
			}
			DecisionOutcome outcome = engine.importFinalizedProposal(message.getMemberId(), finalized);
			if (outcome.getResult() == MutationResult.APPLIED)
			{
				rememberActiveHostState();
				queueCurrentStateForSave();
				notifyStateChanged();
			}
		}));
	}

	public void onRecoveryMessage(RecoveryMessage message)
	{
		if (message == null || !partyService.isInParty()
			|| partyService.getMemberById(message.getMemberId()) == null)
		{
			return;
		}
		executeWhenReady(() -> {
			PartyMember local = partyService.getLocalMember();
			if (local == null || local.getMemberId() != message.getTargetHostMemberId()
				|| local.getMemberId() != engine.getActiveHostMemberId()
				|| engine.getActivePartyId() != partyService.getPartyId()
				|| partyService.getMemberById(message.getMemberId()) == null || !hostSettingsSynchronized)
			{
				return;
			}
			if (message.isComplete())
			{
				recoveryAwaiting.remove(message.getMemberId());
				if (recoveringHistory && recoveryAwaiting.isEmpty())
				{
					finishHistoryRecovery();
				}
				return;
			}
			message.decode(partyService.getPartyId()).ifPresent(finalized -> {
				// A once-trusted key can still sign and backdate new decisions after transfer.
				// The authenticated host snapshot must have committed to this exact record.
				if (!engine.getActiveDecisionAuthorizations().contains(LootDecisionReceipt.decisionId(finalized)))
				{
					log.debug("Ignoring uncommitted Community Lootshare recovery decision {}", finalized.getProposalId());
					return;
				}
				boolean signed = isTrustedReceipt(finalized);
				// Legacy unsigned history can be vouched for only by the same authenticated
				// bootstrap member that is already trusted to confirm host authority.
				boolean legacyConfirmation = finalized.getDecisionReceipt() == null
					&& isFirstPartyMember(message.getMemberId()) && recoveringHistory
					&& recoveryAwaiting.contains(message.getMemberId());
				if (!signed && !legacyConfirmation)
				{
					return;
				}
				LootProposal recovered = signed ? finalized : signDecision(finalized);
				if (recovered == null)
				{
					return;
				}
				DecisionOutcome outcome = engine.importFinalizedProposal(local.getMemberId(), recovered);
				if (outcome.getResult() == MutationResult.APPLIED)
				{
					rememberActiveHostState();
					partyService.send(new ProposalMessage(outcome.getProposal()));
					partyService.send(new DecisionMessage(outcome.getProposal()));
					queueCurrentStateForSave();
					notifyStateChanged();
				}
			});
		});
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

			boolean wasSynchronized = hostSettingsSynchronized;
			boolean hostVacated = vacateMissingHost();
			long activeHostMemberId = engine.getActiveHostMemberId();
			boolean bootstrapSender = (!hostSettingsSynchronized
				|| (activeHostMemberId == 0L && engine.getActiveHostRevision() == 0L))
				&& isFirstPartyMember(message.getMemberId());
			long authorizedMemberId = message.getMemberId();
			if (activeHostMemberId > 0L && activeHostMemberId != message.getMemberId())
			{
				if (!bootstrapSender)
				{
					return;
				}
				// The first Party member is the bootstrap authority. It can confirm a transfer
				// to late joiners, but arbitrary self-announced revisions cannot replace a host.
				authorizedMemberId = activeHostMemberId;
			}
			if (activeHostMemberId == 0L)
			{
				boolean initialSnapshot = engine.getActiveHostRevision() == 0L;
				boolean successorClaim = message.getMemberId() == hostState.getHostMemberId()
					&& isDeterministicSuccessor(message.getMemberId());
				if (!(bootstrapSender || (!initialSnapshot && successorClaim)))
				{
					if (hostVacated)
					{
						queueCurrentStateForSave();
						notifyStateChanged();
					}
					return;
				}
				authorizedMemberId = hostState.getHostMemberId();
			}
			if (!engine.canTrustDecisionKeys(hostState.getDecisionKeys())
				|| !engine.canTrustDecisionAuthorizations(hostState.getDecisionAuthorizations()))
			{
				return;
			}
			MutationResult result = engine.updateHostState(authorizedMemberId, hostState.getHostMemberId(),
				hostState.getSettings(), hostState.getRevision(), hostState.getApprovalStatuses());
			if (result == MutationResult.APPLIED || result == MutationResult.DUPLICATE)
			{
				hostSettingsSynchronized = true;
				boolean keysChanged = engine.trustDecisionKeys(hostState.getDecisionKeys());
				boolean authorizationsChanged = engine.trustDecisionAuthorizations(hostState.getDecisionAuthorizations());
				rememberActiveHostState();
				PartyMember local = partyService.getLocalMember();
				boolean becomingHost = local != null && local.getMemberId() == hostState.getHostMemberId()
					&& activeHostMemberId != local.getMemberId();
				if (becomingHost)
				{
					beginHistoryRecovery();
				}
				else if (local == null || local.getMemberId() != hostState.getHostMemberId())
				{
					clearHistoryRecovery();
				}
				boolean decisionsApplied = resolvePendingProposalsAsHost();
				if (result == MutationResult.APPLIED || decisionsApplied || keysChanged || authorizationsChanged)
				{
					queueCurrentStateForSave();
				}
				notifyStateChanged();
				publishLocalHostConfigurationIfChanged();
				if (!wasSynchronized && !becomingHost)
				{
					// A transferred host may have replayed before the bootstrap confirmation.
					// Request the ledger now that this client can authenticate its host messages.
					requestPartySync();
				}
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
		clearHistoryRecovery();
		decisionSigningKey = null;
		captureService.resetDeduplication();
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
				claimHostIfEligible(false);
				publishLocalHostConfigurationIfChanged();
				requestPartySync();
			}
			notifyStateChanged();
		});
	}

	public void onUserSync(UserSync sync)
	{
		if (sync == null || !isReady() || !partyService.isInParty()
			|| engine.getActivePartyId() != partyService.getPartyId())
		{
			return;
		}
		PartyMember requester = partyService.getMemberById(sync.getMemberId());
		PartyMember local = partyService.getLocalMember();
		if (requester == null || local == null || local.getMemberId() == requester.getMemberId())
		{
			return;
		}
		if (engine.getActiveHostMemberId() != local.getMemberId())
		{
			// The founding/first member confirms the current host to fresh clients even after
			// handing authority to someone else. The Party transport authenticates this sender.
			if (hostSettingsSynchronized && isFirstPartyMember(local.getMemberId()))
			{
				engine.getActiveHostSettings().ifPresent(settings -> partyService.send(new HostMessage(
					engine.getActiveHostMemberId(), settings, engine.getActiveHostRevision(),
					engine.getActiveMemberApprovalStatuses(), engine.getActiveDecisionKeys(), engine.getActiveDecisionAuthorizations())));
			}
			if (requester.getMemberId() == engine.getActiveHostMemberId())
			{
				for (LootProposal finalized : engine.getProposalsForActiveParty())
				{
					if (finalized.getStatus() != LootProposalStatus.PENDING)
					{
						partyService.send(new RecoveryMessage(requester.getMemberId(), finalized));
					}
				}
				for (LootProposal pending : engine.getPendingOwnedBy(local.getMemberId()))
				{
					partyService.send(new ProposalMessage(pending));
				}
				partyService.send(RecoveryMessage.completed(requester.getMemberId()));
			}
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
		return isReady() ? engine.getActiveSession() : Optional.empty();
	}

	public LootshareCalculation getActiveCalculation()
	{
		if (!isReady() || !getActiveHostSettings().isPresent())
		{
			return LootshareCalculation.empty();
		}
		return calculator.calculate(engine.getActiveSession().orElse(null));
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
	public MutationResult transferHost(long nextHostMemberId)
	{
		if (!isReady() || !partyService.isInParty())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		PartyMember local = partyService.getLocalMember();
		PartyMember nextHost = partyService.getMemberById(nextHostMemberId);
		if (local == null || engine.getActiveHostMemberId() != local.getMemberId())
		{
			return MutationResult.NOT_HOST;
		}
		if (nextHost == null)
		{
			return MutationResult.NOT_FOUND;
		}
		if (nextHostMemberId == local.getMemberId() || !nextHost.isLoggedIn())
		{
			return MutationResult.INVALID;
		}

		resolvePendingProposalsAsHost();
		long revision = nextHostRevision();
		if (revision == 0L)
		{
			return MutationResult.LIMIT_REACHED;
		}
		MutationResult result = engine.updateHostState(local.getMemberId(), nextHostMemberId,
			engine.getActiveHostSettings().orElse(null), revision);
		if (result == MutationResult.APPLIED)
		{
			clearHistoryRecovery();
			hostSettingsSynchronized = true;
			rememberActiveHostState();
			queueCurrentStateForSave();
			engine.getActiveHostSettings().ifPresent(settings -> partyService.send(
				new HostMessage(nextHostMemberId, settings, revision,
					engine.getActiveMemberApprovalStatuses(), engine.getActiveDecisionKeys(), engine.getActiveDecisionAuthorizations())));
			notifyStateChanged();
		}
		return result;
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

	/**
	 * Adds a Party-synchronised manual GP contribution. Hosts may select any approved member;
	 * guests may select only themselves when the active host policy permits it.
	 * Must be invoked on the RuneLite client thread by the sidebar controller.
	 */
	public MutationResult addManualGp(long memberId, long amount)
	{
		if (!isReady() || !partyService.isInParty())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (amount <= 0L || amount > LootshareSettings.MAXIMUM_SHARED_LOOT_VALUE)
		{
			return MutationResult.INVALID;
		}
		PartyMember local = partyService.getLocalMember();
		PartyMember target = partyService.getMemberById(memberId);
		if (local == null || target == null || !isManualGpAllowed(local.getMemberId(), memberId))
		{
			return MutationResult.NOT_HOST;
		}
		String recipient = displayName(target, null);
		SharedLootEvent event;
		try
		{
			event = new SharedLootEvent("manual-gp-" + UUID.randomUUID(), recipient, MANUAL_GP_SOURCE_LABEL,
				Instant.now(), Collections.singletonList(new com.communitylootshare.domain.SharedLootItem(0, 0, 1L, amount)));
		}
		catch (RuntimeException e)
		{
			return MutationResult.INVALID;
		}
		LootProposal proposal = LootProposal.pending(partyService.getPartyId(), memberId, event);
		recordLocalProposal(proposal, true);
		return engine.getProposal(proposal.getProposalId()).isPresent()
			? MutationResult.APPLIED : MutationResult.INVALID;
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
		DecisionOutcome outcome = finalizeAsHost(proposal, decision, decisionTime, roster);
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
		participants.put(proposal.getOwnerMemberId(), new LootshareParticipant(proposal.getOwnerMemberId(),
			proposal.getEvent().getRecipient()));
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
			participants.put(member.getMemberId(), new LootshareParticipant(member.getMemberId(), displayName));
		}
		List<LootshareParticipant> roster = new ArrayList<>(participants.values());
		roster.sort(Comparator.comparingLong(LootshareParticipant::getMemberId));
		return roster;
	}

	private boolean resolvePendingProposalsAsHost()
	{
		if (!partyService.isInParty() || recoveringHistory)
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
			DecisionOutcome outcome = finalizeAsHost(proposal, decision, decisionTime, roster);
			if (outcome.getResult() == MutationResult.APPLIED)
			{
				partyService.send(new DecisionMessage(outcome.getProposal()));
				changed = true;
			}
		}
		return changed;
	}

	private boolean isManualGpAllowed(long actingMemberId, long ownerMemberId)
	{
		LootshareSettings settings = effectiveHostSettings();
		if (settings == null || actingMemberId <= 0L || ownerMemberId <= 0L
			|| partyService.getMemberById(ownerMemberId) == null
			|| engine.getActiveMemberApprovalStatus(ownerMemberId) != MemberApprovalStatus.APPROVED)
		{
			return false;
		}
		return actingMemberId == engine.getActiveHostMemberId()
			|| (actingMemberId == ownerMemberId && settings.isAllowMemberManualGp());
	}

	private boolean ensureLocalDecisionKey()
	{
		PartyMember local = partyService.getLocalMember();
		if (local == null || local.getMemberId() != engine.getActiveHostMemberId())
		{
			return false;
		}
		try
		{
			if (decisionSigningKey == null)
			{
				decisionSigningKey = storage.decisionKey(activeFile);
			}
			String keyId = LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(decisionSigningKey), local.getMemberId());
			return engine.trustDecisionKeys(Collections.singletonMap(keyId, local.getMemberId()));
		}
		catch (GeneralSecurityException | IllegalArgumentException e)
		{
			log.debug("Unable to establish a Community Lootshare decision signing key", e);
			return false;
		}
	}

	private LootProposal signDecision(LootProposal finalized)
	{
		PartyMember local = partyService.getLocalMember();
		if (local == null || local.getMemberId() != engine.getActiveHostMemberId())
		{
			return null;
		}
		if (ensureLocalDecisionKey())
		{
			sendCurrentHostState();
		}
		if (decisionSigningKey == null || !Long.valueOf(local.getMemberId()).equals(
			engine.getActiveDecisionKeys().get(LootDecisionReceipt.keyId(
				LootDecisionReceipt.publicKey(decisionSigningKey), local.getMemberId()))))
		{
			return null;
		}
		try
		{
			return finalized.withDecisionReceipt(LootDecisionReceipt.sign(local.getMemberId(), decisionSigningKey, finalized));
		}
		catch (GeneralSecurityException e)
		{
			log.debug("Unable to sign a Community Lootshare decision", e);
			return null;
		}
	}

	private boolean isTrustedReceipt(LootProposal finalized)
	{
		LootDecisionReceipt receipt = finalized.getDecisionReceipt();
		return receipt != null && receipt.isTrustedBy(engine.getActiveDecisionKeys()) && receipt.verifies(finalized);
	}

	private DecisionOutcome finalizeAsHost(LootProposal proposal, LootProposalStatus status, Instant at,
	                                       List<LootshareParticipant> roster)
	{
		if (proposal.getStatus() != LootProposalStatus.PENDING)
		{
			return new DecisionOutcome(proposal.getStatus() == status ? MutationResult.DUPLICATE : MutationResult.CONFLICT, proposal);
		}
		if (recoveringHistory)
		{
			return new DecisionOutcome(MutationResult.INVALID, proposal);
		}
		try
		{
			LootProposal signed = signDecision(proposal.decide(status, at, roster));
			DecisionOutcome outcome = signed == null ? new DecisionOutcome(MutationResult.INVALID, proposal)
				: engine.importFinalizedProposal(engine.getActiveHostMemberId(), signed);
			if (outcome.getResult() == MutationResult.APPLIED)
			{
				rememberActiveHostState();
			}
			return outcome;
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to finalize Community Lootshare proposal {}", proposal.getProposalId(), e);
			return new DecisionOutcome(MutationResult.INVALID, proposal);
		}
	}

	private void beginHistoryRecovery()
	{
		clearHistoryRecovery();
		PartyMember local = partyService.getLocalMember();
		if (local == null || local.getMemberId() != engine.getActiveHostMemberId())
		{
			return;
		}
		for (PartyMember member : partyService.getMembers())
		{
			if (member.getMemberId() != local.getMemberId())
			{
				recoveryAwaiting.add(member.getMemberId());
			}
		}
		if (recoveryAwaiting.isEmpty())
		{
			return;
		}
		recoveringHistory = true;
		ensureLocalDecisionKey();
		sendCurrentHostState();
		int generation = recoveryGeneration;
		try
		{
			recoveryTimeout = executor.schedule(() -> {
				executeWhenReady(() -> {
					if (generation == recoveryGeneration && recoveringHistory)
					{
						finishHistoryRecovery();
					}
				});
			}, 3L, TimeUnit.SECONDS);
		}
		catch (RejectedExecutionException e)
		{
			clearHistoryRecovery();
			log.debug("Community Lootshare recovery scheduling was rejected", e);
		}
		requestPartySync();
	}

	private void finishHistoryRecovery()
	{
		clearHistoryRecovery();
		PartyMember local = partyService.getLocalMember();
		if (!partyService.isInParty() || local == null || local.getMemberId() != engine.getActiveHostMemberId()
			|| partyService.getPartyId() != engine.getActivePartyId())
		{
			return;
		}
		boolean decisionsApplied = resolvePendingProposalsAsHost();
		if (decisionsApplied)
		{
			queueCurrentStateForSave();
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
		notifyStateChanged();
	}

	private void clearHistoryRecovery()
	{
		synchronized (lifecycleLock)
		{
			if (recoveryTimeout != null)
			{
				recoveryTimeout.cancel(false);
				recoveryTimeout = null;
			}
			recoveryGeneration++;
			recoveryAwaiting.clear();
			recoveringHistory = false;
		}
	}

	private String displayName(PartyMember member, LootProposal proposal)
	{
		if (proposal != null && member.getMemberId() == proposal.getOwnerMemberId())
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
		if (deterministicSuccessor || engine.getActiveHostRevision() > 0L)
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
		ensureLocalDecisionKey();
		if (deterministicSuccessor)
		{
			beginHistoryRecovery();
		}
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
		boolean keyAdded = ensureLocalDecisionKey();
		if (engine.getActiveHostSettings().map(configuredSettings::equals).orElse(false))
		{
			if (keyAdded)
			{
				queueCurrentStateForSave();
				sendCurrentHostState();
			}
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
		rememberActiveHostState();
		engine.getActiveHostSettings().ifPresent(settings -> partyService.send(
			new HostMessage(hostMemberId, settings, revision,
				engine.getActiveMemberApprovalStatuses(), engine.getActiveDecisionKeys(), engine.getActiveDecisionAuthorizations())));
	}

	private void rememberActiveHostState()
	{
		long host = engine.getActiveHostMemberId();
		if (host > 0L && Objects.equals(activeFile, storage.resolveCurrentFile()))
		{
			engine.getActiveHostSettings().ifPresent(settings -> storage.rememberHostState(engine.getActivePartyId(),
				new HostMessage(host, settings, engine.getActiveHostRevision(), engine.getActiveMemberApprovalStatuses(),
					engine.getActiveDecisionKeys(), engine.getActiveDecisionAuthorizations())));
		}
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
			config.lootValueBasis() == null
				? com.communitylootshare.domain.LootValueBasis.GRAND_EXCHANGE
				: config.lootValueBasis(),
			config.captureNpcLoot(), config.captureEventLoot(), config.capturePlayerLoot(),
			config.capturePickpocketLoot(), config.captureUnknownLoot(), config.includeLoggedOutMembers(),
			config.allowMemberManualGp());
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

	private boolean isDeterministicSuccessor(long memberId)
	{
		return partyService.getMemberById(memberId) != null
			&& partyService.getMembers().stream().allMatch(member -> member.getMemberId() >= memberId);
	}

	private void recordLocalProposal(LootProposal proposal)
	{
		recordLocalProposal(proposal, false);
	}

	private void recordLocalProposal(LootProposal proposal, boolean manualGp)
	{
		if (!partyService.isInParty() || partyService.getPartyId() != proposal.getPartyId())
		{
			return;
		}
		if (engine.addProposal(proposal) == MutationResult.APPLIED)
		{
			partyService.send(new ProposalMessage(proposal, manualGp));
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
			replayingDeferredActions = true;
			try
			{
				if (partyService.isInParty())
				{
					MutationResult resultCode = engine.enterParty(partyService.getPartyId(), UUID.randomUUID().toString(), Instant.now());
					if (resultCode == MutationResult.APPLIED)
					{
						queueCurrentStateForSave();
					}
					storage.recallHostState(partyService.getPartyId()).ifPresent(saved -> {
						if (saved.getRevision() < engine.getActiveHostRevision()
							|| !engine.canTrustDecisionKeys(saved.getDecisionKeys())
							|| !engine.canTrustDecisionAuthorizations(saved.getDecisionAuthorizations()))
						{
							return;
						}
						long actor = engine.getActiveHostMemberId() == 0L ? saved.getHostMemberId()
							: engine.getActiveHostMemberId();
						MutationResult restored = engine.updateHostState(actor, saved.getHostMemberId(), saved.getSettings(),
							saved.getRevision(), saved.getApprovalStatuses());
						if (restored == MutationResult.APPLIED || restored == MutationResult.DUPLICATE)
						{
							engine.trustDecisionKeys(saved.getDecisionKeys());
							engine.trustDecisionAuthorizations(saved.getDecisionAuthorizations());
						}
					});
					boolean persistedAuthority = engine.getActiveHostRevision() > 0L;
					if (partyService.getLocalMember() != null && vacateMissingHost())
					{
						queueCurrentStateForSave();
					}
					claimHostIfEligible(false);
					PartyMember local = partyService.getLocalMember();
					if (local != null && engine.getActiveHostMemberId() == local.getMemberId())
					{
						// A restart in an existing Party can trust its own persisted authority.
						hostSettingsSynchronized = true;
						if (persistedAuthority)
						{
							beginHistoryRecovery();
						}
					}
					publishLocalHostConfigurationIfChanged();
				}
				// Keep initialization and replay ordered against websocket callbacks. Reentrant
				// callbacks also join this queue rather than overtaking its earlier messages.
				while (started && generation == loadGeneration && !deferredActions.isEmpty())
				{
					deferredActions.removeFirst().run();
				}
			}
			finally
			{
				replayingDeferredActions = false;
			}
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
			if (!ready || replayingDeferredActions)
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
