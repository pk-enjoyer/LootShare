/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.sessions;

import com.communitylootshare.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LootshareCalculatorEdgeCaseTest
{
	@Test public void settlementsConserveCoinsAcrossTwentyChangingRosterScenarios()
	{
		Random random = new Random(7731L);
		for (int scenario = 0; scenario < 20; scenario++)
		{
			LootshareSession session = new LootshareSession("s-" + scenario, 77L, Instant.EPOCH);
			Map<Long, Long> expectedShares = new HashMap<>();
			Map<Long, Long> expectedReceived = new HashMap<>();
			long total = 0;
			for (int drop = 0; drop < 80; drop++)
			{
				List<LootshareParticipant> roster = new ArrayList<>();
				for (long member = 1; member <= 6; member++)
					if (random.nextBoolean()) roster.add(new LootshareParticipant(member, "Member " + member));
				if (roster.isEmpty()) roster.add(new LootshareParticipant(1L, "Member 1"));
				long owner = roster.get(random.nextInt(roster.size())).getMemberId();
				long value = 1L + random.nextInt(10000);
				total += value;
				for (int index = 0; index < roster.size(); index++)
				{
					long member = roster.get(index).getMemberId();
					long roundedShare = (value + roster.size() - 1L - index) / roster.size();
					expectedShares.merge(member, roundedShare, Long::sum);
				}
				expectedReceived.merge(owner, value, Long::sum);
				session.addAcceptedProposal(accepted("p-" + drop, owner, value, roster));
			}
			LootshareCalculation calculation = new LootshareCalculator().calculate(session.snapshot());
			assertEquals(total, calculation.getTotalAcceptedValue());
			Map<Long, Long> unsettled = new HashMap<>();
			long net = 0;
			for (LootshareCalculation.Balance balance : calculation.getBalances())
			{
				assertEquals(expectedShares.get(balance.getMemberId()).longValue(), balance.getEntitledValue());
				assertEquals(expectedReceived.getOrDefault(balance.getMemberId(), 0L).longValue(), balance.getReceivedValue());
				unsettled.put(balance.getMemberId(), balance.getNetValue());
				net += balance.getNetValue();
			}
			assertEquals(0L, net);
			for (LootshareCalculation.Transfer transfer : calculation.getTransfers())
			{
				unsettled.merge(transfer.getFromMemberId(), transfer.getAmount(), Long::sum);
				unsettled.merge(transfer.getToMemberId(), -transfer.getAmount(), Long::sum);
			}
			for (long remaining : unsettled.values()) assertEquals(0L, remaining);
		}
	}

	@Test public void largestRepresentableLootStillSplitsAndSettlesExactly()
	{
		LootshareSession session = new LootshareSession("max", 77L, Instant.EPOCH);
		session.addAcceptedProposal(accepted("max", 1L, Long.MAX_VALUE, Arrays.asList(
			new LootshareParticipant(1L, "One"), new LootshareParticipant(2L, "Two"),
			new LootshareParticipant(3L, "Three"))));
		LootshareCalculation calculation = new LootshareCalculator().calculate(session.snapshot());
		assertEquals(Long.MAX_VALUE, calculation.getTotalAcceptedValue());
		assertEquals(Long.MAX_VALUE / 3L + 1L, calculation.getBalances().get(0).getEntitledValue());
		assertEquals(Long.MAX_VALUE / 3L, calculation.getBalances().get(1).getEntitledValue());
		assertEquals(Long.MAX_VALUE / 3L, calculation.getBalances().get(2).getEntitledValue());
		assertEquals(2, calculation.getTransfers().size());
		assertEquals(Long.MAX_VALUE / 3L, calculation.getTransfers().get(0).getAmount());
		assertEquals(Long.MAX_VALUE / 3L, calculation.getTransfers().get(1).getAmount());
	}

	private static LootProposal accepted(String id, long owner, long value, List<LootshareParticipant> roster)
	{
		return LootProposal.pending(77L, owner, new SharedLootEvent(id, "Owner", "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, value))))
			.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH, roster);
	}
}
