/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class LootshareParticipant
{
	public static final int MAX_DISPLAY_NAME_LENGTH = 64;

	private final long memberId;
	private final String displayName;

	public LootshareParticipant(long memberId, String displayName)
	{
		if (memberId <= 0L)
		{
			throw new IllegalArgumentException("Party member ID must be positive");
		}
		if (displayName == null || displayName.trim().isEmpty()
			|| displayName.trim().length() > MAX_DISPLAY_NAME_LENGTH)
		{
			throw new IllegalArgumentException("Display name must be non-blank and at most "
				+ MAX_DISPLAY_NAME_LENGTH + " characters");
		}
		this.memberId = memberId;
		this.displayName = displayName.trim();
	}

	public long getMemberId()
	{
		return memberId;
	}

	public String getDisplayName()
	{
		return displayName;
	}
}
