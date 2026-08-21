/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class LootshareSessionTest
{
	@Test
	public void sessionStoresAcceptedProposalsInDecisionOrderAndSnapshotsState()
	{
		LootshareSession session = new LootshareSession(" session ", 10L, Instant.EPOCH);
		LootProposal later = accepted("later", 10L, Instant.ofEpochSecond(3));
		LootProposal earlier = accepted("earlier", 10L, Instant.ofEpochSecond(2));

		assertTrue(session.addAcceptedProposal(later));
		assertTrue(session.addAcceptedProposal(earlier));
		assertFalse(session.addAcceptedProposal(earlier));
		assertEquals("session", session.getSessionId());
		assertEquals(10L, session.getPartyId());
		assertEquals(Instant.EPOCH, session.getStartedAt());
		assertEquals("earlier", session.getAcceptedProposals().get(0).getProposalId());
		assertEquals("later", session.getAcceptedProposals().get(1).getProposalId());
		assertTrue(session.isActive());

		LootshareSession snapshot = session.snapshot();
		assertEquals(2, snapshot.getAcceptedProposals().size());
		assertTrue(session.end(Instant.ofEpochSecond(5)));
		assertFalse(session.end(Instant.ofEpochSecond(6)));
		assertFalse(session.isActive());
		assertEquals(Instant.ofEpochSecond(5), session.getEndedAt());
		assertTrue(snapshot.isActive());
		assertFalse(session.snapshot().isActive());
		try
		{
			session.getAcceptedProposals().clear();
			fail("Expected immutable accepted proposal list");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	@Test
	public void sessionRejectsInvalidState()
	{
		expectIllegal(() -> new LootshareSession(null, 1L, Instant.EPOCH));
		expectIllegal(() -> new LootshareSession(" ", 1L, Instant.EPOCH));
		expectIllegal(() -> new LootshareSession(repeat('x', 65), 1L, Instant.EPOCH));
		expectIllegal(() -> new LootshareSession("s", 0L, Instant.EPOCH));
		expectIllegal(() -> new LootshareSession("s", 1L, null));

		LootshareSession session = new LootshareSession("s", 1L, Instant.ofEpochSecond(5));
		expectIllegal(() -> session.addAcceptedProposal(null));
		expectIllegal(() -> session.addAcceptedProposal(LootProposal.pending(1L, 1L, event("p", 1L))));
		expectIllegal(() -> session.addAcceptedProposal(accepted("p", 2L, Instant.ofEpochSecond(6))));
		expectIllegal(() -> session.end(null));
		expectIllegal(() -> session.end(Instant.ofEpochSecond(4)));

		LootshareSession overflowing = new LootshareSession("overflow", 1L, Instant.EPOCH);
		assertTrue(overflowing.addAcceptedProposal(accepted("max", 1L, Instant.EPOCH, Long.MAX_VALUE)));
		expectArithmetic(() -> overflowing.addAcceptedProposal(accepted("one-more", 1L, Instant.EPOCH, 1L)));
	}

	private static LootProposal accepted(String id, long partyId, Instant at)
	{
		return accepted(id, partyId, at, 1L);
	}

	private static LootProposal accepted(String id, long partyId, Instant at, long value)
	{
		return LootProposal.pending(partyId, 1L, event(id, value))
			.decide(LootProposalStatus.ACCEPTED, at,
				Collections.singletonList(new LootshareParticipant(1L, "Alice")));
	}

	private static SharedLootEvent event(String id, long value)
	{
		return new SharedLootEvent(id, "Alice", "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(1, 1, 1, value)));
	}

	private static String repeat(char value, int count)
	{
		return String.join("", Collections.nCopies(count, String.valueOf(value)));
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

	private static void expectArithmetic(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected ArithmeticException");
		}
		catch (ArithmeticException expected)
		{
			// Expected.
		}
	}
}
