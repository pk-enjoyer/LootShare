/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.party;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.SharedLootEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Party wire payload for a host's final accept or reject decision.
 *
 * <p>Accepted decisions carry the frozen split roster; rejected decisions must carry none.</p>
 */
public class DecisionMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 2;

	private int protocolVersion = PROTOCOL_VERSION;
	private String proposalId;
	private String decision;
	private long decidedAtEpochMilli;
	private List<ParticipantPayload> participants = new ArrayList<>();

	public DecisionMessage()
	{
	}

	public DecisionMessage(LootProposal proposal)
	{
		if (proposal == null || proposal.getStatus() == LootProposalStatus.PENDING
			|| proposal.getDecidedAt() == null)
		{
			throw new IllegalArgumentException("A final proposal is required");
		}
		this.proposalId = proposal.getProposalId();
		this.decision = proposal.getStatus().name();
		this.decidedAtEpochMilli = proposal.getDecidedAt().toEpochMilli();
		for (LootshareParticipant participant : proposal.getParticipants())
		{
			participants.add(new ParticipantPayload(participant.getMemberId(), participant.getDisplayName()));
		}
	}

	/** Validates the wire representation without applying it to engine state. */
	public Optional<DecodedDecision> decode()
	{
		if (protocolVersion != PROTOCOL_VERSION || getMemberId() <= 0L || proposalId == null
			|| proposalId.trim().isEmpty() || proposalId.trim().length() > SharedLootEvent.MAX_PROPOSAL_ID_LENGTH
			|| participants == null || participants.size() > LootProposal.MAX_PARTICIPANTS)
		{
			return Optional.empty();
		}
		try
		{
			LootProposalStatus status = LootProposalStatus.valueOf(decision);
			if (status == LootProposalStatus.PENDING)
			{
				return Optional.empty();
			}
			List<LootshareParticipant> roster = new ArrayList<>(participants.size());
			for (ParticipantPayload participant : participants)
			{
				if (participant == null)
				{
					return Optional.empty();
				}
				roster.add(new LootshareParticipant(participant.memberId, participant.displayName));
			}
			if ((status == LootProposalStatus.ACCEPTED && roster.isEmpty())
				|| (status == LootProposalStatus.REJECTED && !roster.isEmpty()))
			{
				return Optional.empty();
			}
			return Optional.of(new DecodedDecision(proposalId.trim(), status,
				Instant.ofEpochMilli(decidedAtEpochMilli), roster));
		}
		catch (RuntimeException ignored)
		{
			return Optional.empty();
		}
	}

	public int getProtocolVersion()
	{
		return protocolVersion;
	}

	public String getProposalId()
	{
		return proposalId;
	}

	public String getDecision()
	{
		return decision;
	}

	public List<ParticipantPayload> getParticipants()
	{
		return Collections.unmodifiableList(participants);
	}

	public static class ParticipantPayload
	{
		private long memberId;
		private String displayName;

		public ParticipantPayload()
		{
		}

		private ParticipantPayload(long memberId, String displayName)
		{
			this.memberId = memberId;
			this.displayName = displayName;
		}

		public long getMemberId()
		{
			return memberId;
		}

		public String getDisplayName()
		{
			return displayName;
		}
	}

	public static final class DecodedDecision
	{
		private final String proposalId;
		private final LootProposalStatus status;
		private final Instant decidedAt;
		private final List<LootshareParticipant> participants;

		private DecodedDecision(String proposalId, LootProposalStatus status, Instant decidedAt,
		                        List<LootshareParticipant> participants)
		{
			this.proposalId = proposalId;
			this.status = status;
			this.decidedAt = decidedAt;
			this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
		}

		public String getProposalId()
		{
			return proposalId;
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
}
