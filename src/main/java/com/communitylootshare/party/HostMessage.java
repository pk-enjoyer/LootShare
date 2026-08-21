/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.party;

import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Synchronizes Community Lootshare host authority and the complete host-owned policy.
 */
public class HostMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 4;

	private int protocolVersion = PROTOCOL_VERSION;
	private long hostMemberId;
	private Long minimumSharedLootValue;
	private String lootValueBasis;
	private Boolean captureNpcLoot;
	private Boolean captureEventLoot;
	private Boolean capturePlayerLoot;
	private Boolean capturePickpocketLoot;
	private Boolean captureUnknownLoot;
	private Boolean includeLoggedOutMembers;
	private Boolean allowMemberManualGp;
	private List<MemberApproval> memberApprovals;
	private long revision;

	public HostMessage()
	{
	}

	public HostMessage(long hostMemberId, long minimumSharedLootValue, long revision)
	{
		this(hostMemberId, LootshareSettings.defaults(minimumSharedLootValue), revision);
	}

	public HostMessage(long hostMemberId, LootshareSettings settings, long revision)
	{
		this(hostMemberId, settings, revision,
			Collections.singletonMap(hostMemberId, MemberApprovalStatus.APPROVED));
	}

	public HostMessage(long hostMemberId, LootshareSettings settings, long revision,
	                                     Map<Long, MemberApprovalStatus> approvalStatuses)
	{
		if (hostMemberId <= 0L || settings == null || revision <= 0L || approvalStatuses == null
			|| approvalStatuses.size() > LootshareSession.MAX_MEMBER_APPROVALS)
		{
			throw new IllegalArgumentException("A valid host, settings, approvals, and revision are required");
		}
		LootshareSettings validatedSettings = settings.validatedCopy();
		this.hostMemberId = hostMemberId;
		this.minimumSharedLootValue = validatedSettings.getMinimumSharedLootValue();
		this.lootValueBasis = validatedSettings.getLootValueBasis().name();
		this.captureNpcLoot = validatedSettings.isCaptureNpcLoot();
		this.captureEventLoot = validatedSettings.isCaptureEventLoot();
		this.capturePlayerLoot = validatedSettings.isCapturePlayerLoot();
		this.capturePickpocketLoot = validatedSettings.isCapturePickpocketLoot();
		this.captureUnknownLoot = validatedSettings.isCaptureUnknownLoot();
		this.includeLoggedOutMembers = validatedSettings.isIncludeLoggedOutMembers();
		this.allowMemberManualGp = validatedSettings.isAllowMemberManualGp();
		Map<Long, MemberApprovalStatus> normalized = validateApprovals(hostMemberId, approvalStatuses);
		this.memberApprovals = new ArrayList<>(normalized.size());
		for (Map.Entry<Long, MemberApprovalStatus> entry : normalized.entrySet())
		{
			this.memberApprovals.add(new MemberApproval(entry.getKey(), entry.getValue().name()));
		}
		this.memberApprovals.sort(Comparator.comparingLong(MemberApproval::getMemberId));
		this.revision = revision;
	}

	private static Map<Long, MemberApprovalStatus> validateApprovals(long hostMemberId,
	                                                                 Map<Long, MemberApprovalStatus> approvals)
	{
		Map<Long, MemberApprovalStatus> validated = new LinkedHashMap<>();
		for (Map.Entry<Long, MemberApprovalStatus> entry : approvals.entrySet())
		{
			Long memberId = entry.getKey();
			MemberApprovalStatus status = entry.getValue();
			if (memberId == null || memberId <= 0L || status == null)
			{
				throw new IllegalArgumentException("Member approvals must contain valid members and statuses");
			}
			if (status != MemberApprovalStatus.PENDING)
			{
				validated.put(memberId, status);
			}
		}
		MemberApprovalStatus hostStatus = validated.get(hostMemberId);
		if (hostStatus != null && hostStatus != MemberApprovalStatus.APPROVED)
		{
			throw new IllegalArgumentException("The host must be approved");
		}
		validated.put(hostMemberId, MemberApprovalStatus.APPROVED);
		if (validated.size() > LootshareSession.MAX_MEMBER_APPROVALS)
		{
			throw new IllegalArgumentException("Too many member approvals");
		}
		return validated;
	}

	/**
	 * Validates a host policy snapshot received through Party transport. Versions 3 and 4 are
	 * understood so existing Parties can migrate without losing authority state.
	 */
	public Optional<DecodedHostState> decode()
	{
		if ((protocolVersion != 3 && protocolVersion != PROTOCOL_VERSION) || getMemberId() <= 0L
			|| hostMemberId <= 0L || revision <= 0L || minimumSharedLootValue == null
			|| captureNpcLoot == null || captureEventLoot == null || capturePlayerLoot == null
			|| capturePickpocketLoot == null || captureUnknownLoot == null
			|| includeLoggedOutMembers == null || memberApprovals == null
			|| memberApprovals.size() > LootshareSession.MAX_MEMBER_APPROVALS)
		{
			return Optional.empty();
		}
		try
		{
			LootValueBasis basis = LootValueBasis.valueOf(lootValueBasis);
			LootshareSettings settings = new LootshareSettings(minimumSharedLootValue, basis,
				captureNpcLoot.booleanValue(), captureEventLoot.booleanValue(), capturePlayerLoot.booleanValue(),
				capturePickpocketLoot.booleanValue(), captureUnknownLoot.booleanValue(),
				includeLoggedOutMembers.booleanValue(),
				allowMemberManualGp != null && allowMemberManualGp.booleanValue());
			Map<Long, MemberApprovalStatus> approvals = new LinkedHashMap<>();
			for (MemberApproval approval : memberApprovals)
			{
				if (approval == null || approval.memberId <= 0L || approval.status == null
					|| approvals.put(approval.memberId, MemberApprovalStatus.valueOf(approval.status)) != null)
				{
					return Optional.empty();
				}
			}
			if (approvals.get(hostMemberId) != MemberApprovalStatus.APPROVED)
			{
				return Optional.empty();
			}
			approvals = validateApprovals(hostMemberId, approvals);
			return Optional.of(new DecodedHostState(hostMemberId, settings, revision, approvals));
		}
		catch (IllegalArgumentException | NullPointerException ignored)
		{
			return Optional.empty();
		}
	}

	public int getProtocolVersion()
	{
		return protocolVersion;
	}

	public long getHostMemberId()
	{
		return hostMemberId;
	}

	public long getMinimumSharedLootValue()
	{
		return minimumSharedLootValue == null ? 0L : minimumSharedLootValue;
	}

	public String getLootValueBasis()
	{
		return lootValueBasis;
	}

	public long getRevision()
	{
		return revision;
	}

	private static final class MemberApproval
	{
		private long memberId;
		private String status;

		private MemberApproval()
		{
		}

		private MemberApproval(long memberId, String status)
		{
			this.memberId = memberId;
			this.status = status;
		}

		private long getMemberId()
		{
			return memberId;
		}
	}

	public static final class DecodedHostState
	{
		private final long hostMemberId;
		private final LootshareSettings settings;
		private final long revision;
		private final Map<Long, MemberApprovalStatus> approvalStatuses;

		private DecodedHostState(long hostMemberId, LootshareSettings settings, long revision,
		                         Map<Long, MemberApprovalStatus> approvalStatuses)
		{
			this.hostMemberId = hostMemberId;
			this.settings = settings.validatedCopy();
			this.revision = revision;
			this.approvalStatuses = Collections.unmodifiableMap(new LinkedHashMap<>(approvalStatuses));
		}

		public long getHostMemberId()
		{
			return hostMemberId;
		}

		public long getMinimumSharedLootValue()
		{
			return settings.getMinimumSharedLootValue();
		}

		public LootshareSettings getSettings()
		{
			return settings.validatedCopy();
		}

		public long getRevision()
		{
			return revision;
		}

		public Map<Long, MemberApprovalStatus> getApprovalStatuses()
		{
			return approvalStatuses;
		}
	}
}
