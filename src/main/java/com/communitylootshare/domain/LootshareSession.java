/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LootshareSession
{
	public static final int MAX_ACCEPTED_PROPOSALS = 4096;
	public static final int MAX_MEMBER_APPROVALS = 64;
	public static final long MAXIMUM_SHARED_LOOT_VALUE = LootshareSettings.MAXIMUM_SHARED_LOOT_VALUE;

	private final String sessionId;
	private final long partyId;
	private final Instant startedAt;
	private final List<LootProposal> acceptedProposals = new ArrayList<>();
	private Instant endedAt;
	private long hostMemberId;
	/**
	 * Retained so schema-2 snapshots written before the full host policy can still be restored.
	 */
	private long minimumSharedLootValue;
	private LootshareSettings hostSettings;
	private long hostRevision;
	private Map<Long, MemberApprovalStatus> memberApprovalStatuses = new LinkedHashMap<>();

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
		copy.setHostState(hostMemberId, getHostSettings(), hostRevision, getMemberApprovalStatuses());
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
}
