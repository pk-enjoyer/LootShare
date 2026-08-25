/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;

public final class LootshareCalculation
{
	private final long totalAcceptedValue;
	private final List<Balance> balances;
	private final List<Transfer> transfers;
	private final List<IncludedLoot> includedLoot;

	public LootshareCalculation(long totalAcceptedValue, List<Balance> balances, List<Transfer> transfers)
	{
		this(totalAcceptedValue, balances, transfers, Collections.emptyList());
	}

	public LootshareCalculation(long totalAcceptedValue, List<Balance> balances, List<Transfer> transfers,
	                            List<IncludedLoot> includedLoot)
	{
		if (totalAcceptedValue < 0L || balances == null || transfers == null || includedLoot == null)
		{
			throw new IllegalArgumentException("Calculation totals and result lists are required");
		}
		this.totalAcceptedValue = totalAcceptedValue;
		this.balances = Collections.unmodifiableList(new ArrayList<>(balances));
		this.transfers = Collections.unmodifiableList(new ArrayList<>(transfers));
		List<IncludedLoot> includedLootCopy = new ArrayList<>(includedLoot);
		if (includedLootCopy.stream().anyMatch(java.util.Objects::isNull))
		{
			throw new IllegalArgumentException("Included loot entries cannot be null");
		}
		this.includedLoot = Collections.unmodifiableList(includedLootCopy);
	}

	public static LootshareCalculation empty()
	{
		return new LootshareCalculation(0L, Collections.emptyList(), Collections.emptyList());
	}

	public long getTotalAcceptedValue()
	{
		return totalAcceptedValue;
	}

	public List<Balance> getBalances()
	{
		return balances;
	}

	public List<Transfer> getTransfers()
	{
		return transfers;
	}

	public List<IncludedLoot> getIncludedLoot()
	{
		return includedLoot;
	}

	@EqualsAndHashCode
	public static final class IncludedLoot
	{
		private final String proposalId;
		private final long value;
		private final Instant capturedAt;
		private final Instant decidedAt;

		public IncludedLoot(String proposalId, long value,
		                    Instant capturedAt, Instant decidedAt)
		{
			if (proposalId == null || proposalId.trim().isEmpty()
				|| proposalId.trim().length() > SharedLootEvent.MAX_PROPOSAL_ID_LENGTH
				|| value < 0L || capturedAt == null || decidedAt == null || decidedAt.isBefore(capturedAt))
			{
				throw new IllegalArgumentException("Included loot requires valid proposal, owner, value, and times");
			}
			this.proposalId = proposalId.trim();
			this.value = value;
			this.capturedAt = capturedAt;
			this.decidedAt = decidedAt;
		}

		public String getProposalId()
		{
			return proposalId;
		}

		public long getValue()
		{
			return value;
		}

		public Instant getCapturedAt()
		{
			return capturedAt;
		}

		public Instant getDecidedAt()
		{
			return decidedAt;
		}
	}

	@EqualsAndHashCode
	public static final class Balance
	{
		private final long memberId;
		private final String displayName;
		private final long receivedValue;
		private final long entitledValue;
		private final long netValue;

		public Balance(long memberId, String displayName, long receivedValue, long entitledValue)
		{
			LootshareParticipant participant = new LootshareParticipant(memberId, displayName);
			if (receivedValue < 0L || entitledValue < 0L)
			{
				throw new IllegalArgumentException("Balance values cannot be negative");
			}
			this.memberId = participant.getMemberId();
			this.displayName = participant.getDisplayName();
			this.receivedValue = receivedValue;
			this.entitledValue = entitledValue;
			this.netValue = Math.subtractExact(entitledValue, receivedValue);
		}

		public long getMemberId()
		{
			return memberId;
		}

		public String getDisplayName()
		{
			return displayName;
		}

		public long getReceivedValue()
		{
			return receivedValue;
		}

		public long getEntitledValue()
		{
			return entitledValue;
		}

		/**
		 * Positive means the member should receive; negative means the member should pay.
		 */
		public long getNetValue()
		{
			return netValue;
		}
	}

	@EqualsAndHashCode
	public static final class Transfer
	{
		private final long fromMemberId;
		private final String fromDisplayName;
		private final long toMemberId;
		private final String toDisplayName;
		private final long amount;

		public Transfer(long fromMemberId, String fromDisplayName, long toMemberId, String toDisplayName, long amount)
		{
			LootshareParticipant from = new LootshareParticipant(fromMemberId, fromDisplayName);
			LootshareParticipant to = new LootshareParticipant(toMemberId, toDisplayName);
			if (fromMemberId == toMemberId || amount <= 0L)
			{
				throw new IllegalArgumentException("Settlement transfer must have distinct members and a positive amount");
			}
			this.fromMemberId = from.getMemberId();
			this.fromDisplayName = from.getDisplayName();
			this.toMemberId = to.getMemberId();
			this.toDisplayName = to.getDisplayName();
			this.amount = amount;
		}

		public long getFromMemberId()
		{
			return fromMemberId;
		}

		public String getFromDisplayName()
		{
			return fromDisplayName;
		}

		public long getToMemberId()
		{
			return toMemberId;
		}

		public String getToDisplayName()
		{
			return toDisplayName;
		}

		public long getAmount()
		{
			return amount;
		}
	}
}
