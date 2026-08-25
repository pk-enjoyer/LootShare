/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

public class HostMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;
	private int protocolVersion = PROTOCOL_VERSION;
	private long hostMemberId;
	private long minimumSharedLootValue;
	private long revision;

	public HostMessage()
	{
	}

	public HostMessage(long hostMemberId, long minimumSharedLootValue, long revision)
	{
		if (hostMemberId <= 0L || minimumSharedLootValue < 0L || revision <= 0L)
		{
			throw new IllegalArgumentException("Valid host state is required");
		}
		this.hostMemberId = hostMemberId;
		this.minimumSharedLootValue = minimumSharedLootValue;
		this.revision = revision;
	}

	public Optional<DecodedHostState> decode()
	{
		return protocolVersion == PROTOCOL_VERSION && getMemberId() > 0L && hostMemberId > 0L && minimumSharedLootValue >= 0L && revision > 0L ? Optional.of(new DecodedHostState(hostMemberId, minimumSharedLootValue, revision)) : Optional.empty();
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
		return minimumSharedLootValue;
	}

	public long getRevision()
	{
		return revision;
	}

	public static final class DecodedHostState
	{
		private final long hostMemberId, minimumSharedLootValue, revision;

		private DecodedHostState(long h, long m, long r)
		{
			hostMemberId = h;
			minimumSharedLootValue = m;
			revision = r;
		}

		public long getHostMemberId()
		{
			return hostMemberId;
		}

		public long getMinimumSharedLootValue()
		{
			return minimumSharedLootValue;
		}

		public long getRevision()
		{
			return revision;
		}
	}
}
