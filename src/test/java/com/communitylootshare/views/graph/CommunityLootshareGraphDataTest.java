/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.views.graph;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.sessions.LootshareCalculator;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CommunityLootshareGraphDataTest
{
	@Test
	public void buildsAllGraphModesFromFrozenAcceptedLoot()
	{
		LootshareSession session = new LootshareSession("graphs", 10L, Instant.EPOCH);
		Map<Long, MemberApprovalStatus> statuses = new LinkedHashMap<>();
		statuses.put(2L, MemberApprovalStatus.EXCLUDED);
		session.setHostState(1L, LootshareSettings.defaults(0L), 2L, statuses);
		session.addAcceptedProposal(accepted("alice", 1L, "Alice", 3_600L, 3_600L));
		session.addAcceptedProposal(accepted("bob", 2L, "Bob", 7_200L, 7_200L));
		LootshareCalculation calculation = new LootshareCalculator().calculate(session);
		Instant now = Instant.ofEpochSecond(10_800L);

		SessionGraphSnapshot rate = CommunityLootshareGraphData.build(
			session, calculation, SessionGraphMode.GP_PER_HOUR, now);
		assertEquals(10_800L, rate.getTotalLoot());
		assertEquals(3_600L, rate.getGpPerHour());
		assertEquals("Bob", rate.getTopPlayer());
		assertEquals(7_200L, rate.getTopPlayerTotal());
		assertEquals(3, rate.getEntries().size());
		assertEquals(3_600L, rate.getEntries().get(0).getValue());
		assertEquals(5_400L, rate.getEntries().get(1).getValue());
		assertEquals(3_600L, rate.getEntries().get(2).getValue());

		SessionGraphSnapshot earnings = CommunityLootshareGraphData.build(
			session, calculation, SessionGraphMode.HIGHEST_EARNINGS, now);
		assertEquals("Bob", earnings.getEntries().get(0).getLabel());
		assertEquals(7_200L, earnings.getEntries().get(0).getValue());
		assertFalse(earnings.getEntries().get(0).isActive());
		assertEquals("Alice", earnings.getEntries().get(1).getLabel());
		assertTrue(earnings.getEntries().get(1).isActive());

		SessionGraphSnapshot balance = CommunityLootshareGraphData.build(
			session, calculation, SessionGraphMode.SPLIT_BALANCE, now);
		assertEquals(2, balance.getEntries().size());
		assertEquals(1_800L, balance.getEntries().get(0).getValue());
		assertEquals(-1_800L, balance.getEntries().get(1).getValue());
	}

	@Test
	public void returnsAnEmptySnapshotWithoutAnActiveSession()
	{
		SessionGraphSnapshot snapshot = CommunityLootshareGraphData.build(
			null, LootshareCalculation.empty(), SessionGraphMode.SPLIT_BALANCE, Instant.EPOCH);
		assertTrue(snapshot.isEmpty());
		assertEquals(SessionGraphMode.SPLIT_BALANCE, snapshot.getMode());
	}

	private static LootProposal accepted(String id, long ownerId, String ownerName, long value, long seconds)
	{
		Instant at = Instant.ofEpochSecond(seconds);
		SharedLootEvent event = new SharedLootEvent(id, ownerName, "Boss", at,
			Arrays.asList(new SharedLootItem(1, 1, 1L, value)));
		return LootProposal.pending(10L, ownerId, event).decide(LootProposalStatus.ACCEPTED, at,
			Arrays.asList(new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
	}
}
