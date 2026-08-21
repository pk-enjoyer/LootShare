/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.domain;

import org.junit.Assert;
import org.junit.Test;

public class LootshareSettingsTest
{
	@Test
	public void keepsTheThreeSharedHostSettings()
	{
		LootshareSettings settings = new LootshareSettings(100_000L, LootValueBasis.HIGH_ALCHEMY, true);
		Assert.assertEquals(100_000L, settings.getMinimumSharedLootValue());
		Assert.assertEquals(LootValueBasis.HIGH_ALCHEMY, settings.getLootValueBasis());
		Assert.assertTrue(settings.isIncludeLoggedOutMembers());
	}
}
