/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.debug;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.sessions.CommunityLootshareEngine;
import com.communitylootshare.sessions.CommunityLootshareEngine.DecisionOutcome;
import com.communitylootshare.sessions.CommunityLootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import net.runelite.api.gameval.ItemID;

/**
 * In-memory Community Lootshare scenario used by developer-mode UI controls.
 *
 * <p>This session deliberately owns a separate engine. It never mutates RuneLite's
 * {@code PartyService}, sends Party messages, or enters the persisted live engine.</p>
 */
@Singleton
public class CommunityLootshareDebugSession
{
	public static final long DEBUG_PARTY_ID = 8_000_000_000_000_000_000L;
	public static final long DEBUG_OWNER_MEMBER_ID = 9_000_000_000_000_000_000L;
	public static final long MAX_SAMPLE_VALUE = Integer.MAX_VALUE;
	public static final String DEBUG_OWNER_NAME = "Debug owner";

	private static final String DEBUG_SESSION_ID = "community-lootshare-debug";
	private static final String DEBUG_SOURCE_LABEL = "Debug loot";
	private static final String TOA_SOURCE_LABEL = "Tombs of Amascut (debug)";
	private final boolean developerMode;
	private final CommunityLootshareEngine engine;
	private final LootshareCalculator calculator;
	private final List<LootshareParticipant> participants = new ArrayList<>();
	private boolean active;
	private int nextMemberSequence;
	private int nextProposalSequence;
	@Inject
	public CommunityLootshareDebugSession(@Named("developerMode") boolean developerMode,
	                                      LootshareCalculator calculator)
	{
		this.developerMode = developerMode;
		this.engine = new CommunityLootshareEngine();
		this.calculator = calculator;
	}

	public boolean isAvailable()
	{
		return developerMode;
	}

	public synchronized void startSimulation()
	{
		if (!developerMode || active)
		{
			return;
		}
		resetState();
		active = true;
	}

	public synchronized void stopSimulation()
	{
		active = false;
		engine.restore(null);
		participants.clear();
		nextMemberSequence = 0;
		nextProposalSequence = 0;
	}

	public synchronized void resetSimulation()
	{
		if (active)
		{
			resetState();
		}
	}

	public synchronized boolean isActive()
	{
		return active;
	}

	public synchronized Optional<LootshareParticipant> addFakePlayer(String displayName)
	{
		if (!active || participants.size() >= LootProposal.MAX_PARTICIPANTS
			|| hasDisplayName(displayName))
		{
			return Optional.empty();
		}

		try
		{
			long memberId = Math.addExact(DEBUG_OWNER_MEMBER_ID, ++nextMemberSequence);
			LootshareParticipant participant = new LootshareParticipant(memberId, displayName);
			participants.add(participant);
			return Optional.of(participant);
		}
		catch (IllegalArgumentException | ArithmeticException ignored)
		{
			return Optional.empty();
		}
	}

	public synchronized boolean removeFakePlayer(long memberId)
	{
		if (!active || memberId == DEBUG_OWNER_MEMBER_ID)
		{
			return false;
		}
		if (findParticipant(memberId) == null)
		{
			return false;
		}
		engine.updateMemberApprovalStatus(DEBUG_OWNER_MEMBER_ID, memberId, MemberApprovalStatus.PENDING,
			engine.getActiveHostRevision() + 1L);
		return participants.removeIf(participant -> participant.getMemberId() == memberId);
	}

	public synchronized Optional<LootProposal> addSampleProposal(long totalValue)
	{
		return addSampleProposal(DEBUG_OWNER_MEMBER_ID, LootPreset.COINS, totalValue);
	}

	public synchronized Optional<LootProposal> addSampleProposal(long ownerMemberId, long totalValue)
	{
		return addSampleProposal(ownerMemberId, LootPreset.COINS, totalValue);
	}

