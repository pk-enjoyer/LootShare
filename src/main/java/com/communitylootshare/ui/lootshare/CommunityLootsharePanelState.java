/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.debug.CommunityLootshareDebugSession.LootPreset;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.views.graph.SessionGraphMode;
import com.communitylootshare.views.graph.SessionGraphSnapshot;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable client-thread snapshot rendered by the Swing sidebar.
 */
public final class CommunityLootsharePanelState
{
	private final boolean ready;
	private final boolean inParty;
	private final String partyPassphrase;
	private final List<MemberLoot> members;
	private final boolean previousPartyAvailable;
	private final DebugState debug;
	private final HostedSettings hostedSettings;
	private final String sessionId;
	private final SettlementState settlement;

	public CommunityLootsharePanelState(boolean ready, boolean inParty, String partyPassphrase,
	                                    List<MemberLoot> members)
	{
		this(ready, inParty, partyPassphrase, members, false, DebugState.unavailable(), HostedSettings.waiting());
	}

	public CommunityLootsharePanelState(boolean ready, boolean inParty, String partyPassphrase,
	                                    List<MemberLoot> members, boolean previousPartyAvailable)
	{
		this(ready, inParty, partyPassphrase, members, previousPartyAvailable,
			DebugState.unavailable(), HostedSettings.waiting());
	}

	public CommunityLootsharePanelState(boolean ready, boolean inParty, String partyPassphrase,
	                                    List<MemberLoot> members, DebugState debug)
	{
		this(ready, inParty, partyPassphrase, members, false, debug, HostedSettings.waiting());
	}

	public CommunityLootsharePanelState(boolean ready, boolean inParty, String partyPassphrase,
	                                    List<MemberLoot> members, boolean previousPartyAvailable,
	                                    DebugState debug)
	{
		this(ready, inParty, partyPassphrase, members, previousPartyAvailable, debug,
			HostedSettings.waiting());
	}

	public CommunityLootsharePanelState(boolean ready, boolean inParty, String partyPassphrase,
	                                    List<MemberLoot> members, boolean previousPartyAvailable,
	                                    DebugState debug, HostedSettings hostedSettings)
	{
		this(ready, inParty, partyPassphrase, members, previousPartyAvailable, debug, hostedSettings,
			null, SettlementState.empty());
	}

