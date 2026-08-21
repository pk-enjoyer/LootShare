/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LootshareSession
{
	private final long partyId;
	private long hostMemberId;
	private long hostRevision;
	private LootshareSettings settings;
	private final List<LootProposal> proposals = new ArrayList<>();
	public LootshareSession(long partyId) { if (partyId <= 0L) throw new IllegalArgumentException("partyId"); this.partyId = partyId; }
	public long getPartyId() { return partyId; }
	public long getHostMemberId() { return hostMemberId; }
	public long getHostRevision() { return hostRevision; }
	public LootshareSettings getSettings() { return settings; }
	public void setHost(long id, LootshareSettings next, long revision) { hostMemberId = id; settings = next; hostRevision = revision; }
	public List<LootProposal> getAcceptedProposals() { List<LootProposal> result = new ArrayList<>(); for (LootProposal p : proposals) if (p.isAccepted()) result.add(p); return Collections.unmodifiableList(result); }
	public LootProposal find(String id) { for (LootProposal p : proposals) if (p.getProposalId().equals(id)) return p; return null; }
	public boolean add(LootProposal proposal) { if (find(proposal.getProposalId()) != null) return false; proposals.add(proposal); return true; }
}