	public synchronized Optional<LootProposal> addSampleProposal(long ownerMemberId, LootPreset lootPreset,
	                                                             long totalValue)
	{
		if (!active || lootPreset == null || totalValue <= 0L || totalValue > MAX_SAMPLE_VALUE)
		{
			return Optional.empty();
		}
		LootshareParticipant owner = findParticipant(ownerMemberId);
		if (owner == null)
		{
			return Optional.empty();
		}

		int proposalSequence = ++nextProposalSequence;
		Instant capturedAt = Instant.EPOCH.plusSeconds(proposalSequence * 2L);
		SharedLootEvent event = new SharedLootEvent(
			"debug-proposal-" + proposalSequence,
			owner.getDisplayName(),
			lootPreset.sourceLabel,
			capturedAt,
			Collections.singletonList(lootPreset.createItem(totalValue)));
		LootProposal proposal = LootProposal.pending(DEBUG_PARTY_ID, ownerMemberId, event);
		if (engine.addProposal(proposal) != MutationResult.APPLIED)
		{
			return Optional.empty();
		}
		decideProposalForCurrentEligibility(proposal);
		return engine.getProposal(proposal.getProposalId());
	}

	public synchronized MutationResult setMemberApproved(long memberId, boolean approved)
	{
		if (!active)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		if (findParticipant(memberId) == null)
		{
			return MutationResult.NOT_FOUND;
		}
		MemberApprovalStatus status = approved
			? MemberApprovalStatus.APPROVED
			: MemberApprovalStatus.EXCLUDED;
		if (memberId == DEBUG_OWNER_MEMBER_ID && status != MemberApprovalStatus.APPROVED)
		{
			return MutationResult.INVALID;
		}
		if (engine.getActiveMemberApprovalStatus(memberId) == status)
		{
			return MutationResult.DUPLICATE;
		}
		return engine.updateMemberApprovalStatus(DEBUG_OWNER_MEMBER_ID, memberId, status,
			engine.getActiveHostRevision() + 1L);
	}

	public synchronized MutationResult approveProposal(String proposalId)
	{
		return decideProposal(proposalId, LootProposalStatus.ACCEPTED);
	}

	public synchronized MutationResult rejectProposal(String proposalId)
	{
		return decideProposal(proposalId, LootProposalStatus.REJECTED);
	}

	public synchronized Snapshot snapshot()
	{
		if (!active)
		{
			return Snapshot.inactive();
		}
		return new Snapshot(
			true,
			DEBUG_PARTY_ID,
			DEBUG_OWNER_MEMBER_ID,
			participants,
			engine.getProposalsForActiveParty(),
			calculator.calculate(engine.getActiveSession().orElse(null)),
			engine.getActiveSession().orElse(null));
	}

	private MutationResult decideProposal(String proposalId, LootProposalStatus decision)
	{
		if (!active)
		{
			return MutationResult.NO_ACTIVE_SESSION;
		}
		LootProposal proposal = engine.getProposal(proposalId).orElse(null);
		if (proposal == null)
		{
			return MutationResult.NOT_FOUND;
		}
		Instant decidedAt = proposal.getEvent().getCapturedAt().plusSeconds(1L);
		List<LootshareParticipant> roster = decision == LootProposalStatus.ACCEPTED
			? approvedParticipants()
			: Collections.emptyList();
		if (decision == LootProposalStatus.ACCEPTED && roster.stream()
			.noneMatch(participant -> participant.getMemberId() == proposal.getOwnerMemberId()))
		{
			roster.add(new LootshareParticipant(proposal.getOwnerMemberId(), proposal.getEvent().getRecipient()));
		}
		DecisionOutcome outcome = engine.decide(proposalId, DEBUG_OWNER_MEMBER_ID, decision, decidedAt, roster);
		return outcome.getResult();
	}

	private void decideProposalForCurrentEligibility(LootProposal proposal)
	{
		LootProposalStatus decision = engine.getActiveMemberApprovalStatus(proposal.getOwnerMemberId())
			== MemberApprovalStatus.APPROVED
			? LootProposalStatus.ACCEPTED
			: LootProposalStatus.REJECTED;
		decideProposal(proposal.getProposalId(), decision);
	}

	private List<LootshareParticipant> approvedParticipants()
	{
		List<LootshareParticipant> approved = new ArrayList<>();
		for (LootshareParticipant participant : participants)
		{
			if (engine.getActiveMemberApprovalStatus(participant.getMemberId()) == MemberApprovalStatus.APPROVED)
			{
				approved.add(participant);
			}
		}
		return approved;
	}

