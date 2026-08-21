/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LootProposal
{
	public static final int MAX_PARTICIPANTS = 128;
	private final long partyId;
	private final long ownerMemberId;
	private final SharedLootEvent event;
	private Instant decidedAt;
	private List<LootshareParticipant> participants = Collections.emptyList();

	private LootProposal(long partyId, long ownerMemberId, SharedLootEvent event)
	{
		if (partyId <= 0L || ownerMemberId <= 0L || event == null)
		{
			throw new IllegalArgumentException("Valid proposal required");
		}
		this.partyId = partyId;
		this.ownerMemberId = ownerMemberId;
		this.event = event;
	}

	public static LootProposal pending(long partyId, long ownerMemberId, SharedLootEvent event)
	{
		return new LootProposal(partyId, ownerMemberId, event);
	}

	public void accept(Instant at, List<LootshareParticipant> roster)
	{
		if (at == null || roster == null || roster.isEmpty() || roster.size() > MAX_PARTICIPANTS)
		{
			throw new IllegalArgumentException("Valid roster required");
		}
		decidedAt = at;
		participants = Collections.unmodifiableList(new ArrayList<>(roster));
	}

	public boolean isAccepted()
	{
		return decidedAt != null;
	}

	public String getProposalId()
	{
		return event.getProposalId();
	}

	public long getPartyId()
	{
		return partyId;
	}

	public long getOwnerMemberId()
	{
		return ownerMemberId;
	}

	public SharedLootEvent getEvent()
	{
		return event;
	}

	public Instant getDecidedAt()
	{
		return decidedAt;
	}

	public List<LootshareParticipant> getParticipants()
	{
		return participants;
	}
}
