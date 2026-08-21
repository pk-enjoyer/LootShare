/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.util.Objects;

/**
 * Immutable host-owned policy used by every member of a Community Lootshare Party.
 */
public final class LootshareSettings
{
	public static final long MAXIMUM_SHARED_LOOT_VALUE = Integer.MAX_VALUE;
	public static final long DEFAULT_MINIMUM_SHARED_LOOT_VALUE = 100_000L;

	private final long minimumSharedLootValue;
	private final LootValueBasis lootValueBasis;
	private final boolean captureNpcLoot;
	private final boolean captureEventLoot;
	private final boolean capturePlayerLoot;
	private final boolean capturePickpocketLoot;
	private final boolean captureUnknownLoot;
	private final boolean includeLoggedOutMembers;

	public LootshareSettings(long minimumSharedLootValue, LootValueBasis lootValueBasis,
	                         boolean captureNpcLoot, boolean captureEventLoot,
	                         boolean capturePlayerLoot, boolean capturePickpocketLoot,
	                         boolean captureUnknownLoot, boolean includeLoggedOutMembers)
	{
		if (minimumSharedLootValue < 0L || minimumSharedLootValue > MAXIMUM_SHARED_LOOT_VALUE)
		{
			throw new IllegalArgumentException("Minimum shared loot value is out of range");
		}
		this.minimumSharedLootValue = minimumSharedLootValue;
		this.lootValueBasis = Objects.requireNonNull(lootValueBasis, "lootValueBasis");
		this.captureNpcLoot = captureNpcLoot;
		this.captureEventLoot = captureEventLoot;
		this.capturePlayerLoot = capturePlayerLoot;
		this.capturePickpocketLoot = capturePickpocketLoot;
		this.captureUnknownLoot = captureUnknownLoot;
		this.includeLoggedOutMembers = includeLoggedOutMembers;
	}

	public static LootshareSettings defaults()
	{
		return defaults(DEFAULT_MINIMUM_SHARED_LOOT_VALUE);
	}

	public static LootshareSettings defaults(long minimumSharedLootValue)
	{
		return new LootshareSettings(minimumSharedLootValue, LootValueBasis.GRAND_EXCHANGE,
			true, true, true, true, true, false);
	}

	public LootshareSettings validatedCopy()
	{
		return new LootshareSettings(minimumSharedLootValue, lootValueBasis,
			captureNpcLoot, captureEventLoot, capturePlayerLoot, capturePickpocketLoot,
			captureUnknownLoot, includeLoggedOutMembers);
	}

	public long getMinimumSharedLootValue()
	{
		return minimumSharedLootValue;
	}

	public LootValueBasis getLootValueBasis()
	{
		return lootValueBasis;
	}

	public boolean isCaptureNpcLoot()
	{
		return captureNpcLoot;
	}

	public boolean isCaptureEventLoot()
	{
		return captureEventLoot;
	}

	public boolean isCapturePlayerLoot()
	{
		return capturePlayerLoot;
	}

	public boolean isCapturePickpocketLoot()
	{
		return capturePickpocketLoot;
	}

	public boolean isCaptureUnknownLoot()
	{
		return captureUnknownLoot;
	}

	public boolean isIncludeLoggedOutMembers()
	{
		return includeLoggedOutMembers;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(minimumSharedLootValue, lootValueBasis, captureNpcLoot,
			captureEventLoot, capturePlayerLoot, capturePickpocketLoot, captureUnknownLoot,
			includeLoggedOutMembers);
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
		return minimumSharedLootValue == that.minimumSharedLootValue
			&& captureNpcLoot == that.captureNpcLoot
			&& captureEventLoot == that.captureEventLoot
			&& capturePlayerLoot == that.capturePlayerLoot
			&& capturePickpocketLoot == that.capturePickpocketLoot
			&& captureUnknownLoot == that.captureUnknownLoot
			&& includeLoggedOutMembers == that.includeLoggedOutMembers
			&& lootValueBasis == that.lootValueBasis;
	}
}
