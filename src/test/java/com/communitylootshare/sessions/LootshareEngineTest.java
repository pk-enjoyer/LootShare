/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.sessions;

import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.sessions.LootshareEngine.DecisionOutcome;
import com.communitylootshare.sessions.LootshareEngine.MutationResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class LootshareEngineTest
{
	@Test
	public void hostControlsVersionedMemberEligibilityAndSnapshotsIt()
	{
		LootshareEngine engine = new LootshareEngine();
		assertEquals(MutationResult.NO_ACTIVE_SESSION,
			engine.updateMemberApprovalStatus(1L, 2L, MemberApprovalStatus.APPROVED, 1L));
		assertEquals(MutationResult.APPLIED, engine.enterParty(10L, "eligibility", Instant.EPOCH));
		assertEquals(MutationResult.APPLIED, engine.updateHostState(1L, 1L, 0L, 1L));
		assertEquals(MemberApprovalStatus.APPROVED, engine.getActiveMemberApprovalStatus(1L));
		assertEquals(MemberApprovalStatus.PENDING, engine.getActiveMemberApprovalStatus(2L));
		assertEquals(MutationResult.NOT_HOST,
			engine.updateMemberApprovalStatus(2L, 2L, MemberApprovalStatus.APPROVED, 2L));
		assertEquals(MutationResult.INVALID,
			engine.updateMemberApprovalStatus(1L, 1L, MemberApprovalStatus.EXCLUDED, 2L));
		assertEquals(MutationResult.APPLIED,
			engine.updateMemberApprovalStatus(1L, 2L, MemberApprovalStatus.APPROVED, 2L));
		assertEquals(MemberApprovalStatus.APPROVED, engine.getActiveMemberApprovalStatus(2L));
		assertEquals(MutationResult.APPLIED,
			engine.updateMemberApprovalStatus(1L, 2L, MemberApprovalStatus.EXCLUDED, 3L));
		assertEquals(MemberApprovalStatus.EXCLUDED, engine.getActiveMemberApprovalStatus(2L));

		LootshareEngine restored = new LootshareEngine();
		restored.restore(engine.snapshot());
		assertEquals(MemberApprovalStatus.APPROVED, restored.getActiveMemberApprovalStatus(1L));
		assertEquals(MemberApprovalStatus.EXCLUDED, restored.getActiveMemberApprovalStatus(2L));
		try
		{
			restored.getActiveMemberApprovalStatuses().clear();
			fail("Expected immutable approval status map");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	@Test
	public void enforcesOwnerApprovalDeduplicationAndFinalDecisionConflicts()
	{
		LootshareEngine engine = new LootshareEngine();
		assertEquals(0L, engine.getActivePartyId());
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.addProposal(pending("p1", 10L, 1L, 100L)));
		assertEquals(MutationResult.INVALID, engine.addProposal(null));
		assertEquals(MutationResult.APPLIED, engine.enterParty(10L, "s1", Instant.EPOCH));
		assertEquals(MutationResult.DUPLICATE, engine.enterParty(10L, "ignored", Instant.ofEpochSecond(1)));
		assertEquals(MutationResult.APPLIED, engine.updateHostState(1L, 1L, 0L, 1L));
		assertEquals(10L, engine.getActivePartyId());

		LootProposal proposal = pending("p1", 10L, 1L, 100L);
		assertEquals(MutationResult.APPLIED, engine.addProposal(proposal));
		assertEquals(MutationResult.DUPLICATE, engine.addProposal(proposal));
		assertEquals(MutationResult.CONFLICT, engine.addProposal(pending("p1", 10L, 1L, 101L)));
		assertEquals(1, engine.getPendingOwnedBy(1L).size());
		assertTrue(engine.getPendingOwnedBy(2L).isEmpty());
		assertEquals(1, engine.getProposalsForActiveParty().size());

		DecisionOutcome missing = engine.decide("missing", 1L, LootProposalStatus.ACCEPTED, Instant.EPOCH,
			roster(1L, 2L));
		assertEquals(MutationResult.NOT_FOUND, missing.getResult());
		assertEquals(null, missing.getProposal());
		assertEquals(MutationResult.NOT_HOST,
			engine.decide("p1", 2L, LootProposalStatus.ACCEPTED, Instant.EPOCH, roster(1L, 2L)).getResult());
		assertEquals(MutationResult.INVALID,
			engine.decide("p1", 1L, LootProposalStatus.PENDING, Instant.EPOCH, roster(1L, 2L)).getResult());

		DecisionOutcome accepted = engine.decide("p1", 1L, LootProposalStatus.ACCEPTED,
			Instant.ofEpochSecond(2), roster(2L, 1L));
		assertEquals(MutationResult.APPLIED, accepted.getResult());
		assertEquals(LootProposalStatus.ACCEPTED, accepted.getProposal().getStatus());
		assertTrue(engine.getPendingOwnedBy(1L).isEmpty());
		assertEquals(1, engine.getActiveSession().get().getAcceptedProposals().size());
		assertEquals(MutationResult.DUPLICATE,
			engine.decide("p1", 1L, LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(3), roster(1L, 2L)).getResult());
		assertEquals(MutationResult.CONFLICT,
			engine.decide("p1", 1L, LootProposalStatus.REJECTED, Instant.ofEpochSecond(3), Collections.emptyList()).getResult());

		LootProposal alreadyAccepted = pending("p2", 10L, 1L, 20L).decide(LootProposalStatus.ACCEPTED,
			Instant.ofEpochSecond(4), roster(1L));
		assertEquals(MutationResult.INVALID, engine.addProposal(alreadyAccepted));
	}

	@Test
	public void rejectsAndPersistsProposalsWhileSessionsFollowPartyLifecycle()
	{
		LootshareEngine engine = new LootshareEngine();
		assertEquals(MutationResult.DUPLICATE, engine.leaveParty(Instant.EPOCH));
		assertEquals(MutationResult.APPLIED, engine.enterParty(10L, "first", Instant.EPOCH));
		assertEquals(MutationResult.APPLIED, engine.updateHostState(1L, 1L, 0L, 1L));
		assertEquals(MutationResult.APPLIED, engine.addProposal(pending("reject", 10L, 1L, 50L)));
		assertEquals(MutationResult.APPLIED,
			engine.decide("reject", 1L, LootProposalStatus.REJECTED, Instant.ofEpochSecond(1), null).getResult());
		assertEquals(LootProposalStatus.REJECTED, engine.getProposal("reject").get().getStatus());
		assertEquals(MutationResult.APPLIED, engine.addProposal(pending("pending", 10L, 1L, 60L)));

		assertEquals(MutationResult.APPLIED, engine.enterParty(20L, "second", Instant.ofEpochSecond(2)));
		assertEquals(20L, engine.getActivePartyId());
		assertEquals(2, engine.getHistory().size());
		assertFalse(engine.getHistory().get(0).isActive());
		assertTrue(engine.getHistory().get(1).isActive());
		assertEquals(MutationResult.NO_ACTIVE_SESSION,
			engine.decide("pending", 1L, LootProposalStatus.REJECTED, Instant.ofEpochSecond(3), null).getResult());
		assertEquals(MutationResult.APPLIED, engine.leaveParty(Instant.ofEpochSecond(4)));
		assertFalse(engine.getActiveSession().isPresent());
		assertEquals(0L, engine.getActivePartyId());
		assertTrue(engine.getProposalsForActiveParty().isEmpty());
		assertTrue(engine.getPendingOwnedBy(1L).isEmpty());
	}

	@Test
	public void invalidSessionTimesDoNotCorruptTheActiveSession()
	{
		LootshareEngine engine = new LootshareEngine();
		assertEquals(MutationResult.INVALID, engine.enterParty(0L, "session", Instant.EPOCH));
		assertEquals(MutationResult.INVALID, engine.enterParty(1L, null, Instant.EPOCH));
		assertEquals(MutationResult.INVALID, engine.enterParty(1L, "session", null));
		assertEquals(MutationResult.APPLIED,
			engine.enterParty(1L, "session", Instant.ofEpochSecond(5)));
		assertEquals(MutationResult.INVALID, engine.leaveParty(Instant.ofEpochSecond(4)));
		assertEquals(1L, engine.getActivePartyId());
		assertEquals(MutationResult.INVALID,
			engine.enterParty(2L, "replacement", Instant.ofEpochSecond(4)));
		assertEquals(1L, engine.getActivePartyId());
		assertEquals(MutationResult.APPLIED, engine.leaveParty(Instant.ofEpochSecond(6)));
	}

	@Test
	public void enforcesVersionedHostAuthorityTransfersAndVacancyRecovery()
	{
		LootshareEngine engine = new LootshareEngine();
		assertEquals(0L, engine.getActiveHostMemberId());
		assertEquals(0L, engine.getActiveMinimumSharedLootValue());
		assertEquals(0L, engine.getActiveHostRevision());
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.updateHostState(1L, 1L, 100L, 1L));
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.vacateHost(1L));

		engine.enterParty(10L, "host", Instant.EPOCH);
		assertEquals(MutationResult.INVALID, engine.updateHostState(0L, 1L, 100L, 1L));
		assertEquals(MutationResult.INVALID, engine.updateHostState(1L, 1L, -1L, 1L));
		assertEquals(MutationResult.NOT_HOST, engine.updateHostState(1L, 2L, 100L, 1L));
		assertEquals(MutationResult.APPLIED, engine.updateHostState(1L, 1L, 100L, 1L));
		assertEquals(1L, engine.getActiveHostMemberId());
		assertEquals(100L, engine.getActiveMinimumSharedLootValue());
		assertEquals(1L, engine.getActiveHostRevision());
		assertEquals(MutationResult.DUPLICATE, engine.updateHostState(1L, 1L, 100L, 1L));
		assertEquals(MutationResult.CONFLICT, engine.updateHostState(1L, 1L, 101L, 1L));
		assertEquals(MutationResult.NOT_HOST, engine.updateHostState(2L, 2L, 200L, 2L));
		LootshareSettings transferredSettings = new LootshareSettings(200L, LootValueBasis.HIGH_ALCHEMY,
			false, true, false, true, false, true);
		assertEquals(MutationResult.INVALID, engine.updateHostState(1L, 2L, (LootshareSettings) null, 2L));
		assertEquals(MutationResult.APPLIED, engine.updateHostState(1L, 2L, transferredSettings, 2L));
		assertEquals(2L, engine.getActiveHostMemberId());
		assertEquals(transferredSettings, engine.getActiveHostSettings().get());
		assertEquals(MutationResult.DUPLICATE, engine.updateHostState(2L, 2L, transferredSettings, 2L));
		assertEquals(MutationResult.CONFLICT, engine.updateHostState(2L, 2L, transferredSettings, 1L));

		assertEquals(MutationResult.INVALID, engine.vacateHost(0L));
		assertEquals(MutationResult.NOT_HOST, engine.vacateHost(1L));
		assertEquals(MutationResult.APPLIED, engine.vacateHost(2L));
		assertEquals(0L, engine.getActiveHostMemberId());
		assertTrue(!engine.getActiveHostSettings().isPresent());
		assertEquals(MutationResult.APPLIED, engine.updateHostState(2L, 2L, transferredSettings, 2L));
		assertEquals(MutationResult.APPLIED, engine.vacateHost(2L));
		assertEquals(MutationResult.DUPLICATE, engine.vacateHost(2L));
	}

	@Test
	public void sessionHostStateValidatesAndSurvivesSnapshots()
	{
		LootshareSession session = new LootshareSession("host-state", 10L, Instant.EPOCH);
		session.setHostState(4L, 500L, 6L);
		LootshareSession snapshot = session.snapshot();
		assertEquals(4L, snapshot.getHostMemberId());
		assertEquals(500L, snapshot.getMinimumSharedLootValue());
		assertEquals(6L, snapshot.getHostRevision());
		snapshot.clearHost();
		assertEquals(0L, snapshot.getHostMemberId());

		expectIllegal(() -> session.setHostState(-1L, 0L, 1L));
		expectIllegal(() -> session.setHostState(1L, -1L, 1L));
		expectIllegal(() -> session.setHostState(1L,
			LootshareSession.MAXIMUM_SHARED_LOOT_VALUE + 1L, 1L));
		expectIllegal(() -> session.setHostState(1L, 0L, 0L));
		expectIllegal(() -> session.setHostState(0L, 0L, -1L));
	}

	@Test
	public void snapshotRestorePreservesValidatedActiveStateAndHandlesNullCollections()
	{
		LootshareEngine source = new LootshareEngine();
		source.enterParty(10L, "s", Instant.EPOCH);
		source.updateHostState(1L, 1L, 100_000L, 1L);
		source.addProposal(pending("p", 10L, 1L, 100L));
		source.decide("p", 1L, LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1), roster(1L, 2L));

		LootshareState snapshot = source.snapshot();
		assertEquals(LootshareState.CURRENT_SCHEMA_VERSION, snapshot.getSchemaVersion());
		assertEquals("s", snapshot.getActiveSessionId());
		assertEquals(1, snapshot.getProposals().size());
		assertEquals(1, snapshot.getSessions().size());

		LootshareEngine restored = new LootshareEngine();
		restored.restore(snapshot);
		assertEquals(10L, restored.getActivePartyId());
		assertEquals(LootProposalStatus.ACCEPTED, restored.getProposal("p").get().getStatus());
		assertEquals(1, restored.getActiveSession().get().getAcceptedProposals().size());
		assertEquals(1L, restored.getActiveHostMemberId());
		assertEquals(100_000L, restored.getActiveMinimumSharedLootValue());
		assertEquals(1L, restored.getActiveHostRevision());

		LootshareState malformed = new LootshareState();
		malformed.setSchemaVersion(7);
		malformed.setProposals(Arrays.asList(null, pending("valid", 10L, 1L, 1L)));
		malformed.setSessions(Arrays.asList(null, new LootshareSession("valid-session", 10L, Instant.EPOCH)));
		malformed.setActiveSessionId("valid-session");
		restored.restore(malformed);
		assertTrue(restored.getProposal("valid").isPresent());
		assertTrue(restored.getActiveSession().isPresent());

		malformed.setProposals(null);
		malformed.setSessions(null);
		assertTrue(malformed.getProposals().isEmpty());
		assertTrue(malformed.getSessions().isEmpty());
		restored.restore(null);
		assertTrue(restored.getHistory().isEmpty());
	}

	@Test
	public void proposalAndSessionLimitsAreBounded()
	{
		LootshareEngine engine = new LootshareEngine();
		engine.enterParty(10L, "s", Instant.EPOCH);
		for (int index = 0; index < LootshareEngine.MAX_PROPOSALS; index++)
		{
			assertEquals(MutationResult.APPLIED,
				engine.addProposal(pending("p" + index, 10L, 1L, 1L)));
		}
		assertEquals(MutationResult.LIMIT_REACHED,
			engine.addProposal(pending("overflow", 10L, 1L, 1L)));

		LootshareState allActive = new LootshareState();
		List<LootshareSession> sessions = new ArrayList<>();
		for (int index = 0; index < LootshareEngine.MAX_SESSIONS; index++)
		{
			sessions.add(new LootshareSession("s" + index, index + 1L, Instant.EPOCH));
		}
		allActive.setSessions(sessions);
		engine.restore(allActive);
		assertEquals(MutationResult.LIMIT_REACHED,
			engine.enterParty(999L, "new", Instant.ofEpochSecond(1)));

		LootshareEngine pruning = new LootshareEngine();
		for (int index = 0; index < LootshareEngine.MAX_SESSIONS; index++)
		{
			pruning.enterParty(index + 1L, "s" + index, Instant.ofEpochSecond(index));
		}
		assertEquals(MutationResult.APPLIED,
			pruning.enterParty(999L, "replacement", Instant.ofEpochSecond(999)));
		assertEquals(LootshareEngine.MAX_SESSIONS, pruning.getHistory().size());
	}

	private static LootProposal pending(String id, long partyId, long ownerId, long value)
	{
		return LootProposal.pending(partyId, ownerId,
			new SharedLootEvent(id, ownerId == 1L ? "Alice" : "Owner", "Boss", Instant.EPOCH,
				Collections.singletonList(new SharedLootItem(1, 1, 1, value))));
	}

	private static List<LootshareParticipant> roster(long... memberIds)
	{
		List<LootshareParticipant> participants = new ArrayList<>();
		for (long memberId : memberIds)
		{
			participants.add(new LootshareParticipant(memberId, memberId == 1L ? "Alice" : "Member " + memberId));
		}
		return participants;
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
