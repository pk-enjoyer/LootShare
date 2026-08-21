/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.util.Objects;

public final class LootshareSettings
{
	private final long minimumSharedLootValue;
	private final LootValueBasis lootValueBasis;
	private final boolean includeLoggedOutMembers;

	public LootshareSettings(long minimumSharedLootValue, LootValueBasis lootValueBasis,
		boolean includeLoggedOutMembers)
	{
		if (minimumSharedLootValue < 0L)
		{
			throw new IllegalArgumentException("Minimum shared loot value cannot be negative");
		}
		this.minimumSharedLootValue = minimumSharedLootValue;
		this.lootValueBasis = Objects.requireNonNull(lootValueBasis, "lootValueBasis");
		this.includeLoggedOutMembers = includeLoggedOutMembers;
	}

	public long getMinimumSharedLootValue() { return minimumSharedLootValue; }
	public LootValueBasis getLootValueBasis() { return lootValueBasis; }
	public boolean isIncludeLoggedOutMembers() { return includeLoggedOutMembers; }
}
