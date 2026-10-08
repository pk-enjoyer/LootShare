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
	public static final int MAX_HISTORY_EVENTS = 256;
	public static final int MAX_ACCEPTED_PROPOSALS = 4096;
	public static final int MAX_REJECTED_PROPOSALS = 4096;
	public static final int MAX_MEMBER_APPROVALS = 64;
	public static final int MAX_DECISION_KEYS = 128;
	public static final int MAX_DECISION_AUTHORIZATIONS = MAX_ACCEPTED_PROPOSALS + MAX_REJECTED_PROPOSALS;
	public static final long MAXIMUM_SHARED_LOOT_VALUE = LootshareSettings.MAXIMUM_SHARED_LOOT_VALUE;

	private HostPeriod hostPeriod;
	private HostPeriod closingPeriod;
	private LootshareSettings closingSettings;
	private HostHistoryEvent.Reason closingReason;
	// Keep the first boundary fields readable from existing schema-8 profiles.
	private List<ClosingBoundary> queuedClosingPeriods = new ArrayList<>();
	private List<HostHistoryEvent> historyEvents = new ArrayList<>();
	private Map<String, HistoryCommitment> historyCommitments = new LinkedHashMap<>();
	private boolean earlierHistoryOmitted;
	private boolean earlierHostChainUnknown = true;

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
		copy.setHistoryMetadata(hostPeriod, getHistoryCommitments(), earlierHistoryOmitted, earlierHostChainUnknown);
		if (closingPeriod != null)
		{
			copy.markClosingPeriod(closingPeriod, closingSettings, closingReason);
		}
		if (queuedClosingPeriods != null)
		{
			for (ClosingBoundary boundary : queuedClosingPeriods)
			{
				copy.markClosingPeriod(boundary.period, boundary.settings, boundary.reason);
			}
		}
		for (HostHistoryEvent event : getHistoryEvents()) { copy.addHistoryEvent(event, false); }
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

	public HostPeriod getHostPeriod() { return hostPeriod; }
	public HostPeriod getClosingPeriod() { return closingPeriod; }
	public LootshareSettings getClosingSettings() { return closingSettings; }
	public HostHistoryEvent.Reason getClosingReason() { return closingReason; }
	public boolean isEarlierHistoryOmitted() { return earlierHistoryOmitted; }
	public boolean isEarlierHostChainUnknown() { return earlierHostChainUnknown; }
	public List<HostHistoryEvent> getHistoryEvents()
	{
		return historyEvents == null ? Collections.emptyList() : Collections.unmodifiableList(historyEvents);
	}
	public Map<String, HistoryCommitment> getHistoryCommitments()
	{
		return historyCommitments == null ? Collections.emptyMap() : Collections.unmodifiableMap(historyCommitments);
	}

	/** Merge only metadata vouched for by the current transport authority. */
	public void setHistoryMetadata(HostPeriod period, Map<String, HistoryCommitment> incoming,
	                               boolean omitted, boolean unknown)
	{
		HostPeriod validatedPeriod = period == null ? null : period.validatedCopy();
		if (validatedPeriod != null && ((hostMemberId > 0 && validatedPeriod.getHostMemberId() != hostMemberId) || validatedPeriod.getEndedAt() != null))
		{
			throw new IllegalArgumentException("Period does not identify the active host");
		}
		if (incoming == null || incoming.size() > MAX_HISTORY_EVENTS)
		{
			throw new IllegalArgumentException("Too many history commitments");
		}
		Map<String, HistoryCommitment> merged = new LinkedHashMap<>(getHistoryCommitments());
		for (Map.Entry<String, HistoryCommitment> entry : incoming.entrySet())
		{
			HostPeriod.validateId(entry.getKey());
			HistoryCommitment anchor = entry.getValue().validatedCopy();
			if (anchor.getRevision() > hostRevision || (merged.containsKey(entry.getKey()) && !merged.get(entry.getKey()).equals(anchor)))
			{
				throw new IllegalArgumentException("Conflicting history commitment");
			}
			merged.put(entry.getKey(), anchor);
		}
		List<String> ordered = new ArrayList<>(merged.keySet());
		ordered.sort(java.util.Comparator.comparingLong((String id) -> merged.get(id).getRevision()).thenComparing(id -> id));
		boolean pruned = ordered.size() > MAX_HISTORY_EVENTS;
		while (ordered.size() > MAX_HISTORY_EVENTS) { merged.remove(ordered.remove(0)); }
		historyCommitments = merged;
		if (historyEvents != null) { historyEvents.removeIf(event -> !merged.containsKey(event.getEventId())); }
		hostPeriod = validatedPeriod;
		earlierHistoryOmitted |= omitted || pruned;
		earlierHostChainUnknown = unknown;
	}

	public boolean addHistoryEvent(HostHistoryEvent event)
	{
		return addHistoryEvent(event, true);
	}
	/** Verify at load/import boundaries; immutable in-memory snapshots need no repeated ECDSA work. */
	public void validateHistorySignatures()
	{
		for (HostHistoryEvent event : getHistoryEvents())
		{
			if (!event.verifies()) { throw new IllegalArgumentException("Invalid history signature"); }
		}
	}
	private boolean addHistoryEvent(HostHistoryEvent event, boolean verifySignature)
	{
		HostHistoryEvent validated = event.validatedCopy();
		HistoryCommitment anchor = getHistoryCommitments().get(validated.getEventId());
		if (validated.getPartyId() != partyId || anchor == null || anchor.getRevision() != validated.getRevision()
			|| !anchor.getHash().equals(validated.commitment()) || (verifySignature && !validated.verifies())
			|| !validated.getReceipt().isTrustedBy(getDecisionKeys()))
		{
			throw new IllegalArgumentException("Uncommitted or invalid history event");
		}
		for (HostHistoryEvent existing : getHistoryEvents())
		{
			if (existing.getEventId().equals(validated.getEventId()))
			{
				if (!existing.commitment().equals(validated.commitment())) { throw new IllegalArgumentException("Conflicting history event"); }
				return false;
			}
		}
		if (historyEvents == null) { historyEvents = new ArrayList<>(); }
		historyEvents.add(validated);
		historyEvents.sort(java.util.Comparator.comparingLong(HostHistoryEvent::getRevision).thenComparing(HostHistoryEvent::getEventId));
		if (validated.getKind() == HostHistoryEvent.Kind.PERIOD_CLOSED)
		{
			removeClosingPeriod(validated.getPeriod().getPeriodId());
		}
		return true;
	}

	public void markClosingPeriod(HostPeriod period, LootshareSettings settings, HostHistoryEvent.Reason reason)
	{
		if (period == null || period.getEndedAt() == null || settings == null || reason == null || reason == HostHistoryEvent.Reason.SETTINGS)
		{
			throw new IllegalArgumentException("Invalid host boundary");
		}
		HostPeriod validatedPeriod = period.validatedCopy();
		LootshareSettings validatedSettings = settings.validatedCopy();
		String id = validatedPeriod.getPeriodId();
		if ((closingPeriod != null && closingPeriod.getPeriodId().equals(id))
			|| getHistoryEvents().stream().anyMatch(event -> event.getKind() == HostHistoryEvent.Kind.PERIOD_CLOSED
				&& event.getPeriod().getPeriodId().equals(id))) { return; }
		if (queuedClosingPeriods == null) { queuedClosingPeriods = new ArrayList<>(); }
		if (queuedClosingPeriods.stream().anyMatch(boundary -> boundary.period.getPeriodId().equals(id))) { return; }
		if (closingPeriod == null)
		{
			closingPeriod = validatedPeriod;
			closingSettings = validatedSettings;
			closingReason = reason;
		}
		else
		{
			if (queuedClosingPeriods.size() >= MAX_HISTORY_EVENTS - 1)
			{
				throw new IllegalArgumentException("Too many pending host boundaries");
			}
			queuedClosingPeriods.add(new ClosingBoundary(validatedPeriod, validatedSettings, reason));
		}
	}

	private void removeClosingPeriod(String periodId)
	{
		if (queuedClosingPeriods != null)
		{
			queuedClosingPeriods.removeIf(boundary -> boundary.period.getPeriodId().equals(periodId));
		}
		if (closingPeriod != null && closingPeriod.getPeriodId().equals(periodId)) { clearClosingPeriod(); }
	}

	public void clearClosingPeriod()
	{
		closingPeriod = null; closingSettings = null; closingReason = null;
		if (queuedClosingPeriods != null && !queuedClosingPeriods.isEmpty())
		{
			ClosingBoundary next = queuedClosingPeriods.remove(0);
			closingPeriod = next.period; closingSettings = next.settings; closingReason = next.reason;
		}
	}

	private static final class ClosingBoundary
	{
		private final HostPeriod period;
		private final LootshareSettings settings;
		private final HostHistoryEvent.Reason reason;

		private ClosingBoundary(HostPeriod period, LootshareSettings settings, HostHistoryEvent.Reason reason)
		{
			this.period = period; this.settings = settings; this.reason = reason;
		}
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
		if (hostPeriod != null && hostMemberId > 0 && hostPeriod.getHostMemberId() != hostMemberId) { hostPeriod = null; }
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
