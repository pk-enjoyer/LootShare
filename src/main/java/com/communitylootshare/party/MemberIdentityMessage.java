/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.party;

import com.communitylootshare.domain.SharedLootEvent;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Announces the sending Party member's current character name.
 */
public class MemberIdentityMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;

	private int protocolVersion = PROTOCOL_VERSION;
	private String displayName;

	public MemberIdentityMessage()
	{
	}

	public MemberIdentityMessage(String displayName)
	{
		if (!isValidDisplayName(displayName))
		{
			throw new IllegalArgumentException("Member display name is invalid");
		}
		this.displayName = displayName.trim();
	}

	private static boolean isValidDisplayName(String value)
	{
		return value != null && !value.trim().isEmpty()
			&& !"<unknown>".equalsIgnoreCase(value.trim())
			&& value.trim().length() <= SharedLootEvent.MAX_RECIPIENT_LENGTH;
	}

	public Optional<String> decode()
	{
		if (protocolVersion != PROTOCOL_VERSION || getMemberId() <= 0L || !isValidDisplayName(displayName))
		{
			return Optional.empty();
		}
		return Optional.of(displayName.trim());
	}

	public int getProtocolVersion()
	{
		return protocolVersion;
	}

	public String getDisplayName()
	{
		return displayName;
	}
}
