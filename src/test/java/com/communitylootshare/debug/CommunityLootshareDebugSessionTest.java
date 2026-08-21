/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.debug;

import com.communitylootshare.debug.CommunityLootshareDebugSession.LootPreset;
import com.communitylootshare.debug.CommunityLootshareDebugSession.Snapshot;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.sessions.CommunityLootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.util.Optional;
import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class CommunityLootshareDebugSessionTest
{
	private CommunityLootshareDebugSession session;

	@Before
	public void setUp()
	{
		session = new CommunityLootshareDebugSession(true, new LootshareCalculator());
	}

	@Test
	public void refusesToStartOutsideRuneLiteDeveloperMode()
	{
		CommunityLootshareDebugSession production =
			new CommunityLootshareDebugSession(false, new LootshareCalculator());

		assertFalse(production.isAvailable());
		production.startSimulation();
		assertFalse(production.isActive());
		assertFalse(production.snapshot().isActive());
	}

	@Test
	public void keepsSimulationInactiveAndEmptyUntilStarted()
	{
		Snapshot snapshot = session.snapshot();

		assertFalse(snapshot.isActive());
		assertEquals(0L, snapshot.getPartyId());
		assertEquals(0L, snapshot.getOwnerMemberId());
		assertTrue(snapshot.getParticipants().isEmpty());
		assertTrue(snapshot.getProposals().isEmpty());
		assertEquals(0L, snapshot.getCalculation().getTotalAcceptedValue());
		assertFalse(session.addFakePlayer("Alice").isPresent());
		assertFalse(session.removeFakePlayer(123L));
		assertFalse(session.addSampleProposal(100L).isPresent());
		assertEquals(MutationResult.NO_ACTIVE_SESSION, session.approveProposal("missing"));
		assertEquals(MutationResult.NO_ACTIVE_SESSION, session.rejectProposal("missing"));
	}

	@Test
	public void startsWithAnIsolatedOwnerAndSupportsFakePlayerLifecycle()
	{
		assertTrue(session.isAvailable());
		session.startSimulation();
		session.startSimulation();

		Snapshot started = session.snapshot();
		assertTrue(session.isActive());
		assertTrue(started.isActive());
		assertEquals(CommunityLootshareDebugSession.DEBUG_PARTY_ID, started.getPartyId());
		assertEquals(CommunityLootshareDebugSession.DEBUG_OWNER_MEMBER_ID, started.getOwnerMemberId());
		assertEquals(1, started.getParticipants().size());
		assertEquals(CommunityLootshareDebugSession.DEBUG_OWNER_NAME,
			started.getParticipants().get(0).getDisplayName());

		Optional<LootshareParticipant> alice = session.addFakePlayer("  Alice  ");
		Optional<LootshareParticipant> bob = session.addFakePlayer("Bob");
		assertTrue(alice.isPresent());
		assertTrue(bob.isPresent());
		assertNotEquals(alice.get().getMemberId(), bob.get().getMemberId());
		assertEquals("Alice", alice.get().getDisplayName());
		assertFalse(session.addFakePlayer("alice").isPresent());
		assertFalse(session.addFakePlayer(" ").isPresent());
		assertFalse(session.addFakePlayer(null).isPresent());
		assertFalse(session.addFakePlayer(repeat('x', LootshareParticipant.MAX_DISPLAY_NAME_LENGTH + 1)).isPresent());
		assertFalse(session.removeFakePlayer(CommunityLootshareDebugSession.DEBUG_OWNER_MEMBER_ID));
		assertFalse(session.removeFakePlayer(123L));
		assertTrue(session.removeFakePlayer(alice.get().getMemberId()));
		assertEquals(2, session.snapshot().getParticipants().size());

		session.stopSimulation();
		assertFalse(session.isActive());
		assertTrue(session.snapshot().getParticipants().isEmpty());
	}

	@Test
	public void capsTheDebugRosterAtTheProductionParticipantLimit()
	{
		session.startSimulation();
		for (int index = 1; index < LootProposal.MAX_PARTICIPANTS; index++)
		{
			assertTrue(session.addFakePlayer("Player " + index).isPresent());
		}

		assertEquals(LootProposal.MAX_PARTICIPANTS, session.snapshot().getParticipants().size());
		assertFalse(session.addFakePlayer("One too many").isPresent());
	}

	@Test
	public void debugMemberEligibilityOnlyAppliesToFutureSampleLoot()
	{
		session.startSimulation();
		LootshareParticipant bob = session.addFakePlayer("Bob").get();
		LootProposal pendingMemberDrop = session.addSampleProposal(bob.getMemberId(), 100L).get();
		assertEquals(LootProposalStatus.REJECTED, pendingMemberDrop.getStatus());
		assertEquals(0L, session.snapshot().getCalculation().getTotalAcceptedValue());

		assertEquals(MutationResult.APPLIED, session.setMemberApproved(bob.getMemberId(), true));
		LootProposal approvedDrop = session.addSampleProposal(bob.getMemberId(), 200L).get();
		assertEquals(LootProposalStatus.ACCEPTED, approvedDrop.getStatus());
		assertEquals(200L, session.snapshot().getCalculation().getTotalAcceptedValue());

		assertEquals(MutationResult.APPLIED, session.setMemberApproved(bob.getMemberId(), false));
		LootProposal excludedDrop = session.addSampleProposal(bob.getMemberId(), 300L).get();
		assertEquals(LootProposalStatus.REJECTED, excludedDrop.getStatus());
		assertEquals(200L, session.snapshot().getCalculation().getTotalAcceptedValue());
	}

	@Test
	public void createsAndDecidesSampleProposalsAgainstTheCurrentFakeRoster()
	{
		session.startSimulation();
		LootshareParticipant bob = session.addFakePlayer("Bob").get();
		assertEquals(MutationResult.APPLIED, session.setMemberApproved(bob.getMemberId(), true));
		LootProposal first = session.addSampleProposal(bob.getMemberId(), 300L).get();

		assertEquals(LootProposalStatus.ACCEPTED, first.getStatus());
		assertEquals(bob.getMemberId(), first.getOwnerMemberId());
		assertEquals("Bob", first.getEvent().getRecipient());
		SharedLootItem coins = first.getEvent().getItems().get(0);
		assertFalse(LootPreset.COINS.usesItemPrice());
		assertEquals(ItemID.COINS, coins.getItemId());
		assertEquals(300L, coins.getQuantity());
		assertEquals(1L, coins.getUnitPrice());
		assertEquals(MutationResult.DUPLICATE, session.approveProposal(first.getProposalId()));
		assertEquals(MutationResult.CONFLICT, session.rejectProposal(first.getProposalId()));

		assertTrue(session.removeFakePlayer(bob.getMemberId()));
		LootshareParticipant carol = session.addFakePlayer("Carol").get();
		assertEquals(MutationResult.APPLIED, session.setMemberApproved(carol.getMemberId(), true));
		LootProposal second = session.addSampleProposal(90L).get();
		assertEquals(LootProposalStatus.ACCEPTED, second.getStatus());
		assertEquals(MutationResult.DUPLICATE, session.approveProposal(second.getProposalId()));

		Snapshot snapshot = session.snapshot();
		assertEquals(2, snapshot.getProposals().size());
		assertEquals(LootProposalStatus.ACCEPTED, snapshot.getProposals().get(0).getStatus());
		assertEquals(LootProposalStatus.ACCEPTED, snapshot.getProposals().get(1).getStatus());
		LootshareCalculation calculation = snapshot.getCalculation();
		assertEquals(390L, calculation.getTotalAcceptedValue());
		assertEquals(3, calculation.getBalances().size());
		assertEquals(2, calculation.getTransfers().size());
		assertEquals(-150L, balanceFor(calculation, bob.getMemberId()).getNetValue());
		assertEquals(45L, balanceFor(calculation, carol.getMemberId()).getNetValue());
		assertEquals(105L,
			balanceFor(calculation, CommunityLootshareDebugSession.DEBUG_OWNER_MEMBER_ID).getNetValue());
	}

	@Test
	public void createsSelectableToaRewardsAsSingleItemSnapshots()
	{
		session.startSimulation();
		LootPreset[] toaRewards = {
			LootPreset.OSMUMTENS_FANG,
			LootPreset.LIGHTBEARER,
			LootPreset.MASORI_BODY,
			LootPreset.TUMEKENS_SHADOW
		};

		for (int index = 0; index < toaRewards.length; index++)
		{
			LootPreset preset = toaRewards[index];
			long capturedValue = 10_000_000L + index;
			LootProposal proposal = session.addSampleProposal(
				CommunityLootshareDebugSession.DEBUG_OWNER_MEMBER_ID, preset, capturedValue).get();
			SharedLootItem item = proposal.getEvent().getItems().get(0);

			assertTrue(preset.usesItemPrice());
			assertEquals("Tombs of Amascut (debug)", proposal.getEvent().getSourceLabel());
			assertEquals(preset.getItemId(), item.getItemId());
			assertEquals(preset.getItemId(), item.getPricingId());
			assertEquals(1L, item.getQuantity());
			assertEquals(capturedValue, item.getUnitPrice());
			assertEquals(capturedValue, item.getLineTotal());
		}
	}

	@Test
	public void rejectsInvalidSamplesAndCanResetAnActiveScenario()
	{
		session.startSimulation();
		assertFalse(session.addSampleProposal(0L).isPresent());
		assertFalse(session.addSampleProposal(-1L).isPresent());
		assertFalse(session.addSampleProposal(CommunityLootshareDebugSession.MAX_SAMPLE_VALUE + 1L).isPresent());
		assertFalse(session.addSampleProposal(123L, 1L).isPresent());
		assertFalse(session.addSampleProposal(
			CommunityLootshareDebugSession.DEBUG_OWNER_MEMBER_ID, null, 1L).isPresent());
		assertEquals(MutationResult.NOT_FOUND, session.approveProposal("missing"));
		assertEquals(MutationResult.NOT_FOUND, session.rejectProposal("missing"));

		LootshareParticipant excluded = session.addFakePlayer("Excluded").get();
		assertEquals(MutationResult.APPLIED, session.setMemberApproved(excluded.getMemberId(), false));
		LootProposal rejected = session.addSampleProposal(excluded.getMemberId(), 250L).get();
		assertEquals(MutationResult.DUPLICATE, session.rejectProposal(rejected.getProposalId()));
		assertEquals(LootProposalStatus.REJECTED, session.snapshot().getProposals().get(0).getStatus());
		assertEquals(0L, session.snapshot().getCalculation().getTotalAcceptedValue());

		session.addFakePlayer("Alice");
		session.resetSimulation();
		Snapshot reset = session.snapshot();
		assertTrue(reset.isActive());
		assertEquals(1, reset.getParticipants().size());
		assertTrue(reset.getProposals().isEmpty());
		assertEquals("debug-proposal-1", session.addSampleProposal(1L).get().getProposalId());

		session.stopSimulation();
		session.resetSimulation();
		assertFalse(session.isActive());
	}

	@Test
	public void keepsTheFrozenRosterWhenAnApprovedOwnerIsRemoved()
	{
		session.startSimulation();
		LootshareParticipant alice = session.addFakePlayer("Alice").get();
		assertEquals(MutationResult.APPLIED, session.setMemberApproved(alice.getMemberId(), true));
		LootProposal proposal = session.addSampleProposal(alice.getMemberId(), 100L).get();

		assertEquals(LootProposalStatus.ACCEPTED, proposal.getStatus());
		assertTrue(session.removeFakePlayer(alice.getMemberId()));
		assertEquals(MutationResult.DUPLICATE, session.approveProposal(proposal.getProposalId()));
		assertEquals(2, session.snapshot().getCalculation().getBalances().size());
	}

	private static LootshareCalculation.Balance balanceFor(LootshareCalculation calculation, long memberId)
	{
		return calculation.getBalances().stream()
			.filter(balance -> balance.getMemberId() == memberId)
			.findFirst()
			.orElseThrow(AssertionError::new);
	}

	private static String repeat(char value, int count)
	{
		StringBuilder builder = new StringBuilder(count);
		for (int index = 0; index < count; index++)
		{
			builder.append(value);
		}
		return builder.toString();
	}
}
