/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The single active Party ledger. Accepted proposals retain their frozen roster. */
public final class LootshareSession
{
	public static final int MAX_ACCEPTED_PROPOSALS = 4096;
	private final String sessionId;
	private final long partyId;
	private final Instant startedAt;
	private final List<LootProposal> acceptedProposals = new ArrayList<>();
	private long hostMemberId;
	private long minimumSharedLootValue;
	private long hostRevision;

	public LootshareSession(String sessionId, long partyId, Instant startedAt)
	{
		if (sessionId == null || sessionId.trim().isEmpty() || sessionId.trim().length() > SharedLootEvent.MAX_PROPOSAL_ID_LENGTH || partyId <= 0L || startedAt == null)
		{
			throw new IllegalArgumentException("Session party, ID, and start time are required");
		}
		this.sessionId = sessionId.trim(); this.partyId = partyId; this.startedAt = startedAt;
	}
	public boolean addAcceptedProposal(LootProposal proposal)
	{
		if (proposal == null || proposal.getStatus() != LootProposalStatus.ACCEPTED || proposal.getPartyId() != partyId) throw new IllegalArgumentException("Accepted proposal must belong to this Party");
		for (LootProposal existing : acceptedProposals) if (existing.getProposalId().equals(proposal.getProposalId())) return false;
		if (acceptedProposals.size() >= MAX_ACCEPTED_PROPOSALS) throw new IllegalStateException("Accepted proposal limit reached");
		acceptedProposals.add(proposal.validatedCopy());
		acceptedProposals.sort((a, b) -> { int c = a.getDecidedAt().compareTo(b.getDecidedAt()); return c != 0 ? c : a.getProposalId().compareTo(b.getProposalId()); });
		return true;
	}
	public LootshareSession snapshot()
	{
		LootshareSession copy = new LootshareSession(sessionId, partyId, startedAt);
		copy.setHostState(hostMemberId, minimumSharedLootValue, hostRevision);
		for (LootProposal proposal : acceptedProposals) copy.addAcceptedProposal(proposal);
		return copy;
	}
	public void setHostState(long memberId, long minimum, long revision)
	{
		if (memberId < 0L || minimum < 0L || minimum > LootshareSettings.MAXIMUM_SHARED_LOOT_VALUE || revision < 0L || (memberId > 0L && revision == 0L)) throw new IllegalArgumentException("Invalid host state");
		hostMemberId = memberId; minimumSharedLootValue = minimum; hostRevision = revision;
	}
	public void clearHost() { hostMemberId = 0L; }
	public String getSessionId() { return sessionId; }
	public long getPartyId() { return partyId; }
	public Instant getStartedAt() { return startedAt; }
	public boolean isActive() { return true; }
	public long getHostMemberId() { return hostMemberId; }
	public long getMinimumSharedLootValue() { return minimumSharedLootValue; }
	public LootshareSettings getHostSettings() { return LootshareSettings.defaults(minimumSharedLootValue); }
	public long getHostRevision() { return hostRevision; }
	public List<LootProposal> getAcceptedProposals() { return Collections.unmodifiableList(acceptedProposals); }
}
