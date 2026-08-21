/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
		LootshareSettings settings = new LootshareSettings(250L, LootValueBasis.HIGH_ALCHEMY,
			true, false, true, false, true, true);
		Map<Long, MemberApprovalStatus> approvals = new LinkedHashMap<>();
		approvals.put(5L, MemberApprovalStatus.EXCLUDED);
		session.setHostState(4L, settings, 2L, approvals);
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
		assertEquals(4L, snapshot.getHostMemberId());
		assertEquals(250L, snapshot.getMinimumSharedLootValue());
		assertEquals(settings, snapshot.getHostSettings());
		assertEquals(2L, snapshot.getHostRevision());
		assertEquals(MemberApprovalStatus.APPROVED, snapshot.getMemberApprovalStatus(4L));
		assertEquals(MemberApprovalStatus.EXCLUDED, snapshot.getMemberApprovalStatus(5L));
		assertEquals(MemberApprovalStatus.PENDING, snapshot.getMemberApprovalStatus(6L));
		session.clearHost();
		assertEquals(0L, session.getHostMemberId());
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
		expectIllegal(() -> session.setHostState(-1L, LootshareSettings.defaults(), 1L));
		expectIllegal(() -> session.setHostState(1L, (LootshareSettings) null, 1L));
		expectIllegal(() -> session.setHostState(1L, LootshareSettings.defaults(), 0L));
		expectIllegal(() -> session.setHostState(1L, LootshareSettings.defaults(), 1L, null));
		expectIllegal(() -> session.getMemberApprovalStatus(0L));
		Map<Long, MemberApprovalStatus> tooManyApprovals = new LinkedHashMap<>();
		for (long memberId = 1L; memberId <= LootshareSession.MAX_MEMBER_APPROVALS + 1L; memberId++)
		{
			tooManyApprovals.put(memberId, MemberApprovalStatus.APPROVED);
		}
		expectIllegal(() -> session.setHostState(1L, LootshareSettings.defaults(), 1L, tooManyApprovals));
		Map<Long, MemberApprovalStatus> fullWithoutHost = new LinkedHashMap<>();
		for (long memberId = 2L; memberId <= LootshareSession.MAX_MEMBER_APPROVALS + 1L; memberId++)
		{
			fullWithoutHost.put(memberId, MemberApprovalStatus.APPROVED);
		}
		expectIllegal(() -> session.setHostState(1L, LootshareSettings.defaults(), 1L, fullWithoutHost));
		expectIllegal(() -> session.setHostState(1L, -1L, 1L));
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
