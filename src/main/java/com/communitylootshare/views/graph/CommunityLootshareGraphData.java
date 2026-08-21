/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.views.graph;

import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.utils.Formats;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapts active Community Lootshare snapshots to the retained graph renderer.
 */
public final class CommunityLootshareGraphData
{
	private static final int MAX_BAR_ENTRIES = 12;

	private CommunityLootshareGraphData()
	{
	}

	public static SessionGraphSnapshot build(LootshareSession session, LootshareCalculation calculation,
	                                         SessionGraphMode mode, Instant now)
	{
		SessionGraphMode selectedMode = mode == null ? SessionGraphMode.GP_PER_HOUR : mode;
		if (session == null || calculation == null)
		{
			return SessionGraphSnapshot.empty(selectedMode);
		}

		Instant start = session.getStartedAt();
		Instant end = session.getEndedAt() == null ? safeNow(now) : session.getEndedAt();
		if (end.isBefore(start))
		{
			end = start;
		}
		long totalLoot = calculation.getTotalAcceptedValue();
		long gpPerHour = hourlyRate(totalLoot, Duration.between(start, end));
		LootshareCalculation.Balance top = calculation.getBalances().stream()
			.filter(balance -> balance.getReceivedValue() > 0L)
			.max(Comparator.comparingLong(LootshareCalculation.Balance::getReceivedValue))
			.orElse(null);
		List<SessionGraphEntry> entries = entriesFor(selectedMode, session, calculation, start, end);

		return new SessionGraphSnapshot(selectedMode, entries, totalLoot, gpPerHour,
			top == null ? "" : top.getDisplayName(), top == null ? 0L : top.getReceivedValue(), start, end);
	}

	private static List<SessionGraphEntry> entriesFor(SessionGraphMode mode, LootshareSession session,
	                                                  LootshareCalculation calculation, Instant start, Instant end)
	{
		Map<Long, MemberApprovalStatus> statuses = session.getMemberApprovalStatuses();
		switch (mode)
		{
			case HIGHEST_EARNINGS:
				return calculation.getBalances().stream()
					.filter(balance -> balance.getReceivedValue() > 0L)
					.sorted(Comparator.comparingLong(LootshareCalculation.Balance::getReceivedValue).reversed())
					.limit(MAX_BAR_ENTRIES)
					.map(balance -> new SessionGraphEntry(balance.getDisplayName(), balance.getReceivedValue(),
						isApproved(statuses, session.getHostMemberId(), balance.getMemberId())))
					.collect(Collectors.toList());
			case SPLIT_BALANCE:
				return calculation.getBalances().stream()
					.filter(balance -> balance.getNetValue() != 0L)
					.sorted(Comparator.comparingLong(
						(LootshareCalculation.Balance balance) -> Math.abs(balance.getNetValue())).reversed())
					.limit(MAX_BAR_ENTRIES)
					.map(balance -> new SessionGraphEntry(balance.getDisplayName(), balance.getNetValue(),
						isApproved(statuses, session.getHostMemberId(), balance.getMemberId())))
					.collect(Collectors.toList());
			case GP_PER_HOUR:
			default:
				return gpPerHourEntries(calculation.getIncludedLoot(), start, end, session.isActive());
		}
	}

	private static List<SessionGraphEntry> gpPerHourEntries(List<LootshareCalculation.IncludedLoot> loot,
	                                                        Instant start, Instant end, boolean active)
	{
		List<SessionGraphEntry> entries = new ArrayList<>();
		long cumulative = 0L;
		Instant lastAt = null;
		for (LootshareCalculation.IncludedLoot included : loot)
		{
			cumulative = Math.addExact(cumulative, included.getValue());
			Instant at = included.getCapturedAt().isBefore(start) ? start : included.getCapturedAt();
			lastAt = at;
			entries.add(new SessionGraphEntry(Formats.getLocalTime().format(at),
				hourlyRate(cumulative, Duration.between(start, at)), true));
		}
		if (active && cumulative > 0L && !end.equals(lastAt))
		{
			entries.add(new SessionGraphEntry(Formats.getLocalTime().format(end),
				hourlyRate(cumulative, Duration.between(start, end)), true));
		}
		return entries;
	}

	private static boolean isApproved(Map<Long, MemberApprovalStatus> statuses, long hostMemberId, long memberId)
	{
		return memberId == hostMemberId || statuses.get(memberId) == MemberApprovalStatus.APPROVED;
	}

	private static Instant safeNow(Instant now)
	{
		return now == null ? Instant.now() : now;
	}

	private static long hourlyRate(long amount, Duration duration)
	{
		long seconds = Math.max(1L, duration == null ? 1L : duration.getSeconds());
		return Math.round((amount * 3600.0d) / seconds);
	}
}
