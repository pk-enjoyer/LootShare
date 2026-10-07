/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LootshareSession
{
	public static final int MAX_ACCEPTED_PROPOSALS = 4096;
	public static final int MAX_REJECTED_PROPOSALS = 4096;
	public static final int MAX_MEMBER_APPROVALS = 64;
	public static final int MAX_DECISION_KEYS = 128;
	public static final int MAX_DECISION_AUTHORIZATIONS = MAX_ACCEPTED_PROPOSALS + MAX_REJECTED_PROPOSALS;
	public static final long MAXIMUM_SHARED_LOOT_VALUE = LootshareSettings.MAXIMUM_SHARED_LOOT_VALUE;

	private final String sessionId;
	private final long partyId;
	private final Instant startedAt;
	private final List<LootProposal> acceptedProposals = new ArrayList<>();
	private List<LootProposal> rejectedProposals = new ArrayList<>();
	private Instant endedAt;
	private long hostMemberId;
	/**
	 * Retained so schema-2 snapshots written before the full host policy can still be restored.
	 */
	private long minimumSharedLootValue;
	private LootshareSettings hostSettings;
	private long hostRevision;
	private Map<Long, MemberApprovalStatus> memberApprovalStatuses = new LinkedHashMap<>();
	private Map<String, Long> decisionKeys = new LinkedHashMap<>();
	private Set<String> decisionAuthorizations = new LinkedHashSet<>();

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
		LootProposal validated = proposal.validatedCopy();
		authorizeDecision(validated);
		acceptedProposals.add(validated);
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

	public boolean addRejectedProposal(LootProposal proposal)
	{
		if (proposal == null || proposal.getStatus() != LootProposalStatus.REJECTED
			|| proposal.getPartyId() != partyId)
		{
			throw new IllegalArgumentException("Session can only archive rejected proposals for its party");
		}
		if (getRejectedProposals().stream().anyMatch(existing -> existing.getProposalId().equals(proposal.getProposalId())))
		{
			return false;
		}
		if (getRejectedProposals().size() >= MAX_REJECTED_PROPOSALS)
		{
			throw new IllegalStateException("Session rejected-proposal limit reached");
		}
		if (rejectedProposals == null)
		{
			// Older JSON snapshots have no rejected-proposal archive.
			rejectedProposals = new ArrayList<>();
		}
		LootProposal validated = proposal.validatedCopy();
		authorizeDecision(validated);
		rejectedProposals.add(validated);
		return true;
	}

	public LootshareSession snapshot()
	{
		LootshareSession copy = new LootshareSession(sessionId, partyId, startedAt);
		copy.setHostState(hostMemberId, getHostSettings(), hostRevision, getMemberApprovalStatuses());
		copy.trustDecisionKeys(getDecisionKeys());
		copy.trustDecisionAuthorizations(getDecisionAuthorizations());
		for (LootProposal proposal : acceptedProposals)
		{
			copy.addAcceptedProposal(proposal);
		}
		for (LootProposal proposal : getRejectedProposals())
		{
			copy.addRejectedProposal(proposal);
		}
		if (endedAt != null)
		{
			copy.end(endedAt);
		}
		return copy;
	}

	public void replaceFinalizedProposal(LootProposal proposal)
	{
		List<LootProposal> records = proposal.getStatus() == LootProposalStatus.ACCEPTED
			? acceptedProposals : rejectedProposals;
		if (records == null)
		{
			return;
		}
		for (int index = 0; index < records.size(); index++)
		{
			LootProposal existing = records.get(index);
			if (existing.getProposalId().equals(proposal.getProposalId()))
			{
				if (!existing.hasSameIdentity(proposal) || existing.getStatus() != proposal.getStatus()
					|| !existing.getDecidedAt().equals(proposal.getDecidedAt())
					|| !existing.getParticipants().equals(proposal.getParticipants()))
				{
					throw new IllegalArgumentException("Cannot replace a frozen decision");
				}
				records.set(index, proposal.validatedCopy());
				return;
			}
		}
	}

	public void resume()
	{
		endedAt = null;
	}

	private void authorizeDecision(LootProposal proposal)
	{
		String id = LootDecisionReceipt.decisionId(proposal);
		if (decisionAuthorizations == null)
		{
			decisionAuthorizations = new LinkedHashSet<>();
		}
		if (!decisionAuthorizations.contains(id) && decisionAuthorizations.size() >= MAX_DECISION_AUTHORIZATIONS)
		{
			throw new IllegalStateException("Decision authorization limit reached");
		}
		decisionAuthorizations.add(id);
	}

	public Set<String> getDecisionAuthorizations()
	{
		return decisionAuthorizations == null ? Collections.emptySet()
			: Collections.unmodifiableSet(new LinkedHashSet<>(decisionAuthorizations));
	}

	/** Only authenticated host snapshots may extend this set without the finalized record. */
	public boolean trustDecisionAuthorizations(Collection<String> incoming)
	{
		if (incoming == null || incoming.size() > MAX_DECISION_AUTHORIZATIONS)
		{
			throw new IllegalArgumentException("Invalid decision authorizations");
		}
		Set<String> merged = new LinkedHashSet<>(getDecisionAuthorizations());
		for (String id : incoming)
		{
			if (id == null || id.length() != 44)
			{
				throw new IllegalArgumentException("Invalid decision commitment");
			}
			byte[] decoded = Base64.getDecoder().decode(id);
			if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(id))
			{
				throw new IllegalArgumentException("Invalid decision commitment");
			}
			merged.add(id);
		}
		if (merged.size() > MAX_DECISION_AUTHORIZATIONS)
		{
			throw new IllegalArgumentException("Too many decision authorizations");
		}
		boolean changed = !merged.equals(getDecisionAuthorizations());
		decisionAuthorizations = merged;
		return changed;
	}

	public Map<String, Long> getDecisionKeys()
	{
		return decisionKeys == null ? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(decisionKeys));
	}

	public boolean trustDecisionKeys(Map<String, Long> incoming)
	{
		if (incoming == null || incoming.size() > MAX_DECISION_KEYS)
		{
			throw new IllegalArgumentException("Invalid decision key collection");
		}
		Map<String, Long> merged = new LinkedHashMap<>(getDecisionKeys());
		for (Map.Entry<String, Long> entry : incoming.entrySet())
		{
			if (entry.getValue() == null || entry.getValue() <= 0L
				|| !LootDecisionReceipt.isValidKeyId(entry.getKey(), entry.getValue())
				|| (merged.containsKey(entry.getKey()) && !merged.get(entry.getKey()).equals(entry.getValue())))
			{
				throw new IllegalArgumentException("Invalid or conflicting decision key");
			}
			merged.put(entry.getKey(), entry.getValue());
		}
		if (merged.size() > MAX_DECISION_KEYS)
		{
			throw new IllegalArgumentException("Too many decision keys");
		}
		boolean changed = !merged.equals(getDecisionKeys());
		decisionKeys = merged;
		return changed;
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

	public void setHostState(long hostMemberId, long minimumSharedLootValue, long hostRevision)
	{
		setHostState(hostMemberId, LootshareSettings.defaults(minimumSharedLootValue), hostRevision);
	}

	public void setHostState(long hostMemberId, LootshareSettings hostSettings, long hostRevision)
	{
		setHostState(hostMemberId, hostSettings, hostRevision, getMemberApprovalStatuses());
	}

	public void setHostState(long hostMemberId, LootshareSettings hostSettings, long hostRevision,
	                         Map<Long, MemberApprovalStatus> approvalStatuses)
	{
		if (hostMemberId < 0L || hostSettings == null || hostRevision < 0L
			|| (hostMemberId > 0L && hostRevision == 0L) || approvalStatuses == null)
		{
			throw new IllegalArgumentException("Host state contains an invalid member, settings, or revision");
		}
		if (approvalStatuses.size() > MAX_MEMBER_APPROVALS)
		{
			throw new IllegalArgumentException("Host state contains too many member approvals");
		}
		Map<Long, MemberApprovalStatus> validatedStatuses = new LinkedHashMap<>();
		for (Map.Entry<Long, MemberApprovalStatus> entry : approvalStatuses.entrySet())
		{
			Long memberId = entry.getKey();
			MemberApprovalStatus status = entry.getValue();
			if (memberId == null || memberId <= 0L || status == null)
			{
				throw new IllegalArgumentException("Host state contains an invalid member approval");
			}
			if (status != MemberApprovalStatus.PENDING)
			{
				validatedStatuses.put(memberId, status);
			}
		}
		if (hostMemberId > 0L)
		{
			validatedStatuses.put(hostMemberId, MemberApprovalStatus.APPROVED);
		}
		if (validatedStatuses.size() > MAX_MEMBER_APPROVALS)
		{
			throw new IllegalArgumentException("Host state contains too many member approvals");
		}
		LootshareSettings validatedSettings = hostSettings.validatedCopy();
		this.hostMemberId = hostMemberId;
		this.minimumSharedLootValue = validatedSettings.getMinimumSharedLootValue();
		this.hostSettings = validatedSettings;
		this.hostRevision = hostRevision;
		this.memberApprovalStatuses = validatedStatuses;
	}

	public void clearHost()
	{
		if (memberApprovalStatuses != null)
		{
			memberApprovalStatuses.remove(hostMemberId);
		}
		hostMemberId = 0L;
	}

	public long getHostMemberId()
	{
		return hostMemberId;
	}

	public long getMinimumSharedLootValue()
	{
		return getHostSettings().getMinimumSharedLootValue();
	}

	public LootshareSettings getHostSettings()
	{
		return hostSettings == null
			? LootshareSettings.defaults(minimumSharedLootValue)
			: hostSettings.validatedCopy();
	}

	public long getHostRevision()
	{
		return hostRevision;
	}

	public MemberApprovalStatus getMemberApprovalStatus(long memberId)
	{
		if (memberId <= 0L)
		{
			throw new IllegalArgumentException("Member ID must be positive");
		}
		if (memberId == hostMemberId && hostMemberId > 0L)
		{
			return MemberApprovalStatus.APPROVED;
		}
		MemberApprovalStatus status = memberApprovalStatuses == null ? null : memberApprovalStatuses.get(memberId);
		return status == null ? MemberApprovalStatus.PENDING : status;
	}

	public Map<Long, MemberApprovalStatus> getMemberApprovalStatuses()
	{
		Map<Long, MemberApprovalStatus> statuses = new LinkedHashMap<>();
		if (memberApprovalStatuses != null)
		{
			statuses.putAll(memberApprovalStatuses);
		}
		if (hostMemberId > 0L)
		{
			statuses.put(hostMemberId, MemberApprovalStatus.APPROVED);
		}
		return Collections.unmodifiableMap(statuses);
	}

	public List<LootProposal> getAcceptedProposals()
	{
		return Collections.unmodifiableList(acceptedProposals);
	}

	public List<LootProposal> getRejectedProposals()
	{
		return rejectedProposals == null ? Collections.emptyList() : Collections.unmodifiableList(rejectedProposals);
	}
}
