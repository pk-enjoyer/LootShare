/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.SharedLootEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/** Host acceptance with the roster frozen for one equal split. */
public class DecisionMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;
	private int protocolVersion = PROTOCOL_VERSION;
	private String proposalId;
	private long decidedAtEpochMilli;
	private List<ParticipantPayload> participants = new ArrayList<>();

	public DecisionMessage() { }
	public DecisionMessage(LootProposal proposal)
	{
		if (!proposal.isAccepted() || proposal.getDecidedAt() == null) { throw new IllegalArgumentException("Accepted proposal required"); }
		proposalId = proposal.getProposalId(); decidedAtEpochMilli = proposal.getDecidedAt().toEpochMilli();
		for (LootshareParticipant participant : proposal.getParticipants()) { participants.add(new ParticipantPayload(participant)); }
	}
	public Optional<DecodedDecision> decode()
	{
		if (protocolVersion != PROTOCOL_VERSION || getMemberId() <= 0L || proposalId == null || participants == null
			|| participants.isEmpty() || participants.size() > LootProposal.MAX_PARTICIPANTS) { return Optional.empty(); }
		try
		{
			List<LootshareParticipant> roster = new ArrayList<>();
			for (ParticipantPayload participant : participants)
			{
				if (participant == null) { return Optional.empty(); }
				roster.add(new LootshareParticipant(participant.memberId, participant.displayName));
			}
			return Optional.of(new DecodedDecision(proposalId, Instant.ofEpochMilli(decidedAtEpochMilli), roster));
		}
		catch (RuntimeException ignored) { return Optional.empty(); }
	}
	public static class ParticipantPayload
	{
		private long memberId; private String displayName;
		public ParticipantPayload() { }
		private ParticipantPayload(LootshareParticipant participant) { memberId = participant.getMemberId(); displayName = participant.getDisplayName(); }
	}
	public static final class DecodedDecision
	{
		private final String proposalId; private final Instant decidedAt; private final List<LootshareParticipant> participants;
		private DecodedDecision(String proposalId, Instant decidedAt, List<LootshareParticipant> participants)
		{ this.proposalId = proposalId; this.decidedAt = decidedAt; this.participants = participants; }
		public String getProposalId() { return proposalId; }
		public Instant getDecidedAt() { return decidedAt; }
		public List<LootshareParticipant> getParticipants() { return participants; }
	}
}
