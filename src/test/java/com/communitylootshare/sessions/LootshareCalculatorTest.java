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
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class LootshareCalculatorTest
{
	@Test
	public void calculatesExactRemaindersAcrossChangingRostersAndSettlementTransfers()
	{
		LootshareSession session = new LootshareSession("s", 10L, Instant.EPOCH);
		session.addAcceptedProposal(accepted("p1", 1L, 100L, Instant.ofEpochSecond(1),
			new LootshareParticipant(3L, "Charlie"),
			new LootshareParticipant(1L, "Alice"),
			new LootshareParticipant(2L, "Bob")));
		session.addAcceptedProposal(accepted("p2", 3L, 60L, Instant.ofEpochSecond(2),
			new LootshareParticipant(2L, "Bobby"),
			new LootshareParticipant(3L, "Charlie")));

		LootshareCalculation result = new LootshareCalculator().calculate(session);
		assertEquals(160L, result.getTotalAcceptedValue());
		assertEquals(2, result.getIncludedLoot().size());
		assertEquals("p1", result.getIncludedLoot().get(0).getProposalId());
		assertEquals(100L, result.getIncludedLoot().get(0).getValue());
		assertEquals(Instant.ofEpochSecond(1), result.getIncludedLoot().get(0).getCapturedAt());
		assertEquals("p2", result.getIncludedLoot().get(1).getProposalId());
		assertEquals(3, result.getBalances().size());
		assertBalance(result, 1L, "Alice", 100L, 34L, -66L);
		assertBalance(result, 2L, "Bobby", 0L, 63L, 63L);
		assertBalance(result, 3L, "Charlie", 60L, 63L, 3L);
		assertEquals(2, result.getTransfers().size());
		assertTransfer(result.getTransfers().get(0), 1L, "Alice", 2L, "Bobby", 63L);
		assertTransfer(result.getTransfers().get(1), 1L, "Alice", 3L, "Charlie", 3L);
	}

	@Test
	public void handlesNullEmptyAndSoloSessions()
	{
		LootshareCalculator calculator = new LootshareCalculator();
		LootshareCalculation empty = calculator.calculate(null);
		assertEquals(0L, empty.getTotalAcceptedValue());
		assertTrue(empty.getBalances().isEmpty());
		assertTrue(empty.getTransfers().isEmpty());

		LootshareSession session = new LootshareSession("s", 10L, Instant.EPOCH);
		assertTrue(calculator.calculate(session).getBalances().isEmpty());
		session.addAcceptedProposal(accepted("solo", 5L, 99L, Instant.ofEpochSecond(1),
			new LootshareParticipant(5L, "Solo")));
		LootshareCalculation solo = calculator.calculate(session);
		assertBalance(solo, 5L, "Solo", 99L, 99L, 0L);
		assertTrue(solo.getTransfers().isEmpty());
	}

	@Test
	public void excludesAcceptedDropsBelowTheHostThresholdWithoutRemovingThemFromTheSession()
	{
		LootshareSession session = new LootshareSession("threshold", 10L, Instant.EPOCH);
		session.setHostState(1L, 100L, 1L);
		session.addAcceptedProposal(accepted("below", 1L, 99L, Instant.ofEpochSecond(1),
			new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
		session.addAcceptedProposal(accepted("at", 1L, 100L, Instant.ofEpochSecond(2),
			new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));

		LootshareCalculation calculation = new LootshareCalculator().calculate(session);

		assertEquals(2, session.getAcceptedProposals().size());
		assertEquals(100L, calculation.getTotalAcceptedValue());
		assertEquals(1, calculation.getIncludedLoot().size());
		assertEquals("at", calculation.getIncludedLoot().get(0).getProposalId());
		assertBalance(calculation, 1L, "Alice", 100L, 50L, -50L);
		assertBalance(calculation, 2L, "Bob", 0L, 50L, 50L);
	}

	@Test
	public void calculationValuesValidateAndExposeFields()
	{
		LootshareCalculation.Balance balance = new LootshareCalculation.Balance(1L, "Alice", 20L, 30L);
		assertEquals(1L, balance.getMemberId());
		assertEquals("Alice", balance.getDisplayName());
		assertEquals(20L, balance.getReceivedValue());
		assertEquals(30L, balance.getEntitledValue());
		assertEquals(10L, balance.getNetValue());

		LootshareCalculation.Transfer transfer = new LootshareCalculation.Transfer(1L, "Alice", 2L, "Bob", 10L);
		assertTransfer(transfer, 1L, "Alice", 2L, "Bob", 10L);
		expectIllegal(() -> new LootshareCalculation(-1L, Collections.emptyList(), Collections.emptyList()));
		expectIllegal(() -> new LootshareCalculation(0L, null, Collections.emptyList()));
		expectIllegal(() -> new LootshareCalculation(0L, Collections.emptyList(), null));
		expectIllegal(() -> new LootshareCalculation(0L, Collections.emptyList(), Collections.emptyList(), null));
		expectIllegal(() -> new LootshareCalculation(0L, Collections.emptyList(), Collections.emptyList(),
			Collections.singletonList(null)));
		expectIllegal(() -> new LootshareCalculation.Balance(1L, "Alice", -1L, 0L));
		expectIllegal(() -> new LootshareCalculation.Transfer(1L, "Alice", 1L, "Alice", 1L));
		expectIllegal(() -> new LootshareCalculation.Transfer(1L, "Alice", 2L, "Bob", 0L));
	}

	@Test
	public void calculationResultListsAreImmutable()
	{
		LootshareCalculation result = new LootshareCalculation(0L,
			Collections.singletonList(new LootshareCalculation.Balance(1L, "Alice", 0L, 0L)),
			Collections.emptyList());
		try
		{
			result.getBalances().clear();
			fail("Expected immutable balances");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
		try
		{
			result.getIncludedLoot().clear();
			fail("Expected immutable included-loot history");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	private static LootProposal accepted(String id, long owner, long value, Instant at,
	                                     LootshareParticipant... participants)
	{
		SharedLootEvent event = new SharedLootEvent(id, owner == 1L ? "Alice" : "Charlie", "Boss", at,
			Collections.singletonList(new SharedLootItem(1, 1, 1, value)));
		return LootProposal.pending(10L, owner, event)
			.decide(LootProposalStatus.ACCEPTED, at, Arrays.asList(participants));
	}

	private static void assertBalance(LootshareCalculation result, long memberId, String name,
	                                  long received, long entitled, long net)
	{
		LootshareCalculation.Balance balance = result.getBalances().stream()
			.filter(candidate -> candidate.getMemberId() == memberId)
			.findFirst()
			.orElseThrow(() -> new AssertionError("Missing balance for " + memberId));
		assertEquals(name, balance.getDisplayName());
		assertEquals(received, balance.getReceivedValue());
		assertEquals(entitled, balance.getEntitledValue());
		assertEquals(net, balance.getNetValue());
	}

	private static void assertTransfer(LootshareCalculation.Transfer transfer, long fromId, String fromName,
	                                   long toId, String toName, long amount)
	{
		assertEquals(fromId, transfer.getFromMemberId());
		assertEquals(fromName, transfer.getFromDisplayName());
		assertEquals(toId, transfer.getToMemberId());
		assertEquals(toName, transfer.getToDisplayName());
		assertEquals(amount, transfer.getAmount());
	}

	private static void expectIllegal(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected.
		}
	}
}
