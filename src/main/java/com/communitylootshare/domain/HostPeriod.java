/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.time.Instant;
import lombok.EqualsAndHashCode;

/** Shared identity of one host's tenure, independent of the cumulative Party ledger. */
@EqualsAndHashCode
public final class HostPeriod
{
	private final String periodId;
	private final long hostMemberId;
	private final String hostDisplayName;
	private final String predecessorId;
	private final Instant startedAt;
	private final Instant endedAt;

	public HostPeriod(String periodId, long hostMemberId, String hostDisplayName,
	                  String predecessorId, Instant startedAt, Instant endedAt)
	{
		validateId(periodId);
		if (predecessorId != null) { validateId(predecessorId); }
		LootshareParticipant host = new LootshareParticipant(hostMemberId, hostDisplayName);
		if (startedAt == null || (endedAt != null && endedAt.isBefore(startedAt))
			|| periodId.equals(predecessorId))
		{
			throw new IllegalArgumentException("Invalid host period chronology");
		}
		this.periodId = periodId;
		this.hostMemberId = host.getMemberId();
		this.hostDisplayName = host.getDisplayName();
		this.predecessorId = predecessorId;
		this.startedAt = Instant.ofEpochMilli(startedAt.toEpochMilli());
		this.endedAt = endedAt == null ? null : Instant.ofEpochMilli(endedAt.toEpochMilli());
	}

	public static void validateId(String id)
	{
		if (id == null || id.isEmpty() || id.length() > 128 || !id.matches("[A-Za-z0-9:_-]+"))
		{
			throw new IllegalArgumentException("Invalid history identifier");
		}
	}
	public HostPeriod validatedCopy()
	{
		return new HostPeriod(periodId, hostMemberId, hostDisplayName, predecessorId, startedAt, endedAt);
	}
	public HostPeriod end(Instant at)
	{
		return new HostPeriod(periodId, hostMemberId, hostDisplayName, predecessorId, startedAt, at);
	}
	public String getPeriodId() { return periodId; }
	public long getHostMemberId() { return hostMemberId; }
	public String getHostDisplayName() { return hostDisplayName; }
	public String getPredecessorId() { return predecessorId; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getEndedAt() { return endedAt; }
}
