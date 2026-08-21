/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LootshareSession
{
	public static final int MAX_ACCEPTED_PROPOSALS = 4096;

	private final String sessionId;
	private final long partyId;
	private final Instant startedAt;
	private Instant endedAt;
	private final List<LootProposal> acceptedProposals = new ArrayList<>();

	public LootshareSession(String sessionId, long partyId, Instant startedAt)
	{
		if (sessionId == null || sessionId.trim().isEmpty()
			|| sessionId.trim().length() > SharedLootEvent.MAX_PROPOSAL_ID_LENGTH)
		{
			throw new IllegalArgumentException("Session ID must be non-blank and at most "
				+ SharedLootEvent.MAX_PROPOSAL_ID_LENGTH + " characters");
		}
		if (partyId <= 0L || startedAt == null)
		{
			throw new IllegalArgumentException("Session party and start time are required");
		}
		this.sessionId = sessionId.trim();
		this.partyId = partyId;
		this.startedAt = startedAt;
	}

	public boolean addAcceptedProposal(LootProposal proposal)
	{
		if (proposal == null || proposal.getStatus() != LootProposalStatus.ACCEPTED
			|| proposal.getPartyId() != partyId)
		{
			throw new IllegalArgumentException("Session can only contain accepted proposals for its party");
		}
		if (acceptedProposals.stream().anyMatch(existing -> existing.getProposalId().equals(proposal.getProposalId())))
		{
			return false;
		}
		if (acceptedProposals.size() >= MAX_ACCEPTED_PROPOSALS)
		{
			throw new IllegalStateException("Session accepted-proposal limit reached");
		}
		long aggregateValue = 0L;
		for (LootProposal accepted : acceptedProposals)
		{
			aggregateValue = Math.addExact(aggregateValue, accepted.getEvent().getTotal());
		}
		Math.addExact(aggregateValue, proposal.getEvent().getTotal());
		acceptedProposals.add(proposal.validatedCopy());
		acceptedProposals.sort((left, right) -> {
			int byDecision = left.getDecidedAt().compareTo(right.getDecidedAt());
			return byDecision != 0 ? byDecision : left.getProposalId().compareTo(right.getProposalId());
		});
		return true;
	}

	public boolean end(Instant at)
	{
		if (at == null || at.isBefore(startedAt))
		{
			throw new IllegalArgumentException("Session end cannot precede its start");
		}
		if (endedAt != null)
		{
			return false;
		}
		endedAt = at;
		return true;
	}

	public LootshareSession snapshot()
	{
		LootshareSession copy = new LootshareSession(sessionId, partyId, startedAt);
		for (LootProposal proposal : acceptedProposals)
		{
			copy.addAcceptedProposal(proposal);
		}
		if (endedAt != null)
		{
			copy.end(endedAt);
		}
		return copy;
	}

	public String getSessionId()
	{
		return sessionId;
	}

	public long getPartyId()
	{
		return partyId;
	}

	public Instant getStartedAt()
	{
		return startedAt;
	}

	public Instant getEndedAt()
	{
		return endedAt;
	}

	public boolean isActive()
	{
		return endedAt == null;
	}

	public List<LootProposal> getAcceptedProposals()
	{
		return Collections.unmodifiableList(acceptedProposals);
	}
}