	public CommunityLootsharePanelState(boolean ready, boolean inParty, String partyPassphrase,
	                                    List<MemberLoot> members, boolean previousPartyAvailable,
	                                    DebugState debug, HostedSettings hostedSettings,
	                                    String sessionId, SettlementState settlement)
	{
		this.ready = ready;
		this.inParty = inParty;
		this.partyPassphrase = partyPassphrase;
		this.members = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(members, "members")));
		this.previousPartyAvailable = previousPartyAvailable;
		this.debug = Objects.requireNonNull(debug, "debug");
		this.hostedSettings = Objects.requireNonNull(hostedSettings, "hostedSettings");
		this.sessionId = sessionId;
		this.settlement = Objects.requireNonNull(settlement, "settlement");
	}

	public boolean isReady()
	{
		return ready;
	}

	public boolean isInParty()
	{
		return inParty;
	}

	public String getPartyPassphrase()
	{
		return partyPassphrase;
	}

	public List<MemberLoot> getMembers()
	{
		return members;
	}

	public boolean isPreviousPartyAvailable()
	{
		return previousPartyAvailable;
	}

	public DebugState getDebug()
	{
		return debug;
	}

	public HostedSettings getHostedSettings()
	{
		return hostedSettings;
	}

	public String getSessionId()
	{
		return sessionId;
	}

	public SettlementState getSettlement()
	{
		return settlement;
	}

	public static final class SettlementState
	{
		private final long totalLoot;
		private final List<BalanceRow> balances;
		private final List<TransferRow> transfers;
		private final Map<SessionGraphMode, SessionGraphSnapshot> graphs;

		public SettlementState(long totalLoot, List<BalanceRow> balances, List<TransferRow> transfers,
		                       Map<SessionGraphMode, SessionGraphSnapshot> graphs)
		{
			if (totalLoot < 0L)
			{
				throw new IllegalArgumentException("Settlement total cannot be negative");
			}
			this.totalLoot = totalLoot;
			List<BalanceRow> balanceCopy = new ArrayList<>(Objects.requireNonNull(balances, "balances"));
			List<TransferRow> transferCopy = new ArrayList<>(Objects.requireNonNull(transfers, "transfers"));
			if (balanceCopy.stream().anyMatch(Objects::isNull) || transferCopy.stream().anyMatch(Objects::isNull))
			{
				throw new IllegalArgumentException("Settlement rows cannot be null");
			}
			this.balances = Collections.unmodifiableList(balanceCopy);
			this.transfers = Collections.unmodifiableList(transferCopy);
			EnumMap<SessionGraphMode, SessionGraphSnapshot> snapshots = new EnumMap<>(SessionGraphMode.class);
			for (Map.Entry<SessionGraphMode, SessionGraphSnapshot> entry :
				Objects.requireNonNull(graphs, "graphs").entrySet())
			{
				snapshots.put(Objects.requireNonNull(entry.getKey(), "graphMode"),
					Objects.requireNonNull(entry.getValue(), "graphSnapshot"));
			}
			this.graphs = Collections.unmodifiableMap(snapshots);
		}

		public static SettlementState empty()
		{
			EnumMap<SessionGraphMode, SessionGraphSnapshot> graphs = new EnumMap<>(SessionGraphMode.class);
			for (SessionGraphMode mode : SessionGraphMode.values())
			{
				graphs.put(mode, SessionGraphSnapshot.empty(mode));
			}
			return new SettlementState(0L, Collections.emptyList(), Collections.emptyList(), graphs);
		}

		public long getTotalLoot()
		{
			return totalLoot;
		}

		public List<BalanceRow> getBalances()
		{
			return balances;
		}

		public List<TransferRow> getTransfers()
		{
			return transfers;
		}

		public SessionGraphSnapshot getGraph(SessionGraphMode mode)
		{
			SessionGraphMode selected = mode == null ? SessionGraphMode.GP_PER_HOUR : mode;
			return graphs.getOrDefault(selected, SessionGraphSnapshot.empty(selected));
		}

		public boolean hasData()
		{
			return totalLoot > 0L || !balances.isEmpty();
		}
	}

	public static final class BalanceRow
	{
		private final long memberId;
		private final String displayName;
		private final long loot;
		private final long share;
		private final long net;

		public BalanceRow(long memberId, String displayName, long loot, long share, long net)
		{
			if (memberId <= 0L || displayName == null || displayName.trim().isEmpty()
				|| loot < 0L || share < 0L)
			{
				throw new IllegalArgumentException("Settlement balance row is invalid");
			}
			this.memberId = memberId;
			this.displayName = displayName.trim();
			this.loot = loot;
			this.share = share;
			this.net = net;
		}

		public long getMemberId()
		{
			return memberId;
		}

		public String getDisplayName()
		{
			return displayName;
		}

		public long getLoot()
		{
			return loot;
		}

		public long getShare()
		{
			return share;
		}

		public long getNet()
		{
			return net;
		}
	}

	public static final class TransferRow
	{
		private final String from;
		private final String to;
		private final long amount;

		public TransferRow(String from, String to, long amount)
		{
			if (from == null || from.trim().isEmpty() || to == null || to.trim().isEmpty() || amount <= 0L)
			{
				throw new IllegalArgumentException("Settlement transfer row is invalid");
			}
			this.from = from.trim();
			this.to = to.trim();
			this.amount = amount;
		}

		public String getFrom()
		{
			return from;
		}

		public String getTo()
		{
			return to;
		}

		public long getAmount()
		{
			return amount;
		}
	}

	public static final class HostedSettings
	{
		private final boolean available;
		private final long hostMemberId;
		private final String hostDisplayName;
		private final boolean localHost;
		private final LootshareSettings settings;

		private HostedSettings(boolean available, long hostMemberId, String hostDisplayName,
		                       boolean localHost, LootshareSettings settings)
		{
			if (available && (hostMemberId <= 0L || hostDisplayName == null
				|| hostDisplayName.trim().isEmpty() || settings == null))
			{
				throw new IllegalArgumentException("Available host settings require a host and settings snapshot");
			}
			this.available = available;
			this.hostMemberId = available ? hostMemberId : 0L;
			this.hostDisplayName = available ? hostDisplayName.trim() : null;
			this.localHost = available && localHost;
			this.settings = available ? settings.validatedCopy() : null;
		}

		public static HostedSettings waiting()
		{
			return new HostedSettings(false, 0L, null, false, null);
		}

		public static HostedSettings available(long hostMemberId, String hostDisplayName,
		                                       boolean localHost, LootshareSettings settings)
		{
			return new HostedSettings(true, hostMemberId, hostDisplayName, localHost, settings);
		}

		public boolean isAvailable()
		{
			return available;
		}

		public long getHostMemberId()
		{
			return hostMemberId;
		}

		public String getHostDisplayName()
		{
			return hostDisplayName;
		}

		public boolean isLocalHost()
		{
			return localHost;
		}

		public LootshareSettings getSettings()
		{
			return settings == null ? null : settings.validatedCopy();
		}
	}

	public static final class DebugState
	{
		private final boolean available;
		private final boolean simulationActive;
		private final long ownerMemberId;
		private final Map<LootPreset, Long> lootPresetPrices;

		public DebugState(boolean available, boolean simulationActive, long ownerMemberId)
		{
			this(available, simulationActive, ownerMemberId, Collections.emptyMap());
		}

		public DebugState(boolean available, boolean simulationActive, long ownerMemberId,
		                  Map<LootPreset, Long> lootPresetPrices)
		{
			if (!available && simulationActive)
			{
				throw new IllegalArgumentException("An unavailable debug session cannot be active");
			}
			if (simulationActive && ownerMemberId <= 0L)
			{
				throw new IllegalArgumentException("An active debug session requires an owner");
			}
			this.available = available;
			this.simulationActive = simulationActive;
			this.ownerMemberId = simulationActive ? ownerMemberId : 0L;
			EnumMap<LootPreset, Long> prices = new EnumMap<>(LootPreset.class);
			for (Map.Entry<LootPreset, Long> entry : Objects.requireNonNull(
				lootPresetPrices, "lootPresetPrices").entrySet())
			{
				LootPreset preset = Objects.requireNonNull(entry.getKey(), "lootPreset");
				Long price = Objects.requireNonNull(entry.getValue(), "lootPresetPrice");
				if (price < 0L)
				{
					throw new IllegalArgumentException("Debug loot preset prices must be non-negative");
				}
				prices.put(preset, price);
			}
			this.lootPresetPrices = Collections.unmodifiableMap(prices);
		}

		public static DebugState unavailable()
		{
			return new DebugState(false, false, 0L);
		}

		public boolean isAvailable()
		{
			return available;
		}

		public boolean isSimulationActive()
		{
			return simulationActive;
		}

		public long getOwnerMemberId()
		{
			return ownerMemberId;
		}

		public long getLootPresetPrice(LootPreset lootPreset)
		{
			if (lootPreset == null)
			{
				return 0L;
			}
			return lootPresetPrices.getOrDefault(lootPreset, 0L);
		}
	}

	public static final class MemberLoot
	{
		private final long memberId;
		private final String displayName;
		private final boolean local;
		private final boolean host;
		private final boolean loggedIn;
		private final BufferedImage avatar;
		private final int proposalCount;
		private final int pendingProposalCount;
		private final long totalValue;
		private final List<LootItem> items;
		private final MemberApprovalStatus approvalStatus;

		public MemberLoot(long memberId, String displayName, boolean local, boolean loggedIn,
		                  BufferedImage avatar, int proposalCount, int pendingProposalCount,
		                  long totalValue, List<LootItem> items)
		{
			this(memberId, displayName, local, false, loggedIn, avatar, proposalCount,
				pendingProposalCount, totalValue, items);
		}

		public MemberLoot(long memberId, String displayName, boolean local, boolean host, boolean loggedIn,
		                  BufferedImage avatar, int proposalCount, int pendingProposalCount,
		                  long totalValue, List<LootItem> items)
		{
			this(memberId, displayName, local, host, loggedIn, avatar, proposalCount,
				pendingProposalCount, totalValue, items,
				host ? MemberApprovalStatus.APPROVED : MemberApprovalStatus.PENDING);
		}

		public MemberLoot(long memberId, String displayName, boolean local, boolean host, boolean loggedIn,
		                  BufferedImage avatar, int proposalCount, int pendingProposalCount,
		                  long totalValue, List<LootItem> items, MemberApprovalStatus approvalStatus)
		{
			this.memberId = memberId;
			this.displayName = Objects.requireNonNull(displayName, "displayName");
			this.local = local;
			this.host = host;
			this.loggedIn = loggedIn;
			this.avatar = avatar;
			this.proposalCount = proposalCount;
			this.pendingProposalCount = pendingProposalCount;
			this.totalValue = totalValue;
			this.items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(items, "items")));
			this.approvalStatus = Objects.requireNonNull(approvalStatus, "approvalStatus");
		}

		public long getMemberId()
		{
			return memberId;
		}

		public String getDisplayName()
		{
			return displayName;
		}

		public boolean isLocal()
		{
			return local;
		}

		public boolean isHost()
		{
			return host;
		}

		public boolean isLoggedIn()
		{
			return loggedIn;
		}

		public BufferedImage getAvatar()
		{
			return avatar;
		}

		public int getProposalCount()
		{
			return proposalCount;
		}

		public int getPendingProposalCount()
		{
			return pendingProposalCount;
		}

		public long getTotalValue()
		{
			return totalValue;
		}

		public List<LootItem> getItems()
		{
			return items;
		}

		public MemberApprovalStatus getApprovalStatus()
		{
			return approvalStatus;
		}
	}

	public static final class LootItem
	{
		private final int itemId;
		private final String name;
		private final long quantity;
		private final long totalValue;

		public LootItem(int itemId, String name, long quantity, long totalValue)
		{
			this.itemId = itemId;
			this.name = Objects.requireNonNull(name, "name");
			this.quantity = quantity;
			this.totalValue = totalValue;
		}

		public int getItemId()
		{
			return itemId;
		}

		public String getName()
		{
			return name;
		}

		public long getQuantity()
		{
			return quantity;
		}

		public long getTotalValue()
		{
			return totalValue;
		}
	}
}
