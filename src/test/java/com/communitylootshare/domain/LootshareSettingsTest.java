/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class LootshareSettingsTest
{
	@Test
	public void defaultsAndValidatedCopiesExposeTheCompletePolicy()
	{
		LootshareSettings settings = LootshareSettings.defaults();

		assertEquals(LootshareSettings.DEFAULT_MINIMUM_SHARED_LOOT_VALUE,
			settings.getMinimumSharedLootValue());
		assertEquals(LootValueBasis.GRAND_EXCHANGE, settings.getLootValueBasis());
		assertTrue(settings.isCaptureNpcLoot());
		assertTrue(settings.isCaptureEventLoot());
		assertFalse(settings.isCapturePlayerLoot());
		assertFalse(settings.isCapturePickpocketLoot());
		assertTrue(settings.isCaptureUnknownLoot());
		assertFalse(settings.isIncludeLoggedOutMembers());
		assertFalse(settings.isAllowMemberManualGp());
		assertEquals("Grand Exchange", settings.getLootValueBasis().getDisplayName());
		assertEquals("Grand Exchange", settings.getLootValueBasis().toString());

		LootshareSettings copy = settings.validatedCopy();
		assertNotSame(settings, copy);
		assertEquals(settings, settings);
		assertEquals(settings, copy);
		assertEquals(settings.hashCode(), copy.hashCode());
		assertFalse(settings.equals(null));
		assertFalse(settings.equals("settings"));
	}

	@Test
	public void customSettingsAndInvalidInputsAreHandled()
	{
		LootshareSettings custom = new LootshareSettings(0L, LootValueBasis.HIGH_ALCHEMY,
			false, true, false, true, false, true);

		assertEquals(0L, custom.getMinimumSharedLootValue());
		assertEquals("High alchemy", custom.getLootValueBasis().getDisplayName());
		assertFalse(custom.isCaptureNpcLoot());
		assertTrue(custom.isCaptureEventLoot());
		assertFalse(custom.isCapturePlayerLoot());
		assertTrue(custom.isCapturePickpocketLoot());
		assertFalse(custom.isCaptureUnknownLoot());
		assertTrue(custom.isIncludeLoggedOutMembers());
		assertFalse(custom.isAllowMemberManualGp());

		LootshareSettings selfAttributionEnabled = new LootshareSettings(0L, LootValueBasis.HIGH_ALCHEMY,
			false, true, false, true, false, true, true);
		assertTrue(selfAttributionEnabled.isAllowMemberManualGp());
		assertFalse(selfAttributionEnabled.equals(custom));
		assertFalse(custom.equals(LootshareSettings.defaults(0L)));

		expectIllegal(() -> LootshareSettings.defaults(-1L));
		expectIllegal(() -> LootshareSettings.defaults(LootshareSettings.MAXIMUM_SHARED_LOOT_VALUE + 1L));
		expectNullPointer(() -> new LootshareSettings(0L, null,
			true, true, true, true, true, false));
	}

	private static void expectIllegal(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected.
		}
	}

	private static void expectNullPointer(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected NullPointerException");
		}
		catch (NullPointerException expected)
		{
			// Expected.
		}
	}
}
