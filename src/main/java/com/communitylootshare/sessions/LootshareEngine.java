/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.sessions;

import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class LootshareEngine
{
	public static final int MAX_PROPOSALS = 2048;
	public static final int MAX_SESSIONS = 256;

	private final Map<String, LootProposal> proposals = new LinkedHashMap<>();
	private final List<LootshareSession> sessions = new ArrayList<>();
	private String activeSessionId;

	public synchronized void restore(LootshareState state)
	{
		proposals.clear();
		sessions.clear();
		activeSessionId = null;
		if (state == null)
		{
			return;
		}

		for (LootProposal persisted : state.getProposals())
		{
			if (proposals.size() >= MAX_PROPOSALS)
			{
				break;
			}
			try
			{
				LootProposal proposal = persisted.validatedCopy();
				proposals.putIfAbsent(proposal.getProposalId(), proposal);
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping an invalid persisted Community Lootshare proposal", e);
			}
		}

		for (LootshareSession persisted : state.getSessions())
		{
			if (sessions.size() >= MAX_SESSIONS)
			{
				break;
			}
			if (persisted == null)
			{
				continue;
			}
			try
			{
				LootshareSession session = persisted.snapshot();
				if (findSession(session.getSessionId()) != null)
				{
					continue;
				}
				sessions.add(session);
				List<LootProposal> finalized = new ArrayList<>(session.getAcceptedProposals());
				finalized.addAll(session.getRejectedProposals());
				for (LootProposal accepted : finalized)
				{
					LootProposal existing = proposals.get(accepted.getProposalId());
					if ((existing == null && proposals.size() < MAX_PROPOSALS)
						|| (existing != null && existing.hasSameIdentity(accepted)
							&& existing.getStatus() == LootProposalStatus.PENDING))
					{
						proposals.put(accepted.getProposalId(), accepted.validatedCopy());
					}
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping an invalid persisted Community Lootshare session", e);
			}
		}

		// Schema-4 snapshots kept rejections only in the transport index. Archive them before
		// allowing eviction, so upgrading cannot turn a rejected drop back into a new proposal.
		for (LootProposal proposal : proposals.values())
		{
			if (proposal.getStatus() != LootProposalStatus.REJECTED)
			{
				continue;
			}
			LootshareSession target = null;
			for (LootshareSession session : sessions)
			{
				if (session.getPartyId() == proposal.getPartyId())
				{
					target = session;
				}
			}
			if (target != null)
			{
				try
				{
					target.addRejectedProposal(proposal);
				}
				catch (RuntimeException e)
				{
					log.debug("Could not archive a persisted Community Lootshare rejection", e);
				}
			}
		}

		LootshareSession active = findSession(state.getActiveSessionId());
		if (active != null && active.isActive())
		{
			activeSessionId = active.getSessionId();
		}
	}

	public synchronized LootshareState snapshot()
	{
		LootshareState state = new LootshareState();
		state.setSchemaVersion(LootshareState.CURRENT_SCHEMA_VERSION);
		state.setActiveSessionId(activeSessionId);
		List<LootProposal> proposalCopies = new ArrayList<>(proposals.size());
		for (LootProposal proposal : proposals.values())
		{
			proposalCopies.add(proposal.validatedCopy());
		}
		state.setProposals(proposalCopies);
		List<LootshareSession> sessionCopies = new ArrayList<>(sessions.size());
		for (LootshareSession session : sessions)
		{
			sessionCopies.add(session.snapshot());
		}
		state.setSessions(sessionCopies);
		return state;
	}

	public synchronized MutationResult enterParty(long partyId, String newSessionId, Instant at)
	{
		final LootshareSession nextSession;
		try
		{
			nextSession = new LootshareSession(newSessionId, partyId, at);
		}
		catch (RuntimeException ignored)
		{
			return MutationResult.INVALID;
		}
		LootshareSession active = activeSession();
		if (active != null && active.getPartyId() == partyId)
		{
			return MutationResult.DUPLICATE;
		}
		// A Party passphrase identifies a ledger, including after a local leave/rejoin.
		LootshareSession resumed = null;
		int resumedIndex = 0;
		for (LootshareSession session : sessions)
		{
			if (session.getPartyId() == partyId)
			{
				resumed = session.snapshot();
				resumedIndex = sessions.indexOf(session);
				break;
			}
		}
		if (resumed != null)
		{
			try
			{
				// Prepare a validated copy before ending the current Party or replacing history.
				if (at.isBefore(resumed.getStartedAt()))
				{
					return MutationResult.INVALID;
				}
				for (LootshareSession session : sessions)
				{
					if (!session.getSessionId().equals(resumed.getSessionId()) && session.getPartyId() == partyId)
					{
						for (LootProposal accepted : session.getAcceptedProposals())
						{
							resumed.addAcceptedProposal(accepted);
						}
						for (LootProposal rejected : session.getRejectedProposals())
						{
							resumed.addRejectedProposal(rejected);
						}
						resumed.setHostState(session.getHostMemberId(), session.getHostSettings(),
							session.getHostRevision(), session.getMemberApprovalStatuses());
						resumed.trustDecisionKeys(session.getDecisionKeys());
						resumed.trustDecisionAuthorizations(session.getDecisionAuthorizations());
					}
				}
			}
			catch (IllegalStateException e)
			{
				return MutationResult.LIMIT_REACHED;
			}
			catch (RuntimeException e)
			{
				return MutationResult.INVALID;
			}
		}
		if (active != null)
		{
			try
			{
				active.end(at);
			}
			catch (RuntimeException ignored)
			{
				return MutationResult.INVALID;
			}
			activeSessionId = null;
		}
		if (resumed != null)
		{
			sessions.removeIf(session -> session.getPartyId() == partyId);
			sessions.add(resumedIndex, resumed);
			resumed.resume();
			activeSessionId = resumed.getSessionId();
			return MutationResult.APPLIED;
		}

		if (sessions.size() >= MAX_SESSIONS && !removeOldestCompletedSession())
		{
			return MutationResult.LIMIT_REACHED;
		}
		sessions.add(nextSession);
		activeSessionId = nextSession.getSessionId();
		return MutationResult.APPLIED;
	}

	public synchronized MutationResult leaveParty(Instant at)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return MutationResult.DUPLICATE;
		}
		try
		{
			active.end(at);
		}
		catch (RuntimeException ignored)
		{
			return MutationResult.INVALID;
		}
		activeSessionId = null;
		return MutationResult.APPLIED;
	}

	public synchronized MutationResult updateHostState(long actingMemberId, long nextHostMemberId,
	                                                   long minimumSharedLootValue, long revision)
	{
		final LootshareSettings settings;
		try
		{
			settings = LootshareSettings.defaults(minimumSharedLootValue);
		}
		catch (RuntimeException ignored)
		{
			return MutationResult.INVALID;
		}
		return updateHostState(actingMemberId, nextHostMemberId, settings, revision);
	}

	public synchronized MutationResult updateHostState(long actingMemberId, long nextHostMemberId,
	                                                   LootshareSettings settings, long revision)
	{
		LootshareSession active = activeSession();
		Map<Long, MemberApprovalStatus> approvalStatuses = active == null
			? Collections.emptyMap()
			: active.getMemberApprovalStatuses();
		return updateHostState(actingMemberId, nextHostMemberId, settings, revision, approvalStatuses);
	}

	public synchronized MutationResult updateHostState(long actingMemberId, long nextHostMemberId,
	                                                   LootshareSettings settings, long revision,
	                                                   Map<Long, MemberApprovalStatus> approvalStatuses)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (actingMemberId <= 0L || nextHostMemberId <= 0L || settings == null || revision <= 0L
			|| approvalStatuses == null)
		{
			return MutationResult.INVALID;
		}
		final LootshareSettings validatedSettings;
		final Map<Long, MemberApprovalStatus> validatedApprovalStatuses;
		try
		{
			validatedSettings = settings.validatedCopy();
			LootshareSession validationSession = new LootshareSession("approval-validation", active.getPartyId(),
				active.getStartedAt());
			validationSession.setHostState(nextHostMemberId, validatedSettings, revision, approvalStatuses);
			validatedApprovalStatuses = validationSession.getMemberApprovalStatuses();
		}
		catch (RuntimeException ignored)
		{
			return MutationResult.INVALID;
		}

		long currentHostMemberId = active.getHostMemberId();
		if ((currentHostMemberId == 0L && actingMemberId != nextHostMemberId)
			|| (currentHostMemberId != 0L && actingMemberId != currentHostMemberId))
		{
			return MutationResult.NOT_HOST;
		}
		if (revision == active.getHostRevision())
		{
			boolean sameApprovalPolicy = active.getMemberApprovalStatuses().equals(validatedApprovalStatuses);
			if (currentHostMemberId == 0L)
			{
				Map<Long, MemberApprovalStatus> activeWithoutClaimant =
					new LinkedHashMap<>(active.getMemberApprovalStatuses());
				Map<Long, MemberApprovalStatus> incomingWithoutClaimant =
					new LinkedHashMap<>(validatedApprovalStatuses);
				activeWithoutClaimant.remove(nextHostMemberId);
				incomingWithoutClaimant.remove(nextHostMemberId);
				sameApprovalPolicy = activeWithoutClaimant.equals(incomingWithoutClaimant);
			}
			if (currentHostMemberId == 0L && actingMemberId == nextHostMemberId
				&& active.getHostSettings().equals(validatedSettings)
				&& sameApprovalPolicy)
			{
				active.setHostState(nextHostMemberId, validatedSettings, revision, validatedApprovalStatuses);
				return MutationResult.APPLIED;
			}
			return currentHostMemberId == nextHostMemberId
				&& active.getHostSettings().equals(validatedSettings)
				&& sameApprovalPolicy
				? MutationResult.DUPLICATE
				: MutationResult.CONFLICT;
		}
		if (revision < active.getHostRevision())
		{
			return MutationResult.CONFLICT;
		}

		active.setHostState(nextHostMemberId, validatedSettings, revision, validatedApprovalStatuses);
		return MutationResult.APPLIED;
	}

	public synchronized MutationResult updateMemberApprovalStatus(long actingMemberId, long targetMemberId,
	                                                              MemberApprovalStatus status, long revision)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (actingMemberId <= 0L || targetMemberId <= 0L || status == null || revision <= 0L)
		{
			return MutationResult.INVALID;
		}
		if (active.getHostMemberId() != actingMemberId)
		{
			return MutationResult.NOT_HOST;
		}
		if (targetMemberId == active.getHostMemberId() && status != MemberApprovalStatus.APPROVED)
		{
			return MutationResult.INVALID;
		}
		Map<Long, MemberApprovalStatus> statuses = new LinkedHashMap<>(active.getMemberApprovalStatuses());
		if (status == MemberApprovalStatus.PENDING)
		{
			statuses.remove(targetMemberId);
		}
		else
		{
			statuses.put(targetMemberId, status);
		}
		return updateHostState(actingMemberId, active.getHostMemberId(), active.getHostSettings(), revision, statuses);
	}

	public synchronized MutationResult vacateHost(long departingMemberId)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (departingMemberId <= 0L)
		{
			return MutationResult.INVALID;
		}
		if (active.getHostMemberId() == 0L)
		{
			return MutationResult.DUPLICATE;
		}
		if (active.getHostMemberId() != departingMemberId)
		{
			return MutationResult.NOT_HOST;
		}
		active.clearHost();
		return MutationResult.APPLIED;
	}

	public synchronized MutationResult addProposal(LootProposal proposal)
	{
		if (proposal == null)
		{
			return MutationResult.INVALID;
		}
		final LootProposal validated;
		try
		{
			validated = proposal.validatedCopy();
		}
		catch (RuntimeException ignored)
		{
			return MutationResult.INVALID;
		}
		LootshareSession active = activeSession();
		if (active == null || active.getPartyId() != validated.getPartyId())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}

		LootProposal existing = findProposal(validated.getProposalId());
		if (existing != null)
		{
			return existing.hasSameIdentity(validated) ? MutationResult.DUPLICATE : MutationResult.CONFLICT;
		}
		if (validated.getStatus() != LootProposalStatus.PENDING)
		{
			return MutationResult.INVALID;
		}
		if (proposals.size() >= MAX_PROPOSALS)
		{
			// Final proposals already have a durable copy in the ledger. The transport index
			// can discard them without losing calculations, duplicate detection, or late-join replay.
			String archivedId = null;
			for (LootProposal indexed : proposals.values())
			{
				if (indexed.getPartyId() != active.getPartyId()
					|| (indexed.getStatus() != LootProposalStatus.PENDING
						&& findArchivedProposal(indexed.getProposalId()) != null))
				{
					archivedId = indexed.getProposalId();
					break;
				}
			}
			if (archivedId == null)
			{
				return MutationResult.LIMIT_REACHED;
			}
			proposals.remove(archivedId);
		}
		proposals.put(validated.getProposalId(), validated);
		return MutationResult.APPLIED;
	}

	public synchronized DecisionOutcome decide(String proposalId, long decidingMemberId,
	                                           LootProposalStatus decision, Instant at,
	                                           List<LootshareParticipant> participants)
	{
		LootProposal existing = findProposal(proposalId);
		if (existing == null)
		{
			return new DecisionOutcome(MutationResult.NOT_FOUND, null);
		}
		if (decision == null || decision == LootProposalStatus.PENDING)
		{
			return new DecisionOutcome(MutationResult.INVALID, existing);
		}
		LootshareSession active = activeSession();
		if (active == null || active.getPartyId() != existing.getPartyId())
		{
			return new DecisionOutcome(MutationResult.NO_ACTIVE_SESSION, existing);
		}
		if (decidingMemberId != active.getHostMemberId())
		{
			return new DecisionOutcome(MutationResult.NOT_HOST, existing);
		}
		if (existing.getStatus() != LootProposalStatus.PENDING)
		{
			MutationResult result = existing.getStatus() == decision
				? MutationResult.DUPLICATE
				: MutationResult.CONFLICT;
			return new DecisionOutcome(result, existing);
		}

		try
		{
			LootProposal decided = existing.decide(decision, at,
				participants == null ? Collections.emptyList() : participants);
			if (decision == LootProposalStatus.ACCEPTED && !isAcceptedInAnySession(proposalId))
			{
				active.addAcceptedProposal(decided);
			}
			else if (decision == LootProposalStatus.REJECTED)
			{
				active.addRejectedProposal(decided);
			}
			proposals.put(proposalId, decided);
			return new DecisionOutcome(MutationResult.APPLIED, decided);
		}
		catch (RuntimeException ignored)
		{
			return new DecisionOutcome(MutationResult.INVALID, existing);
		}
	}

	public synchronized Optional<LootProposal> getProposal(String proposalId)
	{
		return Optional.ofNullable(findProposal(proposalId));
	}

	public synchronized Map<String, Long> getActiveDecisionKeys()
	{
		LootshareSession active = activeSession();
		return active == null ? Collections.emptyMap() : active.getDecisionKeys();
	}

	public synchronized Set<String> getActiveDecisionAuthorizations()
	{
		LootshareSession active = activeSession();
		return active == null ? Collections.emptySet() : active.getDecisionAuthorizations();
	}

	public synchronized boolean canTrustDecisionAuthorizations(Collection<String> incoming)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return false;
		}
		try
		{
			LootshareSession validation = new LootshareSession("authorizations", active.getPartyId(), active.getStartedAt());
			validation.trustDecisionAuthorizations(active.getDecisionAuthorizations());
			validation.trustDecisionAuthorizations(incoming);
			return true;
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}

	public synchronized boolean trustDecisionAuthorizations(Collection<String> incoming)
	{
		LootshareSession active = activeSession();
		return active != null && active.trustDecisionAuthorizations(incoming);
	}

	public synchronized boolean canTrustDecisionKeys(Map<String, Long> keys)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return false;
		}
		try
		{
			LootshareSession validation = new LootshareSession("keys", active.getPartyId(), active.getStartedAt());
			validation.trustDecisionKeys(active.getDecisionKeys());
			validation.trustDecisionKeys(keys);
			return true;
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}

	public synchronized boolean trustDecisionKeys(Map<String, Long> keys)
	{
		LootshareSession active = activeSession();
		return active != null && active.trustDecisionKeys(keys);
	}

	/** The controller must authenticate peer receipts before calling this host-only import. */
	public synchronized DecisionOutcome importFinalizedProposal(long hostMemberId, LootProposal proposal)
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return new DecisionOutcome(MutationResult.NO_ACTIVE_SESSION, null);
		}
		if (hostMemberId != active.getHostMemberId())
		{
			return new DecisionOutcome(MutationResult.NOT_HOST, null);
		}
		try
		{
			LootProposal finalized = proposal.validatedCopy();
			if (finalized.getPartyId() != active.getPartyId() || finalized.getStatus() == LootProposalStatus.PENDING)
			{
				return new DecisionOutcome(MutationResult.INVALID, null);
			}
			LootProposal existing = findProposal(finalized.getProposalId());
			if (existing != null && (!existing.hasSameIdentity(finalized)
				|| (existing.getStatus() != LootProposalStatus.PENDING
					&& (existing.getStatus() != finalized.getStatus()
						|| !existing.getDecidedAt().equals(finalized.getDecidedAt())
						|| !existing.getParticipants().equals(finalized.getParticipants())))))
			{
				return new DecisionOutcome(MutationResult.CONFLICT, existing);
			}
			if (existing != null && existing.getStatus() != LootProposalStatus.PENDING)
			{
				if (existing.getDecisionReceipt() != null || finalized.getDecisionReceipt() == null)
				{
					return new DecisionOutcome(MutationResult.DUPLICATE, existing);
				}
				active.replaceFinalizedProposal(finalized);
			}
			else if (finalized.getStatus() == LootProposalStatus.ACCEPTED)
			{
				active.addAcceptedProposal(finalized);
			}
			else
			{
				active.addRejectedProposal(finalized);
			}
			// Finalized records can live solely in the archive when the transport index is full.
			if (proposals.containsKey(finalized.getProposalId()) || proposals.size() < MAX_PROPOSALS)
			{
				proposals.put(finalized.getProposalId(), finalized);
			}
			return new DecisionOutcome(MutationResult.APPLIED, finalized);
		}
		catch (RuntimeException e)
		{
			return new DecisionOutcome(MutationResult.INVALID, null);
		}
	}

	public synchronized List<LootProposal> getPendingOwnedBy(long memberId)
	{
		List<LootProposal> pending = new ArrayList<>();
		LootshareSession active = activeSession();
		if (active == null)
		{
			return pending;
		}
		for (LootProposal proposal : proposals.values())
		{
			if (proposal.getPartyId() == active.getPartyId()
				&& proposal.getOwnerMemberId() == memberId
				&& proposal.getStatus() == LootProposalStatus.PENDING)
			{
				pending.add(proposal);
			}
		}
		return pending;
	}

	public synchronized List<LootProposal> getProposalsForActiveParty()
	{
		LootshareSession active = activeSession();
		if (active == null)
		{
			return Collections.emptyList();
		}
		Map<String, LootProposal> matching = new LinkedHashMap<>();
		for (LootProposal accepted : active.getAcceptedProposals())
		{
			matching.put(accepted.getProposalId(), accepted);
		}
		for (LootProposal rejected : active.getRejectedProposals())
		{
			matching.put(rejected.getProposalId(), rejected);
		}
		for (LootProposal proposal : proposals.values())
		{
			if (proposal.getPartyId() == active.getPartyId())
			{
				matching.put(proposal.getProposalId(), proposal);
			}
		}
		return new ArrayList<>(matching.values());
	}

	public synchronized List<LootProposal> getPendingProposalsForActiveParty()
	{
		List<LootProposal> pending = new ArrayList<>();
		for (LootProposal proposal : getProposalsForActiveParty())
		{
			if (proposal.getStatus() == LootProposalStatus.PENDING)
			{
				pending.add(proposal);
			}
		}
		return pending;
	}

	public synchronized Optional<LootshareSession> getActiveSession()
	{
		LootshareSession active = activeSession();
		return active == null ? Optional.empty() : Optional.of(active.snapshot());
	}

	public synchronized List<LootshareSession> getHistory()
	{
		List<LootshareSession> history = new ArrayList<>(sessions.size());
		for (LootshareSession session : sessions)
		{
			history.add(session.snapshot());
		}
		return Collections.unmodifiableList(history);
	}

	public synchronized long getActivePartyId()
	{
		LootshareSession active = activeSession();
		return active == null ? 0L : active.getPartyId();
	}

	public synchronized long getActiveHostMemberId()
	{
		LootshareSession active = activeSession();
		return active == null ? 0L : active.getHostMemberId();
	}

	public synchronized long getActiveMinimumSharedLootValue()
	{
		LootshareSession active = activeSession();
		return active == null ? 0L : active.getMinimumSharedLootValue();
	}

	public synchronized Optional<LootshareSettings> getActiveHostSettings()
	{
		LootshareSession active = activeSession();
		return active == null || active.getHostMemberId() <= 0L || active.getHostRevision() <= 0L
			? Optional.empty()
			: Optional.of(active.getHostSettings());
	}

	public synchronized long getActiveHostRevision()
	{
		LootshareSession active = activeSession();
		return active == null ? 0L : active.getHostRevision();
	}

	public synchronized MemberApprovalStatus getActiveMemberApprovalStatus(long memberId)
	{
		LootshareSession active = activeSession();
		return active == null || memberId <= 0L
			? MemberApprovalStatus.PENDING
			: active.getMemberApprovalStatus(memberId);
	}

	public synchronized Map<Long, MemberApprovalStatus> getActiveMemberApprovalStatuses()
	{
		LootshareSession active = activeSession();
		return active == null ? Collections.emptyMap() : active.getMemberApprovalStatuses();
	}

	private boolean removeOldestCompletedSession()
	{
		for (int index = 0; index < sessions.size(); index++)
		{
			if (!sessions.get(index).isActive())
			{
				sessions.remove(index);
				return true;
			}
		}
		return false;
	}

	private boolean isAcceptedInAnySession(String proposalId)
	{
		for (LootshareSession session : sessions)
		{
			if (session.getAcceptedProposals().stream()
				.anyMatch(proposal -> proposal.getProposalId().equals(proposalId)))
			{
				return true;
			}
		}
		return false;
	}

	private LootProposal findProposal(String proposalId)
	{
		LootProposal indexed = proposals.get(proposalId);
		if (indexed != null)
		{
			return indexed;
		}
		return findArchivedProposal(proposalId);
	}

	private LootProposal findArchivedProposal(String proposalId)
	{
		for (LootshareSession session : sessions)
		{
			for (LootProposal accepted : session.getAcceptedProposals())
			{
				if (accepted.getProposalId().equals(proposalId))
				{
					return accepted;
				}
			}
			for (LootProposal rejected : session.getRejectedProposals())
			{
				if (rejected.getProposalId().equals(proposalId))
				{
					return rejected;
				}
			}
		}
		return null;
	}

	private LootshareSession activeSession()
	{
		LootshareSession session = findSession(activeSessionId);
		return session != null && session.isActive() ? session : null;
	}

	private LootshareSession findSession(String sessionId)
	{
		if (sessionId == null)
		{
			return null;
		}
		for (LootshareSession session : sessions)
		{
			if (sessionId.equals(session.getSessionId()))
			{
				return session;
			}
		}
		return null;
	}

	public enum MutationResult
	{
		APPLIED,
		DUPLICATE,
		CONFLICT,
		NOT_FOUND,
		NOT_HOST,
		NO_ACTIVE_SESSION,
		INVALID,
		LIMIT_REACHED
	}

	public static final class DecisionOutcome
	{
		private final MutationResult result;
		private final LootProposal proposal;

		public DecisionOutcome(MutationResult result, LootProposal proposal)
		{
			this.result = result;
			this.proposal = proposal;
		}

		public MutationResult getResult()
		{
			return result;
		}

		public LootProposal getProposal()
		{
			return proposal;
		}
	}
}
