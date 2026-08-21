/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.debug.DebugSession;
import com.communitylootshare.debug.DebugSession.LootPreset;
import com.communitylootshare.debug.DebugSession.Snapshot;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.ui.PopoutWindow;
import com.communitylootshare.ui.PopoutWindowFactory;
import com.communitylootshare.ui.SwingPopoutWindowFactory;
import com.communitylootshare.ui.lootshare.PanelState.BalanceRow;
import com.communitylootshare.ui.lootshare.PanelState.DebugState;
import com.communitylootshare.ui.lootshare.PanelState.HostedSettings;
import com.communitylootshare.ui.lootshare.PanelState.LootItem;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import com.communitylootshare.ui.lootshare.PanelState.SettlementState;
import com.communitylootshare.ui.lootshare.PanelState.TransferRow;
import com.communitylootshare.views.graph.GraphData;
import com.communitylootshare.views.graph.SessionGraphMode;
import com.communitylootshare.views.graph.SessionGraphSnapshot;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.party.PartyConfig;
import net.runelite.client.util.Text;

/**
 * Bridges RuneLite client-thread Party state to immutable EDT-rendered sidebar snapshots.
 */
@Singleton
@Slf4j
public class UiController implements PanelActions
{
	private static final String UNKNOWN_MEMBER_NAME = "<unknown>";

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final ItemManager itemManager;
	private final LootshareController lootshareController;
	private final DebugSession debugSession;
	private final PartyConfig partyConfig;
	private final PopoutWindowFactory popoutWindowFactory;
	private final Object viewLock = new Object();
	private volatile Map<LootPreset, Long> debugLootPrices = Collections.emptyMap();

	private boolean started;
	private long viewGeneration;
	private Panel panel;
	private PanelState latestState;
	private SettlementDashboard settlementDashboard;
	private PopoutWindow settlementWindow;

	public UiController(Client client, ClientThread clientThread, PartyService partyService,
	                                      ItemManager itemManager,
	                                      LootshareController lootshareController)
	{
		this(client, clientThread, partyService, itemManager, lootshareController, null,
			(PartyConfig) null, null);
	}

	public UiController(Client client, ClientThread clientThread, PartyService partyService,
	                                      ItemManager itemManager,
	                                      LootshareController lootshareController,
	                                      DebugSession debugSession)
	{
		this(client, clientThread, partyService, itemManager, lootshareController, debugSession,
			(PartyConfig) null, null);
	}

	@Inject
	public UiController(Client client, ClientThread clientThread, PartyService partyService,
	                                      ItemManager itemManager,
	                                      LootshareController lootshareController,
	                                      DebugSession debugSession,
	                                      ConfigManager configManager,
	                                      SwingPopoutWindowFactory popoutWindowFactory)
	{
		this(client, clientThread, partyService, itemManager, lootshareController, debugSession,
			configManager.getConfig(PartyConfig.class), popoutWindowFactory);
	}

	UiController(Client client, ClientThread clientThread, PartyService partyService,
	                               ItemManager itemManager,
	                               LootshareController lootshareController,
	                               DebugSession debugSession,
	                               PartyConfig partyConfig)
	{
		this(client, clientThread, partyService, itemManager, lootshareController, debugSession,
			partyConfig, null);
	}

