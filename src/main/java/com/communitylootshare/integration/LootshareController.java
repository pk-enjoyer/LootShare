/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.integration;

import com.communitylootshare.LootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.capture.LootCaptureService.CaptureOrigin;
import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.HostPeriod;
import com.communitylootshare.domain.HostHistoryEvent;
import com.communitylootshare.domain.HistoryCommitment;
import com.communitylootshare.domain.SettlementSummary;
import com.communitylootshare.party.HistoryMessage;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
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

	private ChatMessageManager chatMessageManager;
	private final Map<String, HistoryAssembly> historyAssemblies = new LinkedHashMap<>();
	private final Map<String, BufferedHistory> bufferedHistory = new LinkedHashMap<>();
	private final Set<String> emittedNotices = new java.util.LinkedHashSet<>();
	private boolean liveHostDeparture;
	private boolean suppressHostClaimUntilSync;
	private volatile String persistenceNotice;

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

	@Inject
	public void setChatMessageManager(ChatMessageManager manager) { chatMessageManager = manager; }
	private void notice(String id, String text)
	{
		if (!emittedNotices.add(id)) { return; }
		while (emittedNotices.size() > 512) { emittedNotices.remove(emittedNotices.iterator().next()); }
		if (chatMessageManager != null)
		{
			chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage("[Community Lootshare] " + text).build());
		}
	}
	public String getPersistenceNotice() { return persistenceNotice; }
	public long getActiveHostRevision() { return isReady() ? engine.getActiveHostRevision() : 0L; }
	public boolean canApplyMySettings()
	{
		PartyMember local = partyService.getLocalMember();
		return local != null && local.getMemberId() == getActiveHostMemberId() && !recoveringHistory
			&& getActiveHostSettings().map(settings -> !settings.equals(configuredSettings())).orElse(false);
	}

	public MutationResult applyMySettings(long displayedHostMemberId, long displayedRevision)
	{
		return applyMySettings(engine.getActivePartyId(), displayedHostMemberId, displayedRevision);
	}
	public MutationResult applyMySettings(long displayedPartyId, long displayedHostMemberId, long displayedRevision)
	{
		if (!isReady() || !partyService.isInParty() || engine.getActivePartyId() != partyService.getPartyId())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || local.getMemberId() != engine.getActiveHostMemberId()) { return MutationResult.NOT_HOST; }
		if (!hostSettingsSynchronized || recoveringHistory || displayedHostMemberId != local.getMemberId()
			|| displayedPartyId != engine.getActivePartyId() || displayedRevision != engine.getActiveHostRevision()) { return MutationResult.CONFLICT; }
		LootshareSession beforeSession = engine.getActiveSession().orElse(null);
		LootshareSettings next = configuredSettings();
		if (beforeSession == null) { return MutationResult.NO_ACTIVE_SESSION; }
		if (beforeSession.getHostSettings().equals(next)) { return MutationResult.DUPLICATE; }
		long revision = nextHostRevision();
		if (revision == 0) { return MutationResult.LIMIT_REACHED; }
		ensureHostPeriod();
		beforeSession = engine.getActiveSession().get();
		LootshareSession afterSession = beforeSession.snapshot();
		afterSession.setHostState(local.getMemberId(), next, revision);
		HostHistoryEvent event = signHistory(new HostHistoryEvent("settings:" + UUID.randomUUID(),
			engine.getActivePartyId(), revision, beforeSession.getHostPeriod(), periodTime(beforeSession.getHostPeriod()), HostHistoryEvent.Kind.SETTINGS_APPLIED,
			HostHistoryEvent.Reason.SETTINGS, beforeSession.getHostSettings(), next,
			SettlementSummary.from(calculator.calculate(beforeSession)), SettlementSummary.from(calculator.calculate(afterSession)), true, null));
		if (event == null) { return MutationResult.INVALID; }
		MutationResult result = engine.applySettingsWithHistory(local.getMemberId(), displayedRevision, next, event);
		if (result == MutationResult.APPLIED)
		{
			sendCurrentHostState(); sendHistory(event, 0L, true);
			settingsNotice(event);
			queueCurrentStateForSave(); notifyStateChanged();
		}
		return result;
	}

	public void onHistoryMessage(HistoryMessage message)
	{
		if (message == null || !partyService.isInParty() || partyService.getMemberById(message.getMemberId()) == null
			|| !message.isValid(partyService.getPartyId())) { return; }
		executeWhenReady(() -> {
			PartyMember local = partyService.getLocalMember();
			if (local == null || engine.getActivePartyId() != partyService.getPartyId()
				|| (message.getTargetMemberId() != 0L && message.getTargetMemberId() != local.getMemberId())) { return; }
			String key = message.getMemberId() + ":" + message.getEventId();
			HistoryAssembly assembly = historyAssemblies.get(key);
			if (assembly == null)
			{
				if (historyAssemblies.size() >= 8) { historyAssemblies.remove(historyAssemblies.keySet().iterator().next()); }
				assembly = new HistoryAssembly(message.getCount(), message.isLive(), message.getTargetMemberId(),
					hostSettingsSynchronized && message.getMemberId() == engine.getActiveHostMemberId());
				historyAssemblies.put(key, assembly);
			}
			if (!assembly.accept(message)) { historyAssemblies.remove(key); return; }
			if (!assembly.isComplete()) { return; }
			historyAssemblies.remove(key);
			HistoryAssembly completed = assembly;
			storage.decodeHistory(assembly.json()).ifPresent(event -> {
				if (!event.getEventId().equals(message.getEventId()) || event.getPartyId() != engine.getActivePartyId()
					|| !event.verifies() || event.getReceipt() == null) { return; }
				if (bufferedHistory.size() >= 16) { bufferedHistory.remove(bufferedHistory.keySet().iterator().next()); }
				bufferedHistory.put(key, new BufferedHistory(event, completed.live && completed.authenticatedLive));
				drainBufferedHistory();
			});
		});
	}

	private void drainBufferedHistory()
	{
		java.util.Iterator<BufferedHistory> iterator = bufferedHistory.values().iterator();
		boolean changed = false;
		List<HostHistoryEvent> imported = new ArrayList<>();
		while (iterator.hasNext())
		{
			BufferedHistory buffered = iterator.next();
			MutationResult result = engine.importHistoryEvent(buffered.event);
			if (result == MutationResult.APPLIED || result == MutationResult.DUPLICATE)
			{
				iterator.remove();
				if (result == MutationResult.APPLIED)
				{
					changed = true;
					imported.add(buffered.event);
					if (buffered.live && buffered.event.getKind() == HostHistoryEvent.Kind.SETTINGS_APPLIED) { settingsNotice(buffered.event); }
				}
			}
		}
		PartyMember local = partyService.getLocalMember();
		if (local != null && local.getMemberId() == engine.getActiveHostMemberId())
		{
			for (HostHistoryEvent event : imported) { sendHistory(event, 0L, false); }
		}
		if (changed) { queueCurrentStateForSave(); notifyStateChanged(); }
	}

	private void settingsNotice(HostHistoryEvent event)
	{
		LootshareSettings old = event.getOldSettings(), next = event.getNewSettings();
		List<String> changes = new ArrayList<>();
		if (old.getMinimumSharedLootValue() != next.getMinimumSharedLootValue())
		{
			changes.add(String.format(java.util.Locale.US, "minimum split %,d → %,d gp", old.getMinimumSharedLootValue(), next.getMinimumSharedLootValue()));
		}
		if (old.getLootValueBasis() != next.getLootValueBasis()) { changes.add("new loot value " + next.getLootValueBasis().getDisplayName()); }
		settingChange(changes, "NPC loot", old.isCaptureNpcLoot(), next.isCaptureNpcLoot());
		settingChange(changes, "activity loot", old.isCaptureEventLoot(), next.isCaptureEventLoot());
		settingChange(changes, "player loot", old.isCapturePlayerLoot(), next.isCapturePlayerLoot());
		settingChange(changes, "pickpocket loot", old.isCapturePickpocketLoot(), next.isCapturePickpocketLoot());
		settingChange(changes, "other loot", old.isCaptureUnknownLoot(), next.isCaptureUnknownLoot());
		settingChange(changes, "logged-out members", old.isIncludeLoggedOutMembers(), next.isIncludeLoggedOutMembers());
		settingChange(changes, "member manual GP", old.isAllowMemberManualGp(), next.isAllowMemberManualGp());
		notice("settings:" + event.getPartyId() + ":" + event.getEventId(), safeName(event.getPeriod().getHostDisplayName())
			+ " applied new party settings: " + String.join(", ", changes) + ". "
			+ (old.getMinimumSharedLootValue() != next.getMinimumSharedLootValue() ? "Running balances recalculated; saved history unchanged." : "Saved history unchanged."));
	}
	private static void settingChange(List<String> changes, String name, boolean old, boolean next)
	{
		if (old != next) { changes.add(name + (next ? " enabled" : " disabled")); }
	}
	private static String safeName(String name)
	{
		return Text.removeTags(Text.sanitize(name)).replaceAll("[\\p{Cntrl}<>]", "").trim();
	}
	private static Instant now() { return Instant.ofEpochMilli(Instant.now().toEpochMilli()); }
	private static Instant periodTime(HostPeriod period)
	{
		Instant at = now();
		return at.isBefore(period.getStartedAt()) ? period.getStartedAt() : at;
	}
	private HostHistoryEvent signHistory(HostHistoryEvent event)
	{
		ensureLocalDecisionKey();
		PartyMember local = partyService.getLocalMember();
		if (decisionSigningKey == null || local == null) { return null; }
		try { return event.sign(local.getMemberId(), decisionSigningKey); }
		catch (GeneralSecurityException e) { log.debug("Unable to sign Community Lootshare history", e); return null; }
	}
	private void sendHistory(HostHistoryEvent event, long target, boolean live)
	{
		try { for (HistoryMessage message : storage.historyMessages(event, target, live)) { partyService.send(message); } }
		catch (IllegalArgumentException e) { log.debug("Community Lootshare history exceeds transport bounds", e); }
	}
	private void sendStoredHistory(long target)
	{
		engine.getActiveSession().ifPresent(session -> {
			for (HostHistoryEvent event : session.getHistoryEvents()) { sendHistory(event, target, false); }
		});
	}
	private void ensureHostPeriod()
	{
		LootshareSession session = engine.getActiveSession().orElse(null);
		PartyMember host = partyService.getMemberById(engine.getActiveHostMemberId());
		if (session != null && session.getHostPeriod() == null && host != null)
		{
			engine.setHostPeriod(new HostPeriod(UUID.randomUUID().toString(), host.getMemberId(), displayName(host, null),
				null, now(), null), true);
		}
	}
	private boolean archiveHostBoundary(boolean complete)
	{
		boolean archived = false;
		while (archiveNextHostBoundary(complete)) { archived = true; }
		return archived;
	}
	private boolean archiveNextHostBoundary(boolean complete)
	{
		LootshareSession session = engine.getActiveSession().orElse(null);
		if (session == null || session.getClosingPeriod() == null) { return false; }
		HostPeriod closing = session.getClosingPeriod();
		// Calculate with the outgoing policy even if recovery has installed the next host period.
		LootshareSession calculationSession = session.snapshot();
		calculationSession.setHostState(session.getHostMemberId(), session.getClosingSettings(), session.getHostRevision());
		SettlementSummary summary = SettlementSummary.from(calculator.calculate(calculationSession));
		HostHistoryEvent event = signHistory(new HostHistoryEvent("close:" + closing.getPeriodId(), session.getPartyId(),
			session.getHostRevision(), closing, closing.getEndedAt(), HostHistoryEvent.Kind.PERIOD_CLOSED,
			session.getClosingReason(), session.getClosingSettings(), session.getClosingSettings(), summary, summary, complete, null));
		if (event == null) { return false; }
		boolean changed = engine.commitHistoryEvent(event);
		if (changed) { sendCurrentHostState(); sendHistory(event, 0L, true); queueCurrentStateForSave(); }
		return changed;
	}
	private boolean recoveredAllCommittedRecords()
	{
		LootshareSession session = engine.getActiveSession().orElse(null);
		if (session == null) { return false; }
		Set<String> present = new HashSet<>();
		for (LootProposal proposal : engine.getProposalsForActiveParty())
		{
			if (proposal.getStatus() != LootProposalStatus.PENDING) { present.add(LootDecisionReceipt.decisionId(proposal)); }
		}
		Set<String> history = new HashSet<>();
		for (HostHistoryEvent event : session.getHistoryEvents()) { history.add(event.getEventId()); }
		return present.containsAll(session.getDecisionAuthorizations()) && history.containsAll(session.getHistoryCommitments().keySet());
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
			historyAssemblies.clear(); bufferedHistory.clear(); liveHostDeparture = false;
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
		historyAssemblies.clear(); bufferedHistory.clear(); emittedNotices.clear(); persistenceNotice = null; suppressHostClaimUntilSync = false;
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
			HostPeriod incomingPeriod = hostState.getHostPeriod();
			if (incomingPeriod == null && activeHostMemberId == hostState.getHostMemberId())
			{
				incomingPeriod = engine.getActiveSession().map(LootshareSession::getHostPeriod).orElse(null);
			}
			MutationResult result = engine.updateHostSnapshot(authorizedMemberId, hostState.getHostMemberId(),
				hostState.getSettings(), hostState.getRevision(), hostState.getApprovalStatuses(), incomingPeriod,
				hostState.getHistoryCommitments(), hostState.isEarlierHistoryOmitted(), hostState.isEarlierHostChainUnknown());
			if (result == MutationResult.APPLIED || result == MutationResult.DUPLICATE)
			{
				hostSettingsSynchronized = true;
				suppressHostClaimUntilSync = false;
				boolean keysChanged = engine.trustDecisionKeys(hostState.getDecisionKeys());
				boolean authorizationsChanged = engine.trustDecisionAuthorizations(hostState.getDecisionAuthorizations());
				rememberActiveHostState();
				drainBufferedHistory();
				if (result == MutationResult.APPLIED && activeHostMemberId != hostState.getHostMemberId()
					&& (wasSynchronized || liveHostDeparture))
				{
					notice("takeover:" + engine.getActivePartyId() + ":" + hostState.getRevision(), displayName(partyService.getMemberById(hostState.getHostMemberId()), null)
						+ " is now host. Previous party settings were inherited.");
					liveHostDeparture = false;
				}
				PartyMember local = partyService.getLocalMember();
				boolean becomingHost = local != null && local.getMemberId() == hostState.getHostMemberId()
					&& activeHostMemberId != local.getMemberId();
				if (becomingHost)
				{
					ensureHostPeriod();
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
				if (engine.getActiveMemberApprovalStatus(event.getMemberId()) == MemberApprovalStatus.PENDING)
				{
					if (decisionsApplied) { queueCurrentStateForSave(); notifyStateChanged(); }
					return;
				}
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
			markDepartedHost();
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
		executeWhenReady(this::notifyStateChanged);
	}

	public void onMinimumSharedLootValueChanged()
	{
		onLocalConfigurationChanged();
	}

	public void onPartyChanged(PartyChanged event)
	{
		clearHistoryRecovery();
		historyAssemblies.clear(); bufferedHistory.clear(); liveHostDeparture = false;
		captureService.resetDeduplication();
		ignoreServerNpcLootTick = Integer.MIN_VALUE;
		hostSettingsSynchronized = false;
		Long partyId = event == null ? null : event.getPartyId();
		executeWhenReady(() -> {
			if (partyId == null || partyId != engine.getActivePartyId())
			{
				LootshareSession leaving = engine.getActiveSession().orElse(null);
				if (leaving != null && leaving.getHostPeriod() != null && decisionSigningKey != null
					&& engine.getActiveDecisionKeys().containsKey(LootDecisionReceipt.keyId(
						LootDecisionReceipt.publicKey(decisionSigningKey), leaving.getHostMemberId())))
				{
					// Remaining peers author the departure checkpoint after recovery. Keep the boundary locally for rejoin.
					engine.markHostBoundary(now(), HostHistoryEvent.Reason.DEPARTURE);
					engine.vacateHost(leaving.getHostMemberId());
					suppressHostClaimUntilSync = true;
				}
			}
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
				engine.getActiveSession().ifPresent(session -> partyService.send(new HostMessage(session)));
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
				sendStoredHistory(requester.getMemberId());
				partyService.send(RecoveryMessage.completed(requester.getMemberId()));
			}
			return;
		}

		sendCurrentHostState();
		sendStoredHistory(requester.getMemberId());
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

		if (!hostSettingsSynchronized || recoveringHistory) { return MutationResult.CONFLICT; }
		resolvePendingProposalsAsHost();
		long revision = nextHostRevision();
		if (revision == 0L)
		{
			return MutationResult.LIMIT_REACHED;
		}
		ensureHostPeriod();
		LootshareSession outgoingSession = engine.getActiveSession().get();
		HostPeriod predecessor = outgoingSession.getHostPeriod();
		Instant boundary = periodTime(predecessor);
		SettlementSummary summary = SettlementSummary.from(calculator.calculate(outgoingSession));
		HostHistoryEvent checkpoint = signHistory(new HostHistoryEvent("close:" + predecessor.getPeriodId(), outgoingSession.getPartyId(),
			revision, predecessor.end(boundary), boundary, HostHistoryEvent.Kind.PERIOD_CLOSED, HostHistoryEvent.Reason.TRANSFER,
			outgoingSession.getHostSettings(), outgoingSession.getHostSettings(), summary, summary, recoveredAllCommittedRecords(), null));
		if (checkpoint == null) { return MutationResult.INVALID; }
		HostPeriod nextPeriod = new HostPeriod(UUID.randomUUID().toString(), nextHostMemberId, displayName(nextHost, null),
			predecessor.getPeriodId(), boundary, null);
		MutationResult result = engine.transferHostWithHistory(local.getMemberId(), nextHostMemberId,
			outgoingSession.getHostRevision(), nextPeriod, checkpoint);
		if (result == MutationResult.APPLIED)
		{
			clearHistoryRecovery();
			hostSettingsSynchronized = true;
			rememberActiveHostState();
			queueCurrentStateForSave();
			engine.getActiveSession().ifPresent(session -> partyService.send(new HostMessage(session)));
			sendHistory(checkpoint, 0L, true);
			notice("takeover:" + engine.getActivePartyId() + ":" + revision, displayName(nextHost, null)
				+ " is now host. Previous party settings were inherited.");
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
			archiveHostBoundary(recoveredAllCommittedRecords());
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
			finishHistoryRecovery();
			log.debug("Community Lootshare recovery scheduling was rejected", e);
		}
		requestPartySync();
	}

	private void finishHistoryRecovery()
	{
		boolean complete = recoveryAwaiting.isEmpty() && recoveredAllCommittedRecords();
		clearHistoryRecovery();
		PartyMember local = partyService.getLocalMember();
		if (!partyService.isInParty() || local == null || local.getMemberId() != engine.getActiveHostMemberId()
			|| partyService.getPartyId() != engine.getActivePartyId())
		{
			return;
		}
		boolean archived = archiveHostBoundary(complete);
		boolean decisionsApplied = resolvePendingProposalsAsHost();
		if (decisionsApplied || archived)
		{
			queueCurrentStateForSave();
		}
		sendCurrentHostState();
		sendStoredHistory(0L);
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
		String sanitized = safeName(name);
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

		if (suppressHostClaimUntilSync && engine.getActiveHostRevision() > 0L && members.size() > 1) { return false; }
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
		LootshareSession inherited = engine.getActiveSession().orElse(null);
		MutationResult result = engine.updateHostState(local.getMemberId(), local.getMemberId(),
			engine.getActiveHostRevision() == 0L ? configuredSettings() : inherited.getHostSettings(), revision);
		if (result != MutationResult.APPLIED)
		{
			return false;
		}
		hostSettingsSynchronized = true;
		suppressHostClaimUntilSync = false;
		HostPeriod outgoing = inherited == null ? null : inherited.getHostPeriod();
		engine.setHostPeriod(new HostPeriod(UUID.randomUUID().toString(), local.getMemberId(), displayName(local, null),
			outgoing == null ? null : outgoing.getPeriodId(), outgoing == null ? now() : periodTime(outgoing), null), inherited != null && inherited.isEarlierHostChainUnknown() && revision > 1L);
		ensureLocalDecisionKey();
		if (liveHostDeparture)
		{
			notice("takeover:" + engine.getActivePartyId() + ":" + revision, displayName(local, null)
				+ " is now host. Previous party settings were inherited.");
			liveHostDeparture = false;
		}
		if (deterministicSuccessor || (inherited != null && inherited.getClosingPeriod() != null))
		{
			beginHistoryRecovery();
		}
		queueCurrentStateForSave();
		sendCurrentHostState();
		notifyStateChanged();
		return true;
	}

	private void markDepartedHost()
	{
		LootshareSession session = engine.getActiveSession().orElse(null);
		if (session == null || session.getHostMemberId() <= 0L) { return; }
		engine.markHostBoundary(now(), HostHistoryEvent.Reason.DEPARTURE);
		HostPeriod period = session.getHostPeriod();
		if (!replayingDeferredActions)
		{
			liveHostDeparture = true;
			String name = period == null ? "Party member " + session.getHostMemberId() : safeName(period.getHostDisplayName());
			notice("departure:" + session.getPartyId() + ":" + (period == null ? session.getHostRevision() : period.getPeriodId()), name
				+ " left the party or disconnected. Closing their session and recovering party history.");
		}
	}
	private boolean vacateMissingHost()
	{
		long hostMemberId = engine.getActiveHostMemberId();
		if (hostMemberId <= 0L || partyService.getMemberById(hostMemberId) != null) { return false; }
		markDepartedHost();
		boolean vacated = engine.vacateHost(hostMemberId) == MutationResult.APPLIED;
		if (vacated) { hostSettingsSynchronized = false; }
		return vacated;
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
		engine.getActiveSession().ifPresent(session -> partyService.send(new HostMessage(session)));
	}

	private void rememberActiveHostState()
	{
		long host = engine.getActiveHostMemberId();
		if (host > 0L && Objects.equals(activeFile, storage.resolveCurrentFile()))
		{
			engine.getActiveSession().ifPresent(session -> storage.rememberHostState(engine.getActivePartyId(), new HostMessage(session)));
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
			persistenceNotice = file != null && !persistenceWritable ? "History storage is read-only; existing files are preserved." : null;
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
					LootshareSession loadedParty = engine.getActiveSession().orElse(null);
					PartyMember loadingLocal = partyService.getLocalMember();
					suppressHostClaimUntilSync = loadedParty != null && loadingLocal != null && loadedParty.getHostMemberId() == 0L
						&& loadedParty.getClosingPeriod() != null && loadedParty.getHostPeriod() != null
						&& loadedParty.getHostPeriod().getHostMemberId() == loadingLocal.getMemberId();
					storage.recallHostState(partyService.getPartyId()).ifPresent(saved -> {
						if (suppressHostClaimUntilSync && saved.getHostMemberId() == loadingLocal.getMemberId()) { return; }
						if (saved.getRevision() < engine.getActiveHostRevision()
							|| !engine.canTrustDecisionKeys(saved.getDecisionKeys())
							|| !engine.canTrustDecisionAuthorizations(saved.getDecisionAuthorizations()))
						{
							return;
						}
						long actor = engine.getActiveHostMemberId() == 0L ? saved.getHostMemberId()
							: engine.getActiveHostMemberId();
						MutationResult restored = engine.updateHostSnapshot(actor, saved.getHostMemberId(), saved.getSettings(),
							saved.getRevision(), saved.getApprovalStatuses(), saved.getHostPeriod(), saved.getHistoryCommitments(),
							saved.isEarlierHistoryOmitted(), saved.isEarlierHostChainUnknown());
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
						ensureHostPeriod();
						if (persistedAuthority)
						{
							beginHistoryRecovery();
						}
					}
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
				boolean saved = storage.save(request.file, request.state);
				File savedFile = request.file;
				clientThread.invokeLater(() -> {
					if (isReady() && Objects.equals(activeFile, savedFile))
					{
						persistenceNotice = saved ? null : "History could not be saved. The last good file is preserved.";
						notifyStateChanged();
					}
				});
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

	private static final class BufferedHistory
	{
		private final HostHistoryEvent event;
		private final boolean live;
		private BufferedHistory(HostHistoryEvent event, boolean live) { this.event = event; this.live = live; }
	}
	private static final class HistoryAssembly
	{
		private final String[] chunks;
		private final boolean live;
		private final long target;
		private final boolean authenticatedLive;
		private int received;
		private HistoryAssembly(int count, boolean live, long target, boolean authenticatedLive)
		{
			chunks = new String[count]; this.live = live; this.target = target; this.authenticatedLive = authenticatedLive;
		}
		private boolean accept(HistoryMessage message)
		{
			if (chunks.length != message.getCount() || live != message.isLive() || target != message.getTargetMemberId()) { return false; }
			int index = message.getIndex();
			if (chunks[index] != null) { return chunks[index].equals(message.getData()); }
			chunks[index] = message.getData(); received++; return true;
		}
		private boolean isComplete() { return received == chunks.length; }
		private String json() { return String.join("", chunks); }
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
