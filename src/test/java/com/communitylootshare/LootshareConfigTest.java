/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LootshareConfigTest
{
	@Test
	public void exposesSafeDefaultsForAHostedParty()
	{
		LootshareConfig config = new LootshareConfig()
		{
		};

		assertEquals(LootshareConfig.DEFAULT_MINIMUM_SHARED_LOOT_VALUE,
			config.minimumSharedLootValue());
		assertTrue(config.captureNpcLoot());
		assertTrue(config.captureEventLoot());
		assertTrue(config.captureUnknownLoot());
		assertFalse(config.includeLoggedOutMembers());
	}
}
