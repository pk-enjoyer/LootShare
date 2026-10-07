/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/** A bounded peer copy of a finalized contribution, addressed to its recovering host. */
public class RecoveryMessage extends PartyMemberMessage
{
	private int protocolVersion = 1;
	private long targetHostMemberId;
	private ProposalMessage proposal;
	private DecisionMessage decision;
	private boolean complete;

	public RecoveryMessage() { }

	public RecoveryMessage(long targetHostMemberId, LootProposal finalized)
	{
		if (targetHostMemberId <= 0L || finalized == null || finalized.getStatus() == LootProposalStatus.PENDING)
		{
			throw new IllegalArgumentException("A host and finalized proposal are required");
		}
		this.targetHostMemberId = targetHostMemberId;
		this.proposal = new ProposalMessage(finalized);
		this.decision = new DecisionMessage(finalized);
	}

	public Optional<LootProposal> decode(long partyId)
	{
		if (protocolVersion != 1 || getMemberId() <= 0L || targetHostMemberId <= 0L
			|| proposal == null || decision == null)
		{
			return Optional.empty();
		}
		proposal.setMemberId(getMemberId());
		decision.setMemberId(getMemberId());
		Optional<LootProposal> pending = proposal.decode(partyId);
		Optional<DecisionMessage.DecodedDecision> decoded = decision.decode();
		if (!pending.isPresent() || !decoded.isPresent()
			|| !pending.get().getProposalId().equals(decoded.get().getProposalId()))
		{
			return Optional.empty();
		}
		try
		{
			DecisionMessage.DecodedDecision result = decoded.get();
			return Optional.of(pending.get().decide(result.getStatus(), result.getDecidedAt(), result.getParticipants())
				.withDecisionReceipt(result.getReceipt()));
		}
		catch (RuntimeException e)
		{
			return Optional.empty();
		}
	}

	public static RecoveryMessage completed(long targetHostMemberId)
	{
		if (targetHostMemberId <= 0L)
		{
			throw new IllegalArgumentException("A host is required");
		}
		RecoveryMessage message = new RecoveryMessage();
		message.targetHostMemberId = targetHostMemberId;
		message.complete = true;
		return message;
	}

	public boolean isComplete()
	{
		return protocolVersion == 1 && complete && proposal == null && decision == null;
	}

	public long getTargetHostMemberId() { return targetHostMemberId; }
}