	UiController(Client client, ClientThread clientThread, PartyService partyService,
	                               ItemManager itemManager,
	                               LootshareController lootshareController,
	                               DebugSession debugSession,
	                               PartyConfig partyConfig, PopoutWindowFactory popoutWindowFactory)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.itemManager = itemManager;
		this.lootshareController = lootshareController;
		this.debugSession = debugSession;
		this.partyConfig = partyConfig;
		this.popoutWindowFactory = popoutWindowFactory;
	}

	private static String resolveDisplayName(PartyMember member, List<LootProposal> proposals,
	                                         String localPlayerName)
	{
		String memberName = cleanName(member.getDisplayName());
		if (memberName != null && !UNKNOWN_MEMBER_NAME.equalsIgnoreCase(memberName))
		{
			return memberName;
		}
		if (localPlayerName != null)
		{
			return localPlayerName;
		}
		for (LootProposal proposal : proposals)
		{
			String recipient = cleanName(proposal.getEvent().getRecipient());
			if (recipient != null)
			{
				return recipient;
			}
		}
		return "Party member " + member.getMemberId();
	}

	private static String cleanName(String value)
	{
		if (value == null)
		{
			return null;
		}
		String cleaned = Text.removeTags(value).trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private static String normalizePassphrase(String passphrase)
	{
		if (passphrase == null)
		{
			return null;
		}
		String normalized = passphrase.trim().toLowerCase(Locale.US);
		if (normalized.isEmpty())
		{
			return null;
		}
		for (int index = 0; index < normalized.length(); index++)
		{
			char character = normalized.charAt(index);
			if (!Character.isLetter(character) && !Character.isDigit(character) && character != '-')
			{
				return null;
			}
		}
		return normalized;
	}

	private static long saturatedAdd(long left, long right)
	{
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	public void start(Panel panel)
	{
		synchronized (viewLock)
		{
			this.panel = panel;
			started = true;
			viewGeneration++;
		}
		lootshareController.setStateChangeListener(this::refresh);
		refresh();
	}

	public void stop()
	{
		lootshareController.setStateChangeListener(null);
		if (debugSession != null)
		{
			debugSession.stopSimulation();
		}
		debugLootPrices = Collections.emptyMap();
		synchronized (viewLock)
		{
			started = false;
			panel = null;
			latestState = null;
			viewGeneration++;
		}
		SwingUtilities.invokeLater(this::disposeSettlementDashboard);
	}

	public void refresh()
	{
		final Panel target;
		final long generation;
		synchronized (viewLock)
		{
			if (!started || panel == null)
			{
				return;
			}
			target = panel;
			generation = viewGeneration;
		}

		clientThread.invokeLater(() -> {
			synchronized (viewLock)
			{
				if (!started || generation != viewGeneration || panel != target)
				{
					return;
				}
			}
			PanelState state = buildState();
			SwingUtilities.invokeLater(() -> {
				synchronized (viewLock)
				{
					if (!started || generation != viewGeneration || panel != target)
					{
						return;
					}
				}
				latestState = state;
				target.render(state);
				if (settlementDashboard != null)
				{
					settlementDashboard.render(state.getSettlement());
				}
			});
		});
	}

	@Override
	public void createParty()
	{
		if (isDebugSimulationActive())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && !partyService.isInParty())
			{
				partyService.changeParty(partyService.generatePassphrase());
			}
		});
	}

	@Override
	public boolean joinParty(String passphrase)
	{
		String normalized = normalizePassphrase(passphrase);
		if (normalized == null || !isStarted() || isDebugSimulationActive())
		{
			return false;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && !partyService.isInParty())
			{
				partyService.changeParty(normalized);
			}
		});
		return true;
	}

	@Override
	public void joinPreviousParty()
	{
		String previousParty = previousPartyPassphrase();
		if (previousParty == null || !isStarted() || isDebugSimulationActive())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && !partyService.isInParty())
			{
				partyService.changeParty(previousParty);
			}
		});
	}

	@Override
	public void leaveParty()
	{
		if (isDebugSimulationActive())
		{
			stopDebugSimulation();
			return;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && partyService.isInParty())
			{
				partyService.changeParty(null);
			}
		});
	}

	@Override
	public void transferHost(long memberId)
	{
		if (!isStarted() || isDebugSimulationActive())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && partyService.isInParty())
			{
				lootshareController.transferHost(memberId);
			}
		});
	}

	@Override
	public void setMemberApproved(long memberId, boolean approved)
	{
		if (!isStarted())
		{
			return;
		}
		if (isDebugSimulationActive())
		{
			debugSession.setMemberApproved(memberId, approved);
			refresh();
			return;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && partyService.isInParty())
			{
				lootshareController.setMemberApproved(memberId, approved);
			}
		});
	}

	@Override
	public void openSettlementDashboard()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::openSettlementDashboard);
			return;
		}
		if (!isStarted() || popoutWindowFactory == null)
		{
			return;
		}
		if (settlementWindow != null && settlementWindow.isDisplayable())
		{
			settlementWindow.focus();
			return;
		}
		if (settlementWindow != null || settlementDashboard != null)
		{
			disposeSettlementDashboard();
		}

		SettlementDashboard dashboard =
			new SettlementDashboard(this::refresh);
		if (latestState != null)
		{
			dashboard.render(latestState.getSettlement());
		}
		boolean[] closedDuringOpen = {false};
		final PopoutWindow window;
		try
		{
			window = popoutWindowFactory.open("Community Lootshare — Settlement", dashboard,
				new Dimension(760, 480), new Dimension(1040, 640), () -> {
					closedDuringOpen[0] = true;
					onSettlementDashboardClosed(dashboard);
				});
		}
		catch (RuntimeException e)
		{
			dashboard.stop();
			log.debug("Unable to open the Community Lootshare settlement dashboard", e);
			return;
		}
		if (closedDuringOpen[0] || window == null)
		{
			dashboard.stop();
			return;
		}
		if (!isStarted())
		{
			dashboard.stop();
			window.dispose();
			return;
		}
		settlementDashboard = dashboard;
		settlementWindow = window;
	}

	@Override
	public void startDebugSimulation()
	{
		if (isStarted() && isDebugAvailable())
		{
			debugLootPrices = Collections.emptyMap();
			debugSession.startSimulation();
			refresh();
		}
	}

	@Override
	public void stopDebugSimulation()
	{
		if (isDebugAvailable())
		{
			debugSession.stopSimulation();
			debugLootPrices = Collections.emptyMap();
			refresh();
		}
	}

	@Override
	public void resetDebugSimulation()
	{
		if (isStarted() && isDebugSimulationActive())
		{
			debugSession.resetSimulation();
			refresh();
		}
	}

	@Override
	public boolean addDebugPlayer(String displayName)
	{
		boolean added = isStarted() && isDebugSimulationActive()
			&& debugSession.addFakePlayer(displayName).isPresent();
		if (added)
		{
			refresh();
		}
		return added;
	}

	@Override
	public boolean removeDebugPlayer(long memberId)
	{
		boolean removed = isStarted() && isDebugSimulationActive()
			&& debugSession.removeFakePlayer(memberId);
		if (removed)
		{
			refresh();
		}
		return removed;
	}

	@Override
	public boolean addDebugLoot(long ownerMemberId, long totalValue)
	{
		return addDebugLoot(ownerMemberId, LootPreset.COINS, totalValue);
	}

	@Override
	public void addManualGp(long memberId, long amount)
	{
		if (!isStarted() || isDebugSimulationActive())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (isStarted() && partyService.isInParty())
			{
				lootshareController.addManualGp(memberId, amount);
			}
		});
	}

	@Override
	public boolean addDebugLoot(long ownerMemberId, LootPreset lootPreset, long totalValue)
	{
		if (lootPreset == null)
		{
			return false;
		}
		long capturedValue = lootPreset.usesItemPrice()
			? debugLootPrices.getOrDefault(lootPreset, 0L)
			: totalValue;
		boolean added = isStarted() && isDebugSimulationActive()
			&& debugSession.addSampleProposal(ownerMemberId, lootPreset, capturedValue).isPresent();
		if (added)
		{
			refresh();
		}
		return added;
	}

	public void onPartyChanged(PartyChanged event)
	{
		if (event != null && event.getPartyId() != null && partyConfig != null)
		{
			String passphrase = normalizePassphrase(event.getPassphrase());
			if (passphrase != null)
			{
				partyConfig.setPreviousPartyId(passphrase);
			}
		}
		refresh();
	}

	PanelState buildState()
	{
		if (isDebugSimulationActive())
		{
			return buildDebugState(debugSession.snapshot());
		}
		boolean ready = lootshareController.isReady();
		boolean inParty = partyService.isInParty();
		boolean previousPartyAvailable = previousPartyPassphrase() != null;
		if (!inParty)
		{
			return new PanelState(
				ready, false, null, Collections.emptyList(), previousPartyAvailable, currentDebugState());
		}

		List<LootProposal> proposals = lootshareController.getActivePartyProposals();
		Optional<LootshareSession> activeSessionSnapshot = lootshareController.getActiveSession();
		LootshareSession activeSession = activeSessionSnapshot == null ? null : activeSessionSnapshot.orElse(null);
		LootshareCalculation calculation = lootshareController.getActiveCalculation();
		Map<Long, List<LootProposal>> proposalsByOwner = new HashMap<>();
		for (LootProposal proposal : proposals)
		{
			proposalsByOwner.computeIfAbsent(proposal.getOwnerMemberId(), ignored -> new ArrayList<>()).add(proposal);
		}

		PartyMember localMember = partyService.getLocalMember();
		long localMemberId = localMember == null ? Long.MIN_VALUE : localMember.getMemberId();
		long hostMemberId = lootshareController.getActiveHostMemberId();
		Player localPlayer = client.getLocalPlayer();
		String localPlayerName = localPlayer == null ? null : cleanName(localPlayer.getName());
		Map<Integer, String> itemNames = new HashMap<>();
		List<MemberLoot> members = new ArrayList<>();
		for (PartyMember member : new ArrayList<>(partyService.getMembers()))
		{
			List<LootProposal> memberProposals = proposalsByOwner.getOrDefault(
				member.getMemberId(), Collections.emptyList());
			boolean local = member.getMemberId() == localMemberId;
			String displayName = resolveDisplayName(member, memberProposals, local ? localPlayerName : null);
			MemberApprovalStatus approvalStatus = lootshareController.getMemberApprovalStatus(member.getMemberId());
			if (approvalStatus == null)
			{
				approvalStatus = member.getMemberId() == hostMemberId
					? MemberApprovalStatus.APPROVED
					: MemberApprovalStatus.PENDING;
			}
			members.add(buildMember(member, displayName, local, member.getMemberId() == hostMemberId,
				member.isLoggedIn() || (local && localPlayerName != null), memberProposals, itemNames,
				approvalStatus));
		}

		members.sort((left, right) -> {
			int byLocal = Boolean.compare(right.isLocal(), left.isLocal());
			if (byLocal != 0)
			{
				return byLocal;
			}
			int byName = String.CASE_INSENSITIVE_ORDER.compare(left.getDisplayName(), right.getDisplayName());
			return byName != 0 ? byName : Long.compare(left.getMemberId(), right.getMemberId());
		});
		HostedSettings hostedSettings = HostedSettings.waiting();
		LootshareSettings settings = lootshareController.getActiveHostSettings().orElse(null);
		if (hostMemberId > 0L && settings != null)
		{
			for (MemberLoot member : members)
			{
				if (member.getMemberId() == hostMemberId)
				{
					hostedSettings = HostedSettings.available(hostMemberId, member.getDisplayName(),
						member.isLocal(), settings);
					break;
				}
			}
		}
		return new PanelState(
			ready, true, partyService.getPartyPassphrase(), members, previousPartyAvailable,
			currentDebugState(), hostedSettings,
			activeSession == null ? null : activeSession.getSessionId(),
			buildSettlementState(activeSession, calculation));
	}

	private MemberLoot buildMember(PartyMember member, String displayName, boolean local, boolean host,
	                               boolean loggedIn,
	                               List<LootProposal> proposals, Map<Integer, String> itemNames,
	                               MemberApprovalStatus approvalStatus)
	{
		return buildMember(member.getMemberId(), displayName, local, host, loggedIn, member.getAvatar(),
			proposals, itemNames, approvalStatus);
	}

	private MemberLoot buildMember(long memberId, String displayName, boolean local, boolean host,
	                               boolean loggedIn,
	                               BufferedImage avatar, List<LootProposal> proposals,
	                               Map<Integer, String> itemNames, MemberApprovalStatus approvalStatus)
	{
		Map<Integer, ItemAggregate> itemAggregates = new LinkedHashMap<>();
		int proposalCount = 0;
		int pendingProposalCount = 0;
		long totalValue = 0L;
		for (LootProposal proposal : proposals)
		{
			if (proposal.getStatus() != LootProposalStatus.ACCEPTED)
			{
				continue;
			}
			proposalCount++;
			totalValue = saturatedAdd(totalValue, proposal.getEvent().getTotal());
			for (SharedLootItem item : proposal.getEvent().getItems())
			{
				ItemAggregate aggregate = itemAggregates.computeIfAbsent(
					item.getPricingId(), ItemAggregate::new);
				aggregate.quantity = saturatedAdd(aggregate.quantity, item.getQuantity());
				aggregate.totalValue = saturatedAdd(aggregate.totalValue, item.getLineTotal());
			}
		}

		List<LootItem> items = new ArrayList<>(itemAggregates.size());
		for (ItemAggregate aggregate : itemAggregates.values())
		{
			String itemName = itemNames.computeIfAbsent(aggregate.itemId, this::resolveItemName);
			items.add(new LootItem(aggregate.itemId, itemName, aggregate.quantity, aggregate.totalValue));
		}
		items.sort(Comparator.comparingLong(LootItem::getTotalValue).reversed()
			.thenComparing(LootItem::getName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(LootItem::getItemId));

		return new MemberLoot(memberId, displayName, local, host, loggedIn, avatar,
			proposalCount, pendingProposalCount, totalValue, items, approvalStatus);
	}

	private PanelState buildDebugState(Snapshot snapshot)
	{
		Map<LootPreset, Long> lootPresetPrices = resolveDebugLootPrices();
		debugLootPrices = lootPresetPrices;
		Map<Long, List<LootProposal>> proposalsByOwner = new HashMap<>();
		for (LootProposal proposal : snapshot.getProposals())
		{
			proposalsByOwner.computeIfAbsent(proposal.getOwnerMemberId(), ignored -> new ArrayList<>())
				.add(proposal);
		}
		Map<Integer, String> itemNames = new HashMap<>();
		List<MemberLoot> members = new ArrayList<>();
		for (LootshareParticipant participant : snapshot.getParticipants())
		{
			long memberId = participant.getMemberId();
			members.add(buildMember(memberId, participant.getDisplayName(),
				memberId == snapshot.getOwnerMemberId(), memberId == snapshot.getOwnerMemberId(), true, null,
				proposalsByOwner.getOrDefault(memberId, Collections.emptyList()), itemNames,
				snapshot.getSession().getMemberApprovalStatus(memberId)));
		}
		members.sort((left, right) -> {
			int byLocal = Boolean.compare(right.isLocal(), left.isLocal());
			if (byLocal != 0)
			{
				return byLocal;
			}
			int byName = String.CASE_INSENSITIVE_ORDER.compare(left.getDisplayName(), right.getDisplayName());
			return byName != 0 ? byName : Long.compare(left.getMemberId(), right.getMemberId());
		});
		return new PanelState(true, true, null, members, false,
			new DebugState(true, true, snapshot.getOwnerMemberId(), lootPresetPrices), HostedSettings.waiting(),
			snapshot.getSession().getSessionId(),
			buildSettlementState(snapshot.getSession(), snapshot.getCalculation()));
	}

	private SettlementState buildSettlementState(LootshareSession session, LootshareCalculation calculation)
	{
		if (session == null || calculation == null)
		{
			return SettlementState.empty();
		}
		List<BalanceRow> balances = new ArrayList<>();
		for (LootshareCalculation.Balance balance : calculation.getBalances())
		{
			balances.add(new BalanceRow(balance.getMemberId(), balance.getDisplayName(),
				balance.getReceivedValue(), balance.getEntitledValue(), balance.getNetValue()));
		}
		List<TransferRow> transfers = new ArrayList<>();
		for (LootshareCalculation.Transfer transfer : calculation.getTransfers())
		{
			transfers.add(new TransferRow(transfer.getFromDisplayName(), transfer.getToDisplayName(),
				transfer.getAmount()));
		}
		EnumMap<SessionGraphMode, SessionGraphSnapshot> graphs = new EnumMap<>(SessionGraphMode.class);
		Instant now = Instant.now();
		for (SessionGraphMode mode : SessionGraphMode.values())
		{
			graphs.put(mode, GraphData.build(session, calculation, mode, now));
		}
		return new SettlementState(calculation.getTotalAcceptedValue(), balances, transfers, graphs);
	}

	private Map<LootPreset, Long> resolveDebugLootPrices()
	{
		EnumMap<LootPreset, Long> prices = new EnumMap<>(LootPreset.class);
		for (LootPreset lootPreset : LootPreset.values())
		{
			if (lootPreset.usesItemPrice())
			{
				prices.put(lootPreset, resolveDebugLootPrice(lootPreset));
			}
		}
		return Collections.unmodifiableMap(prices);
	}

	private long resolveDebugLootPrice(LootPreset lootPreset)
	{
		try
		{
			return Math.max(0L, itemManager.getItemPrice(lootPreset.getItemId()));
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to resolve Community Lootshare debug price for item {}",
				lootPreset.getItemId(), e);
			return 0L;
		}
	}

	private String resolveItemName(int itemId)
	{
		try
		{
			ItemComposition item = itemManager.getItemComposition(itemId);
			String name = item == null ? null : cleanName(item.getName());
			return name == null ? "Item " + itemId : name;
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to resolve Community Lootshare item name for {}", itemId, e);
			return "Item " + itemId;
		}
	}

	private String previousPartyPassphrase()
	{
		return partyConfig == null ? null : normalizePassphrase(partyConfig.previousPartyId());
	}

	private boolean isStarted()
	{
		synchronized (viewLock)
		{
			return started;
		}
	}

	private boolean isDebugAvailable()
	{
		return debugSession != null && debugSession.isAvailable();
	}

	private boolean isDebugSimulationActive()
	{
		return isDebugAvailable() && debugSession.isActive();
	}

	private DebugState currentDebugState()
	{
		return isDebugAvailable()
			? new DebugState(true, false, 0L)
			: DebugState.unavailable();
	}

	private void onSettlementDashboardClosed(SettlementDashboard dashboard)
	{
		dashboard.stop();
		if (settlementDashboard == dashboard)
		{
			settlementDashboard = null;
			settlementWindow = null;
		}
	}

	private void disposeSettlementDashboard()
	{
		SettlementDashboard dashboard = settlementDashboard;
		PopoutWindow window = settlementWindow;
		settlementDashboard = null;
		settlementWindow = null;
		if (dashboard != null)
		{
			dashboard.stop();
		}
		if (window != null && window.isDisplayable())
		{
			window.dispose();
		}
	}

	private static final class ItemAggregate
	{
		private final int itemId;
		private long quantity;
		private long totalValue;

		private ItemAggregate(int itemId)
		{
			this.itemId = itemId;
		}
	}
}
