/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.EqualsAndHashCode;

/** Immutable cumulative settlement, referencing canonical loot without copying items. */
@EqualsAndHashCode
public final class SettlementSummary
{
	public static final int MAX_MEMBERS = 4096;
	private final long total;
	private final List<LootshareCalculation.Balance> balances;
	private final List<LootshareCalculation.Transfer> transfers;
	private final List<String> proposalIds;

	public SettlementSummary(long total, List<LootshareCalculation.Balance> balances,
	                         List<LootshareCalculation.Transfer> transfers, List<String> proposalIds)
	{
		if (total < 0 || balances == null || transfers == null || proposalIds == null
			|| balances.size() > MAX_MEMBERS || transfers.size() > MAX_MEMBERS
			|| proposalIds.size() > LootshareSession.MAX_ACCEPTED_PROPOSALS)
		{
			throw new IllegalArgumentException("Invalid settlement summary bounds");
		}
		List<LootshareCalculation.Balance> bs = new ArrayList<>();
		Map<Long, Long> remaining = new HashMap<>();
		long received = 0, entitled = 0;
		for (LootshareCalculation.Balance b : balances)
		{
			LootshareCalculation.Balance copy = new LootshareCalculation.Balance(b.getMemberId(),
				b.getDisplayName(), b.getReceivedValue(), b.getEntitledValue());
			if (remaining.put(copy.getMemberId(), copy.getNetValue()) != null)
			{
				throw new IllegalArgumentException("Duplicate balance member");
			}
			received = Math.addExact(received, copy.getReceivedValue());
			entitled = Math.addExact(entitled, copy.getEntitledValue());
			bs.add(copy);
		}
		if (received != total || entitled != total)
		{
			throw new IllegalArgumentException("Settlement must conserve coins");
		}
		List<LootshareCalculation.Transfer> ts = new ArrayList<>();
		for (LootshareCalculation.Transfer t : transfers)
		{
			LootshareCalculation.Transfer copy = new LootshareCalculation.Transfer(t.getFromMemberId(),
				t.getFromDisplayName(), t.getToMemberId(), t.getToDisplayName(), t.getAmount());
			Long from = remaining.get(copy.getFromMemberId()), to = remaining.get(copy.getToMemberId());
			if (from == null || to == null || from >= 0 || to <= 0 || copy.getAmount() > -from || copy.getAmount() > to)
			{
				throw new IllegalArgumentException("Invalid settlement transfer");
			}
			remaining.put(copy.getFromMemberId(), Math.addExact(from, copy.getAmount()));
			remaining.put(copy.getToMemberId(), Math.subtractExact(to, copy.getAmount()));
			ts.add(copy);
		}
		if (remaining.values().stream().anyMatch(value -> value != 0L))
		{
			throw new IllegalArgumentException("Settlement transfers do not clear balances");
		}
		Set<String> ids = new HashSet<>();
		for (String id : proposalIds)
		{
			if (id == null || id.isEmpty() || id.length() > SharedLootEvent.MAX_PROPOSAL_ID_LENGTH || !ids.add(id))
			{
				throw new IllegalArgumentException("Invalid checkpoint proposal reference");
			}
		}
		bs.sort(Comparator.comparingLong(LootshareCalculation.Balance::getMemberId));
		List<String> sortedIds = new ArrayList<>(ids);
		Collections.sort(sortedIds);
		this.total = total;
		this.balances = Collections.unmodifiableList(bs);
		this.transfers = Collections.unmodifiableList(ts);
		this.proposalIds = Collections.unmodifiableList(sortedIds);
	}
	public static SettlementSummary from(LootshareCalculation calculation)
	{
		List<String> ids = new ArrayList<>();
		for (LootshareCalculation.IncludedLoot loot : calculation.getIncludedLoot()) { ids.add(loot.getProposalId()); }
		return new SettlementSummary(calculation.getTotalAcceptedValue(), calculation.getBalances(), calculation.getTransfers(), ids);
	}
	public SettlementSummary validatedCopy() { return new SettlementSummary(total, balances, transfers, proposalIds); }
	public long getTotal() { return total; }
	public List<LootshareCalculation.Balance> getBalances() { return balances; }
	public List<LootshareCalculation.Transfer> getTransfers() { return transfers; }
	public List<String> getProposalIds() { return proposalIds; }
}
