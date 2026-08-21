/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class LootProposal
{
	public static final int MAX_PARTICIPANTS = 64; // TODO Is this equal to the party hub plugin?

	private final long partyId;
	private final long ownerMemberId;
	private final SharedLootEvent event;
	private final LootProposalStatus status;
	private final Instant decidedAt;
	private final List<LootshareParticipant> participants;

	private LootProposal(long partyId, long ownerMemberId, SharedLootEvent event, LootProposalStatus status,
	                     Instant decidedAt, List<LootshareParticipant> participants)
	{
		if (partyId <= 0L || ownerMemberId <= 0L || event == null || status == null)
		{
			throw new IllegalArgumentException("Proposal party, owner, event, and status are required");
		}

		List<LootshareParticipant> participantCopy = validateParticipants(participants);
		if (status == LootProposalStatus.PENDING)
		{
			if (decidedAt != null || !participantCopy.isEmpty())
			{
				throw new IllegalArgumentException("Pending proposals cannot contain decision data");
			}
		}
		else if (decidedAt == null || decidedAt.isBefore(event.getCapturedAt()))
		{
			throw new IllegalArgumentException("Final proposals require a decision time at or after capture");
		}

		if (status == LootProposalStatus.ACCEPTED)
		{
			if (participantCopy.isEmpty()
				|| participantCopy.stream().noneMatch(participant -> participant.getMemberId() == ownerMemberId))
			{
				throw new IllegalArgumentException("Accepted proposals must include their owner in the split roster");
			}
		}
		else if (!participantCopy.isEmpty())
		{
			throw new IllegalArgumentException("Only accepted proposals may contain a split roster");
		}

		this.partyId = partyId;
		this.ownerMemberId = ownerMemberId;
		this.event = event;
		this.status = status;
		this.decidedAt = decidedAt;
		this.participants = Collections.unmodifiableList(participantCopy);
	}

	public static LootProposal pending(long partyId, long ownerMemberId, SharedLootEvent event)
	{
		return new LootProposal(partyId, ownerMemberId, event, LootProposalStatus.PENDING, null,
			Collections.emptyList());
	}

	private static List<LootshareParticipant> validateParticipants(List<LootshareParticipant> participants)
	{
		if (participants == null)
		{
			throw new IllegalArgumentException("Split roster is required");
		}
		if (participants.size() > MAX_PARTICIPANTS)
		{
			throw new IllegalArgumentException("Split roster cannot exceed " + MAX_PARTICIPANTS + " members");
		}

		Set<Long> seenMemberIds = new HashSet<>();
		List<LootshareParticipant> copy = new ArrayList<>(participants.size());
		for (LootshareParticipant participant : participants)
		{
			if (participant == null || !seenMemberIds.add(participant.getMemberId()))
			{
				throw new IllegalArgumentException("Split roster members must be non-null and unique");
			}
			copy.add(new LootshareParticipant(participant.getMemberId(), participant.getDisplayName()));
		}
		copy.sort(Comparator.comparingLong(LootshareParticipant::getMemberId));
		return copy;
	}

	public LootProposal decide(LootProposalStatus decision, Instant at, List<LootshareParticipant> splitRoster)
	{
		if (status != LootProposalStatus.PENDING)
		{
			throw new IllegalStateException("Proposal has already been decided");
		}
		if (decision == null || decision == LootProposalStatus.PENDING)
		{
			throw new IllegalArgumentException("Decision must accept or reject the proposal");
		}
		List<LootshareParticipant> roster = decision == LootProposalStatus.ACCEPTED
			? splitRoster
			: Collections.emptyList();
		return new LootProposal(partyId, ownerMemberId, event, decision, at, roster);
	}

	public LootProposal validatedCopy()
	{
		LootProposal copy = pending(partyId, ownerMemberId,
			new SharedLootEvent(event.getProposalId(), event.getRecipient(), event.getSourceLabel(),
				event.getCapturedAt(), event.getItems()));
		if (status == LootProposalStatus.PENDING)
		{
			return copy;
		}
		return copy.decide(status, decidedAt, participants);
	}

	public boolean hasSameIdentity(LootProposal other)
	{
		return other != null
			&& partyId == other.partyId
			&& ownerMemberId == other.ownerMemberId
			&& event.equals(other.event);
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

	public String getProposalId()
	{
		return event.getProposalId();
	}

	public LootProposalStatus getStatus()
	{
		return status;
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
