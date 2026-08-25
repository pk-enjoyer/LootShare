/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.ui.lootshare.PanelState.BalanceRow;
import com.communitylootshare.ui.lootshare.PanelState.HostedSettings;
import com.communitylootshare.ui.lootshare.PanelState.LootItem;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import com.communitylootshare.ui.lootshare.PanelState.SettlementState;
import com.communitylootshare.ui.lootshare.PanelState.TransferRow;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
	private static final String NAME_UNAVAILABLE = "Name unavailable";

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final ItemManager itemManager;
	private final LootshareController lootshareController;
	private final PartyConfig partyConfig;
	private final Object viewLock = new Object();

	private boolean started;
	private long viewGeneration;
	private Panel panel;
	private PanelState latestState;

	public UiController(Client client, ClientThread clientThread, PartyService partyService,
	                    ItemManager itemManager,
	                    LootshareController lootshareController)
	{
		this(client, clientThread, partyService, itemManager, lootshareController, (PartyConfig) null);
	}

	@Inject
	public UiController(Client client, ClientThread clientThread, PartyService partyService,
	                    ItemManager itemManager,
	                    LootshareController lootshareController,
	                    ConfigManager configManager)
	{
		this(client, clientThread, partyService, itemManager, lootshareController,
			configManager.getConfig(PartyConfig.class));
	}

	UiController(Client client, ClientThread clientThread, PartyService partyService,
	             ItemManager itemManager,
	             LootshareController lootshareController,
	             PartyConfig partyConfig)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.itemManager = itemManager;
		this.lootshareController = lootshareController;
		this.partyConfig = partyConfig;
	}

	private static String resolveDisplayName(PartyMember member, List<LootProposal> proposals,
	                                         String localPlayerName, String knownMemberName)
	{
		if (knownMemberName != null)
		{
			return knownMemberName;
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
		return NAME_UNAVAILABLE;
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
		synchronized (viewLock)
		{
			started = false;
			panel = null;
			latestState = null;
			viewGeneration++;
		}
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
			});
		});
	}

	@Override
	public void createParty()
	{
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
		if (normalized == null || !isStarted())
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
		if (previousParty == null || !isStarted())
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
		clientThread.invokeLater(() -> {
			if (isStarted() && partyService.isInParty())
			{
				partyService.changeParty(null);
			}
		});
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
		boolean ready = lootshareController.isReady();
		boolean inParty = partyService.isInParty();
		boolean previousPartyAvailable = previousPartyPassphrase() != null;
		if (!inParty)
		{
			return new PanelState(ready, false, null, Collections.emptyList(), previousPartyAvailable);
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
			Optional<String> knownName = lootshareController.getKnownMemberName(member);
			String displayName = resolveDisplayName(member, memberProposals, local ? localPlayerName : null,
				knownName == null ? null : knownName.orElse(null));
			members.add(buildMember(member, displayName, local, member.getMemberId() == hostMemberId,
				member.isLoggedIn() || (local && localPlayerName != null), memberProposals, itemNames));
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
			ready, true, partyService.getPartyPassphrase(), members, previousPartyAvailable, hostedSettings,
			activeSession == null ? null : activeSession.getSessionId(),
			buildSettlementState(activeSession, calculation));
	}

	private MemberLoot buildMember(PartyMember member, String displayName, boolean local, boolean host,
	                               boolean loggedIn,
	                               List<LootProposal> proposals, Map<Integer, String> itemNames)
	{
		return buildMember(member.getMemberId(), displayName, local, host, loggedIn, member.getAvatar(),
			proposals, itemNames);
	}

	private MemberLoot buildMember(long memberId, String displayName, boolean local, boolean host,
	                               boolean loggedIn,
	                               BufferedImage avatar, List<LootProposal> proposals,
	                               Map<Integer, String> itemNames)
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
			proposalCount, pendingProposalCount, totalValue, items);
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
		return new SettlementState(calculation.getTotalAcceptedValue(), balances, transfers);
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
