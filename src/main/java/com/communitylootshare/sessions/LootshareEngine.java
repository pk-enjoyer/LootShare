/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.sessions;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the sole beta.1 active ledger.
 */
@Singleton
@Slf4j
public class LootshareEngine
{
	public static final int MAX_PROPOSALS = 2048;
	private final Map<String, LootProposal> proposals = new LinkedHashMap<>();
	private LootshareSession activeSession;

	public synchronized void restore(LootshareState state)
	{
		proposals.clear();
		activeSession = null;
		if (state == null || state.getActiveSession() == null)
		{
			return;
		}
		try
		{
			activeSession = state.getActiveSession().snapshot();
			for (LootProposal p : state.getProposals())
			{
				LootProposal copy = p.validatedCopy();
				if (copy.getPartyId() == activeSession.getPartyId() && proposals.size() < MAX_PROPOSALS)
				{
					proposals.putIfAbsent(copy.getProposalId(), copy);
				}
			}
			for (LootProposal p : activeSession.getAcceptedProposals())
			{
				proposals.put(p.getProposalId(), p.validatedCopy());
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Skipping invalid active Community Lootshare ledger", e);
			proposals.clear();
			activeSession = null;
		}
	}

	public synchronized LootshareState snapshot()
	{
		LootshareState state = new LootshareState();
		state.setActiveSession(activeSession == null ? null : activeSession.snapshot());
		List<LootProposal> copies = new ArrayList<>();
		for (LootProposal p : proposals.values())
		{
			copies.add(p.validatedCopy());
		}
		state.setProposals(copies);
		return state;
	}

	public synchronized MutationResult enterParty(long partyId, String sessionId, Instant at)
	{
		if (activeSession != null && activeSession.getPartyId() == partyId)
		{
			return MutationResult.DUPLICATE;
		}
		try
		{
			activeSession = new LootshareSession(sessionId, partyId, at);
			proposals.clear();
			return MutationResult.APPLIED;
		}
		catch (RuntimeException e)
		{
			return MutationResult.INVALID;
		}
	}

	/**
	 * Intentional Party leave discards the active ledger.
	 */
	public synchronized MutationResult leaveParty(Instant at)
	{
		if (activeSession == null)
		{
			return MutationResult.DUPLICATE;
		}
		activeSession = null;
		proposals.clear();
		return MutationResult.APPLIED;
	}

	public synchronized MutationResult updateHostState(long acting, long host, long minimum, long revision)
	{
		if (activeSession == null)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (acting <= 0L || host <= 0L || minimum < 0L || revision <= 0L)
		{
			return MutationResult.INVALID;
		}
		long current = activeSession.getHostMemberId();
		if ((current == 0L && acting != host) || (current != 0L && acting != current))
		{
			return MutationResult.NOT_HOST;
		}
		if (revision < activeSession.getHostRevision())
		{
			return MutationResult.CONFLICT;
		}
		if (revision == activeSession.getHostRevision())
		{
			if (current == host && activeSession.getMinimumSharedLootValue() == minimum)
			{
				return MutationResult.DUPLICATE;
			}
			if (current != 0L)
			{
				return MutationResult.CONFLICT;
			}
		}
		try
		{
			activeSession.setHostState(host, minimum, revision);
			return MutationResult.APPLIED;
		}
		catch (RuntimeException e)
		{
			return MutationResult.INVALID;
		}
	}

	public synchronized MutationResult vacateHost(long member)
	{
		if (activeSession == null)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (member <= 0L)
		{
			return MutationResult.INVALID;
		}
		if (activeSession.getHostMemberId() == 0L)
		{
			return MutationResult.DUPLICATE;
		}
		if (activeSession.getHostMemberId() != member)
		{
			return MutationResult.NOT_HOST;
		}
		activeSession.clearHost();
		return MutationResult.APPLIED;
	}

	public synchronized MutationResult addProposal(LootProposal proposal)
	{
		if (proposal == null)
		{
			return MutationResult.INVALID;
		}
		try
		{
			proposal = proposal.validatedCopy();
		}
		catch (RuntimeException e)
		{
			return MutationResult.INVALID;
		}
		if (activeSession == null || proposal.getPartyId() != activeSession.getPartyId())
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		LootProposal old = proposals.get(proposal.getProposalId());
		if (old != null)
		{
			return old.hasSameIdentity(proposal) ? MutationResult.DUPLICATE : MutationResult.CONFLICT;
		}
		if (proposals.size() >= MAX_PROPOSALS)
		{
			return MutationResult.LIMIT_REACHED;
		}
		if (proposal.getStatus() != LootProposalStatus.PENDING)
		{
			return MutationResult.INVALID;
		}
		proposals.put(proposal.getProposalId(), proposal);
		return MutationResult.APPLIED;
	}

	public synchronized DecisionOutcome decide(String id, long deciding, LootProposalStatus decision, Instant at, List<LootshareParticipant> participants)
	{
		LootProposal old = proposals.get(id);
		if (old == null)
		{
			return new DecisionOutcome(MutationResult.NOT_FOUND, null);
		}
		if (activeSession == null || deciding != activeSession.getHostMemberId())
		{
			return new DecisionOutcome(MutationResult.NOT_HOST, old);
		}
		if (decision == null || decision == LootProposalStatus.PENDING)
		{
			return new DecisionOutcome(MutationResult.INVALID, old);
		}
		if (old.getStatus() != LootProposalStatus.PENDING)
		{
			return new DecisionOutcome(old.getStatus() == decision ? MutationResult.DUPLICATE : MutationResult.CONFLICT, old);
		}
		try
		{
			LootProposal next = old.decide(decision, at, participants == null ? Collections.emptyList() : participants);
			if (decision == LootProposalStatus.ACCEPTED)
			{
				activeSession.addAcceptedProposal(next);
			}
			proposals.put(id, next);
			return new DecisionOutcome(MutationResult.APPLIED, next);
		}
		catch (RuntimeException e)
		{
			return new DecisionOutcome(MutationResult.INVALID, old);
		}
	}

	public synchronized Optional<LootProposal> getProposal(String id)
	{
		return Optional.ofNullable(proposals.get(id));
	}

	public synchronized List<LootProposal> getPendingOwnedBy(long id)
	{
		List<LootProposal> r = new ArrayList<>();
		for (LootProposal p : getProposalsForActiveParty())
		{
			if (p.getOwnerMemberId() == id && p.getStatus() == LootProposalStatus.PENDING)
			{
				r.add(p);
			}
		}
		return r;
	}

	public synchronized List<LootProposal> getProposalsForActiveParty()
	{
		return activeSession == null ? Collections.emptyList() : new ArrayList<>(proposals.values());
	}

	public synchronized List<LootProposal> getPendingProposalsForActiveParty()
	{
		List<LootProposal> r = new ArrayList<>();
		for (LootProposal p : getProposalsForActiveParty())
		{
			if (p.getStatus() == LootProposalStatus.PENDING)
			{
				r.add(p);
			}
		}
		return r;
	}

	public synchronized Optional<LootshareSession> getActiveSession()
	{
		return activeSession == null ? Optional.empty() : Optional.of(activeSession.snapshot());
	}

	public synchronized long getActivePartyId()
	{
		return activeSession == null ? 0L : activeSession.getPartyId();
	}

	public synchronized long getActiveHostMemberId()
	{
		return activeSession == null ? 0L : activeSession.getHostMemberId();
	}

	public synchronized long getActiveMinimumSharedLootValue()
	{
		return activeSession == null ? 0L : activeSession.getMinimumSharedLootValue();
	}

	public synchronized long getActiveHostRevision()
	{
		return activeSession == null ? 0L : activeSession.getHostRevision();
	}

	public enum MutationResult
	{APPLIED, DUPLICATE, CONFLICT, NOT_FOUND, NOT_HOST, NO_ACTIVE_SESSION, INVALID, LIMIT_REACHED}

	public static final class DecisionOutcome
	{
		private final MutationResult result;
		private final LootProposal proposal;

		private DecisionOutcome(MutationResult r, LootProposal p)
		{
			result = r;
			proposal = p;
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
