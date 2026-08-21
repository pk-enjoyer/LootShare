/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.domain;

import java.time.Instant;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class LootProposalTest
{
	@Test public void acceptsOneFrozenRoster()
	{
		SharedLootEvent event = new SharedLootEvent("drop", "Alice", "NPC", Instant.EPOCH,
			Arrays.asList(new SharedLootItem(995, 995, 100, 1)));
		LootProposal proposal = LootProposal.pending(1L, 2L, event);
		proposal.accept(Instant.EPOCH, Arrays.asList(new LootshareParticipant(2L, "Alice"), new LootshareParticipant(3L, "Bob")));
		Assert.assertTrue(proposal.isAccepted());
		Assert.assertEquals(2, proposal.getParticipants().size());
	}
}
