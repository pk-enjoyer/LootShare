/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

/**
 * Price source frozen into newly captured loot while a host policy is active.
 */
public enum LootValueBasis
{
	GRAND_EXCHANGE("Grand Exchange"),
	HIGH_ALCHEMY("High alchemy");

	private final String displayName;

	LootValueBasis(String displayName)
	{
		this.displayName = displayName;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
