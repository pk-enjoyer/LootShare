/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare;

import com.communitylootshare.domain.LootValueBasis;
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
		assertEquals(LootValueBasis.GRAND_EXCHANGE, config.lootValueBasis());
		assertTrue(config.captureNpcLoot());
		assertTrue(config.captureEventLoot());
		assertTrue(config.capturePlayerLoot());
		assertTrue(config.capturePickpocketLoot());
		assertTrue(config.captureUnknownLoot());
		assertFalse(config.includeLoggedOutMembers());
		assertFalse(config.allowMemberManualGp());
	}
}
