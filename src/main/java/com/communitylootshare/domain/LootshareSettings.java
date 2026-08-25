/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;


/**
 * Immutable host-owned policy used by every member of a Community Lootshare Party.
 */
public final class LootshareSettings
{
	public static final long MAXIMUM_SHARED_LOOT_VALUE = Integer.MAX_VALUE;
	public static final long DEFAULT_MINIMUM_SHARED_LOOT_VALUE = 100_000L;

	private final long minimumSharedLootValue;

	public LootshareSettings(long minimumSharedLootValue)
	{
		if (minimumSharedLootValue < 0L || minimumSharedLootValue > MAXIMUM_SHARED_LOOT_VALUE)
		{
			throw new IllegalArgumentException("Minimum shared loot value is out of range");
		}
		this.minimumSharedLootValue = minimumSharedLootValue;
	}

	public static LootshareSettings defaults()
	{
		return defaults(DEFAULT_MINIMUM_SHARED_LOOT_VALUE);
	}

	public static LootshareSettings defaults(long minimumSharedLootValue)
	{
		return new LootshareSettings(minimumSharedLootValue);
	}

	public LootshareSettings validatedCopy()
	{
		return new LootshareSettings(minimumSharedLootValue);
	}

	public long getMinimumSharedLootValue()
	{
		return minimumSharedLootValue;
	}

	@Override
	public int hashCode()
	{
		return Long.hashCode(minimumSharedLootValue);
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof LootshareSettings))
		{
			return false;
		}
		LootshareSettings that = (LootshareSettings) other;
		return minimumSharedLootValue == that.minimumSharedLootValue;
	}
}
