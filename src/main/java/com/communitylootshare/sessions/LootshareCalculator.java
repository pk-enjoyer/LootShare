/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.sessions;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;

/**
 * Calculates deterministic balances and direct settlement transfers for a session.
 *
 * <p>Remainder coins are assigned in ascending member-ID order from each proposal's frozen roster,
 * and the resulting balances are always checked to be zero-sum.</p>
 */
@Singleton
public class LootshareCalculator
{
	/**
	 * Includes accepted proposals meeting the session minimum, then derives each member's received,
	 * entitled, and net amounts.
	 */
	public LootshareCalculation calculate(LootshareSession session)
	{
		if (session == null)
		{
			return LootshareCalculation.empty();
		}

		Map<Long, Accumulator> byMember = new LinkedHashMap<>();
		List<LootshareCalculation.IncludedLoot> includedLoot = new ArrayList<>();
		long totalAcceptedValue = 0L;
		for (LootProposal proposal : session.getAcceptedProposals())
		{
			if (proposal == null || proposal.getStatus() != LootProposalStatus.ACCEPTED)
			{
				continue;
			}
			long eventValue = proposal.getEvent().getTotal();
			if (eventValue < session.getMinimumSharedLootValue())
			{
				continue;
			}
			totalAcceptedValue = Math.addExact(totalAcceptedValue, eventValue);
			includedLoot.add(new LootshareCalculation.IncludedLoot(proposal.getProposalId(),
				eventValue, proposal.getEvent().getCapturedAt(), proposal.getDecidedAt()));

			List<LootshareParticipant> participants = proposal.getParticipants();
			long baseShare = eventValue / participants.size();
			long remainder = eventValue % participants.size();
			for (int index = 0; index < participants.size(); index++)
			{
				LootshareParticipant participant = participants.get(index);
				Accumulator accumulator = byMember.computeIfAbsent(participant.getMemberId(),
					ignored -> new Accumulator(participant));
				accumulator.displayName = participant.getDisplayName();
				long share = Math.addExact(baseShare, index < remainder ? 1L : 0L);
				accumulator.entitled = Math.addExact(accumulator.entitled, share);
			}

			Accumulator owner = byMember.get(proposal.getOwnerMemberId());
			if (owner == null)
			{
				throw new IllegalStateException("Accepted proposal owner is missing from its split roster");
			}
			owner.received = Math.addExact(owner.received, eventValue);
		}

		List<LootshareCalculation.Balance> balances = new ArrayList<>();
		for (Accumulator accumulator : byMember.values())
		{
			balances.add(new LootshareCalculation.Balance(accumulator.memberId, accumulator.displayName,
				accumulator.received, accumulator.entitled));
		}
		balances.sort(Comparator.comparingLong(LootshareCalculation.Balance::getMemberId));

		long netTotal = 0L;
		for (LootshareCalculation.Balance balance : balances)
		{
			netTotal = Math.addExact(netTotal, balance.getNetValue());
		}
		if (netTotal != 0L)
		{
			throw new IllegalStateException("Lootshare balances are not zero-sum");
		}

		includedLoot.sort(Comparator.comparing(LootshareCalculation.IncludedLoot::getCapturedAt)
			.thenComparing(LootshareCalculation.IncludedLoot::getProposalId));
		return new LootshareCalculation(totalAcceptedValue, balances, buildTransfers(balances), includedLoot);
	}

	/** Matches ordered debtors and creditors to produce a deterministic minimal transfer list. */
	private List<LootshareCalculation.Transfer> buildTransfers(List<LootshareCalculation.Balance> balances)
	{
		List<Outstanding> payers = new ArrayList<>();
		List<Outstanding> receivers = new ArrayList<>();
		for (LootshareCalculation.Balance balance : balances)
		{
			if (balance.getNetValue() < 0L)
			{
				payers.add(new Outstanding(balance, Math.negateExact(balance.getNetValue())));
			}
			else if (balance.getNetValue() > 0L)
			{
				receivers.add(new Outstanding(balance, balance.getNetValue()));
			}
		}

		List<LootshareCalculation.Transfer> transfers = new ArrayList<>();
		int payerIndex = 0;
		int receiverIndex = 0;
		while (payerIndex < payers.size() && receiverIndex < receivers.size())
		{
			Outstanding payer = payers.get(payerIndex);
			Outstanding receiver = receivers.get(receiverIndex);
			long amount = Math.min(payer.remaining, receiver.remaining);
			transfers.add(new LootshareCalculation.Transfer(
				payer.balance.getMemberId(), payer.balance.getDisplayName(),
				receiver.balance.getMemberId(), receiver.balance.getDisplayName(), amount));
			payer.remaining -= amount;
			receiver.remaining -= amount;
			if (payer.remaining == 0L)
			{
				payerIndex++;
			}
			if (receiver.remaining == 0L)
			{
				receiverIndex++;
			}
		}
		return transfers;
	}

	private static final class Accumulator
	{
		private final long memberId;
		private String displayName;
		private long received;
		private long entitled;

		private Accumulator(LootshareParticipant participant)
		{
			this.memberId = participant.getMemberId();
			this.displayName = participant.getDisplayName();
		}
	}

	private static final class Outstanding
	{
		private final LootshareCalculation.Balance balance;
		private long remaining;

		private Outstanding(LootshareCalculation.Balance balance, long remaining)
		{
			this.balance = balance;
			this.remaining = remaining;
		}
	}
}