	private void resetState()
	{
		engine.restore(null);
		participants.clear();
		participants.add(new LootshareParticipant(DEBUG_OWNER_MEMBER_ID, DEBUG_OWNER_NAME));
		nextMemberSequence = 0;
		nextProposalSequence = 0;
		MutationResult result = engine.enterParty(DEBUG_PARTY_ID, DEBUG_SESSION_ID, Instant.EPOCH);
		if (result != MutationResult.APPLIED)
		{
			throw new IllegalStateException("Unable to initialize the Community Lootshare debug session");
		}
		result = engine.updateHostState(DEBUG_OWNER_MEMBER_ID, DEBUG_OWNER_MEMBER_ID,
			LootshareSettings.defaults(0L), 1L);
		if (result != MutationResult.APPLIED)
		{
			throw new IllegalStateException("Unable to initialize the Community Lootshare debug host");
		}
	}

	private boolean hasDisplayName(String displayName)
	{
		if (displayName == null)
		{
			return false;
		}
		String normalized = displayName.trim();
		for (LootshareParticipant participant : participants)
		{
			if (participant.getDisplayName().equalsIgnoreCase(normalized))
			{
				return true;
			}
		}
		return false;
	}

	private LootshareParticipant findParticipant(long memberId)
	{
		for (LootshareParticipant participant : participants)
		{
			if (participant.getMemberId() == memberId)
			{
				return participant;
			}
		}
		return null;
	}

	/**
	 * Loot choices exposed by the developer simulation.
	 */
	public enum LootPreset
	{
		COINS("Coins", DEBUG_SOURCE_LABEL, ItemID.COINS, true),
		OSMUMTENS_FANG("Osmumten's fang", TOA_SOURCE_LABEL, ItemID.OSMUMTENS_FANG, false),
		LIGHTBEARER("Lightbearer", TOA_SOURCE_LABEL, ItemID.LIGHTBEARER, false),
		MASORI_BODY("Masori body", TOA_SOURCE_LABEL, ItemID.MASORI_BODY, false),
		TUMEKENS_SHADOW("Tumeken's shadow", TOA_SOURCE_LABEL, ItemID.TUMEKENS_SHADOW_UNCHARGED, false);

		private final String displayName;
		private final String sourceLabel;
		private final int itemId;
		private final boolean valueAsQuantity;

		LootPreset(String displayName, String sourceLabel, int itemId, boolean valueAsQuantity)
		{
			this.displayName = displayName;
			this.sourceLabel = sourceLabel;
			this.itemId = itemId;
			this.valueAsQuantity = valueAsQuantity;
		}

		public String getDisplayName()
		{
			return displayName;
		}

		public int getItemId()
		{
			return itemId;
		}

		public boolean usesItemPrice()
		{
			return !valueAsQuantity;
		}

		private SharedLootItem createItem(long totalValue)
		{
			long quantity = valueAsQuantity ? totalValue : 1L;
			long unitPrice = valueAsQuantity ? 1L : totalValue;
			return new SharedLootItem(itemId, itemId, quantity, unitPrice);
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	public static final class Snapshot
	{
		private final boolean active;
		private final long partyId;
		private final long ownerMemberId;
		private final List<LootshareParticipant> participants;
		private final List<LootProposal> proposals;
		private final LootshareCalculation calculation;
		private final LootshareSession session;

		private Snapshot(boolean active, long partyId, long ownerMemberId,
		                 List<LootshareParticipant> participants, List<LootProposal> proposals,
		                 LootshareCalculation calculation, LootshareSession session)
		{
			this.active = active;
			this.partyId = partyId;
			this.ownerMemberId = ownerMemberId;
			this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
			this.proposals = Collections.unmodifiableList(new ArrayList<>(proposals));
			this.calculation = calculation;
			this.session = session == null ? null : session.snapshot();
		}

		private static Snapshot inactive()
		{
			return new Snapshot(false, 0L, 0L, Collections.emptyList(), Collections.emptyList(),
				LootshareCalculation.empty(), null);
		}

		public boolean isActive()
		{
			return active;
		}

		public long getPartyId()
		{
			return partyId;
		}

		public long getOwnerMemberId()
		{
			return ownerMemberId;
		}

		public List<LootshareParticipant> getParticipants()
		{
			return participants;
		}

		public List<LootProposal> getProposals()
		{
			return proposals;
		}

		public LootshareCalculation getCalculation()
		{
			return calculation;
		}

		public LootshareSession getSession()
		{
			return session == null ? null : session.snapshot();
		}
	}
}
