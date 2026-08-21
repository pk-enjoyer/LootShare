/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.LootshareSettings;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Compact beta host-policy snapshot.
 */
public class HostMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;
	private int protocolVersion = PROTOCOL_VERSION;
	private long hostMemberId;
	private long minimumSharedLootValue;
	private String lootValueBasis;
	private boolean includeLoggedOutMembers;
	private long revision;

	public HostMessage()
	{
	}

	public HostMessage(long hostMemberId, LootshareSettings settings, long revision)
	{
		if (hostMemberId <= 0L || settings == null || revision <= 0L)
		{
			throw new IllegalArgumentException("A host, settings, and revision are required");
		}
		this.hostMemberId = hostMemberId;
		this.minimumSharedLootValue = settings.getMinimumSharedLootValue();
		this.lootValueBasis = settings.getLootValueBasis().name();
		this.includeLoggedOutMembers = settings.isIncludeLoggedOutMembers();
		this.revision = revision;
	}

	public Optional<DecodedHostState> decode()
	{
		if (protocolVersion != PROTOCOL_VERSION || getMemberId() <= 0L || hostMemberId <= 0L || revision <= 0L)
		{
			return Optional.empty();
		}
		try
		{
			return Optional.of(new DecodedHostState(hostMemberId,
				new LootshareSettings(minimumSharedLootValue, LootValueBasis.valueOf(lootValueBasis), includeLoggedOutMembers), revision));
		}
		catch (IllegalArgumentException | NullPointerException ignored)
		{
			return Optional.empty();
		}
	}

	public static final class DecodedHostState
	{
		private final long hostMemberId;
		private final LootshareSettings settings;
		private final long revision;

		private DecodedHostState(long hostMemberId, LootshareSettings settings, long revision)
		{
			this.hostMemberId = hostMemberId;
			this.settings = settings;
			this.revision = revision;
		}

		public long getHostMemberId()
		{
			return hostMemberId;
		}

		public LootshareSettings getSettings()
		{
			return settings;
		}

		public long getRevision()
		{
			return revision;
		}
	}
}
