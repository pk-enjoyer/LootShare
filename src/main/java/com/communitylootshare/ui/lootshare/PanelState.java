/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.LootshareSettings;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable client-thread snapshot rendered by the Swing sidebar.
 */
public final class PanelState
{
	private final boolean ready;
	private final boolean inParty;
	private final String partyPassphrase;
	private final List<MemberLoot> members;
	private final boolean previousPartyAvailable;
	private final HostedSettings hostedSettings;
	private final String sessionId;
	private final SettlementState settlement;

	public PanelState(boolean ready, boolean inParty, String partyPassphrase,
	                  List<MemberLoot> members)
	{
		this(ready, inParty, partyPassphrase, members, false, HostedSettings.waiting());
	}

	public PanelState(boolean ready, boolean inParty, String partyPassphrase,
	                  List<MemberLoot> members, boolean previousPartyAvailable)
	{
		this(ready, inParty, partyPassphrase, members, previousPartyAvailable, HostedSettings.waiting());
	}

	public PanelState(boolean ready, boolean inParty, String partyPassphrase,
	                  List<MemberLoot> members, boolean previousPartyAvailable,
	                  HostedSettings hostedSettings)
	{
		this(ready, inParty, partyPassphrase, members, previousPartyAvailable, hostedSettings,
			null, SettlementState.empty());
	}

	public PanelState(boolean ready, boolean inParty, String partyPassphrase,
	                  List<MemberLoot> members, boolean previousPartyAvailable,
	                  HostedSettings hostedSettings,
	                  String sessionId, SettlementState settlement)
	{
		this.ready = ready;
		this.inParty = inParty;
		this.partyPassphrase = partyPassphrase;
		this.members = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(members, "members")));
		this.previousPartyAvailable = previousPartyAvailable;
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

		public SettlementState(long totalLoot, List<BalanceRow> balances, List<TransferRow> transfers)
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
		}

		public static SettlementState empty()
		{
			return new SettlementState(0L, Collections.emptyList(), Collections.emptyList());
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
