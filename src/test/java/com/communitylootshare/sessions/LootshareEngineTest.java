/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.sessions;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import java.time.Instant;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LootshareEngineTest
{
	@Test
	public void persistsOneActiveLedgerAndClearsItOnIntentionalLeave()
	{
		LootshareEngine engine = new LootshareEngine();
		assertEquals(LootshareEngine.MutationResult.APPLIED, engine.enterParty(10L, "session", Instant.EPOCH));
		assertEquals(LootshareEngine.MutationResult.APPLIED, engine.updateHostState(1L, 1L, 100L, 1L));
		LootProposal proposal = LootProposal.pending(10L, 2L, event("drop", 200L));
		assertEquals(LootshareEngine.MutationResult.APPLIED, engine.addProposal(proposal));
		assertEquals(LootshareEngine.MutationResult.APPLIED, engine.decide("drop", 1L, LootProposalStatus.ACCEPTED,
			Instant.EPOCH, Arrays.asList(new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob"))).getResult());

		LootshareEngine restored = new LootshareEngine();
		restored.restore(engine.snapshot());
		assertEquals(10L, restored.getActivePartyId());
		assertEquals(100L, restored.getActiveMinimumSharedLootValue());
		assertEquals(1, restored.getActiveSession().get().getAcceptedProposals().size());
		assertEquals(LootshareEngine.MutationResult.APPLIED, restored.leaveParty(Instant.now()));
		assertFalse(restored.getActiveSession().isPresent());
		assertTrue(restored.snapshot().getProposals().isEmpty());
	}

	@Test
	public void hostUpdatesAreRevisionedAndOnlyHostCanDecide()
	{
		LootshareEngine engine = new LootshareEngine();
		engine.enterParty(10L, "session", Instant.EPOCH);
		assertEquals(LootshareEngine.MutationResult.APPLIED, engine.updateHostState(1L, 1L, 0L, 1L));
		assertEquals(LootshareEngine.MutationResult.NOT_HOST, engine.updateHostState(2L, 2L, 0L, 2L));
		engine.addProposal(LootProposal.pending(10L, 2L, event("drop", 1L)));
		assertEquals(LootshareEngine.MutationResult.NOT_HOST, engine.decide("drop", 2L, LootProposalStatus.REJECTED, Instant.EPOCH, null).getResult());
	}

	private static SharedLootEvent event(String id, long value)
	{
		return new SharedLootEvent(id, "Bob", "NPC", Instant.EPOCH,
			Arrays.asList(new SharedLootItem(1, 1, 1L, value)));
	}
}
