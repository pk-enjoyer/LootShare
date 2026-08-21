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

@Singleton
public class LootshareCalculator
{
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
		return new LootshareCalculation(totalAcceptedValue, balances,
			buildHostSettlementTransfers(balances, session.getHostMemberId()), includedLoot);
	}

	private List<LootshareCalculation.Transfer> buildHostSettlementTransfers(
		List<LootshareCalculation.Balance> balances, long hostMemberId)
	{
		LootshareCalculation.Balance host = null;
		for (LootshareCalculation.Balance balance : balances)
		{
			if (balance.getMemberId() == hostMemberId)
			{
				host = balance;
				break;
			}
		}
		if (host == null)
		{
			return new ArrayList<>();
		}

		List<LootshareCalculation.Transfer> transfers = new ArrayList<>();
		for (LootshareCalculation.Balance balance : balances)
		{
			if (balance.getMemberId() == hostMemberId || balance.getNetValue() == 0L)
			{
				continue;
			}
			if (balance.getNetValue() < 0L)
			{
				transfers.add(new LootshareCalculation.Transfer(
					balance.getMemberId(), balance.getDisplayName(), host.getMemberId(), host.getDisplayName(),
					Math.negateExact(balance.getNetValue())));
			}
			else
			{
				transfers.add(new LootshareCalculation.Transfer(
					host.getMemberId(), host.getDisplayName(), balance.getMemberId(), balance.getDisplayName(),
					balance.getNetValue()));
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

}
