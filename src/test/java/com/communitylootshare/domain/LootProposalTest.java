/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class LootProposalTest
{
	@Test
	public void pendingProposalCanBeAcceptedWithCanonicalRoster()
	{
		LootProposal pending = LootProposal.pending(10L, 2L, event("p1", 100L));
		assertEquals(10L, pending.getPartyId());
		assertEquals(2L, pending.getOwnerMemberId());
		assertEquals("p1", pending.getProposalId());
		assertEquals(LootProposalStatus.PENDING, pending.getStatus());
		assertNull(pending.getDecidedAt());
		assertTrue(pending.getParticipants().isEmpty());

		LootProposal accepted = pending.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(5),
			Arrays.asList(new LootshareParticipant(3L, "Charlie"), new LootshareParticipant(2L, "Alice")));
		assertEquals(LootProposalStatus.ACCEPTED, accepted.getStatus());
		assertEquals(Instant.ofEpochSecond(5), accepted.getDecidedAt());
		assertEquals(2L, accepted.getParticipants().get(0).getMemberId());
		assertEquals(3L, accepted.getParticipants().get(1).getMemberId());
		assertEquals(accepted, accepted.validatedCopy());
		assertTrue(pending.hasSameIdentity(accepted));
		assertFalse(pending.hasSameIdentity(null));
		assertFalse(pending.hasSameIdentity(LootProposal.pending(11L, 2L, event("p1", 100L))));
	}

	@Test
	public void pendingProposalCanBeRejectedWithoutRoster()
	{
		LootProposal rejected = LootProposal.pending(10L, 2L, event("p2", 10L))
			.decide(LootProposalStatus.REJECTED, Instant.ofEpochSecond(7),
				Collections.singletonList(new LootshareParticipant(2L, "Alice")));
		assertEquals(LootProposalStatus.REJECTED, rejected.getStatus());
		assertTrue(rejected.getParticipants().isEmpty());
		assertEquals(rejected, rejected.validatedCopy());
	}

	@Test
	public void participantValidatesIdentityAndName()
	{
		LootshareParticipant participant = new LootshareParticipant(1L, " Alice ");
		assertEquals(1L, participant.getMemberId());
		assertEquals("Alice", participant.getDisplayName());
		expectIllegal(() -> new LootshareParticipant(0L, "Alice"));
		expectIllegal(() -> new LootshareParticipant(1L, null));
		expectIllegal(() -> new LootshareParticipant(1L, " "));
		expectIllegal(() -> new LootshareParticipant(1L, repeat('x', 65)));
	}

	@Test
	public void proposalRejectsInvalidConstructionAndDecisions()
	{
		expectIllegal(() -> LootProposal.pending(0L, 1L, event("p", 1L)));
		expectIllegal(() -> LootProposal.pending(1L, 0L, event("p", 1L)));
		expectIllegal(() -> LootProposal.pending(1L, 1L, null));

		LootProposal pending = LootProposal.pending(1L, 1L, event("p", 1L));
		expectIllegal(() -> pending.decide(null, Instant.EPOCH, Collections.emptyList()));
		expectIllegal(() -> pending.decide(LootProposalStatus.PENDING, Instant.EPOCH, Collections.emptyList()));
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, null,
			Collections.singletonList(new LootshareParticipant(1L, "Alice"))));
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH, null));
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH, Collections.emptyList()));
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH,
			Collections.singletonList(new LootshareParticipant(2L, "Bob"))));
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH,
			Arrays.asList(new LootshareParticipant(1L, "Alice"), new LootshareParticipant(1L, "Alice"))));
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH,
			Arrays.asList(new LootshareParticipant(1L, "Alice"), null)));
		SharedLootEvent laterEvent = new SharedLootEvent("later", "Alice", "Boss", Instant.ofEpochSecond(5),
			Collections.singletonList(new SharedLootItem(1, 1, 1, 1)));
		LootProposal later = LootProposal.pending(1L, 1L, laterEvent);
		expectIllegal(() -> later.decide(LootProposalStatus.REJECTED, Instant.ofEpochSecond(4),
			Collections.emptyList()));

		List<LootshareParticipant> tooMany = new ArrayList<>();
		for (long memberId = 1L; memberId <= LootProposal.MAX_PARTICIPANTS + 1L; memberId++)
		{
			tooMany.add(new LootshareParticipant(memberId, "Member " + memberId));
		}
		expectIllegal(() -> pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH, tooMany));

		LootProposal accepted = pending.decide(LootProposalStatus.ACCEPTED, Instant.EPOCH,
			Collections.singletonList(new LootshareParticipant(1L, "Alice")));
		expectState(() -> accepted.decide(LootProposalStatus.REJECTED, Instant.EPOCH, Collections.emptyList()));
	}

	private static SharedLootEvent event(String id, long value)
	{
		return new SharedLootEvent(id, "Alice", "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(1, 1, 1, value)));
	}

	private static String repeat(char value, int count)
	{
		char[] chars = new char[count];
		Arrays.fill(chars, value);
		return new String(chars);
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

	private static void expectState(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected IllegalStateException");
		}
		catch (IllegalStateException expected)
		{
			// Expected.
		}
	}
}
