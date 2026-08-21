/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.capture;

import com.communitylootshare.domain.SharedLootEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LootCaptureServiceTest
{
	private ItemManager itemManager;
	private LootCaptureService service;

	@Before
	public void setUp()
	{
		itemManager = mock(ItemManager.class);
		when(itemManager.canonicalize(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
		when(itemManager.getItemPrice(anyInt())).thenReturn(50);
		AtomicInteger ids = new AtomicInteger();
		service = new LootCaptureService(itemManager, () -> "proposal-" + ids.incrementAndGet());
	}

	@Test
	public void capturesAggregatedCanonicalPriceSnapshotsAtThreshold()
	{
		LootReceived received = loot("Boss", Arrays.asList(new ItemStack(100, 2), new ItemStack(100, 1)));
		Optional<SharedLootEvent> result = service.capture(received, "Alice",
			Instant.ofEpochSecond(1, 123_456_789L), 5L, 150L);

		assertTrue(result.isPresent());
		SharedLootEvent event = result.get();
		assertEquals("proposal-1", event.getProposalId());
		assertEquals("Alice", event.getRecipient());
		assertEquals("Boss", event.getSourceLabel());
		assertEquals(Instant.ofEpochMilli(1_123L), event.getCapturedAt());
		assertEquals(1, event.getItems().size());
		assertEquals(100, event.getItems().get(0).getItemId());
		assertEquals(100, event.getItems().get(0).getPricingId());
		assertEquals(3L, event.getItems().get(0).getQuantity());
		assertEquals(50L, event.getItems().get(0).getUnitPrice());
		assertEquals(150L, event.getTotal());
		verify(itemManager, times(2)).canonicalize(100);
	}

	@Test
	public void suppressesOnlyIdenticalSameTickCapturesAndCanReset()
	{
		LootReceived received = loot("Boss", Collections.singletonList(new ItemStack(100, 2)));
		assertTrue(service.capture(received, "Alice", Instant.EPOCH, 10L, 0L).isPresent());
		assertFalse(service.capture(received, "Alice", Instant.EPOCH.plusSeconds(1), 10L, 0L).isPresent());
		assertTrue(service.capture(received, "Alice", Instant.EPOCH.plusSeconds(2), 11L, 0L).isPresent());
		service.resetDeduplication();
		assertTrue(service.capture(received, "Alice", Instant.EPOCH.plusSeconds(3), 11L, 0L).isPresent());
	}

	@Test
	public void ignoresBelowThresholdAndInvalidCaptureInputs()
	{
		LootReceived oneItem = loot("Boss", Collections.singletonList(new ItemStack(100, 1)));
		assertFalse(service.capture(oneItem, "Alice", Instant.EPOCH, 1L, 51L).isPresent());
		assertFalse(service.capture(null, "Alice", Instant.EPOCH, 1L, 0L).isPresent());
		assertFalse(service.capture(oneItem, null, Instant.EPOCH, 1L, 0L).isPresent());
		assertFalse(service.capture(oneItem, " ", Instant.EPOCH, 1L, 0L).isPresent());
		assertFalse(service.capture(oneItem, "Alice", null, 1L, 0L).isPresent());
		assertFalse(service.capture(oneItem, "Alice", Instant.EPOCH, 1L, -1L).isPresent());
		assertFalse(service.capture(loot("Boss", null), "Alice", Instant.EPOCH, 1L, 0L).isPresent());
		assertFalse(service.capture(loot("Boss", Collections.emptyList()), "Alice", Instant.EPOCH, 1L, 0L).isPresent());
		assertFalse(service.capture(loot("Boss", Arrays.asList(null, new ItemStack(-1, 1), new ItemStack(2, 0))),
			"Alice", Instant.EPOCH, 1L, 0L).isPresent());
	}

	@Test
	public void fallsBackToTypeTruncatesLabelsAndSkipsInvalidPricingIds()
	{
		LootReceived unnamed = new LootReceived(" ", 0, LootRecordType.NPC,
			Collections.singletonList(new ItemStack(100, 1)), 1, null);
		assertEquals("NPC", service.capture(unnamed, "Alice", Instant.EPOCH, 1L, 0L).get().getSourceLabel());

		String longName = String.join("", Collections.nCopies(200, "x"));
		assertEquals(SharedLootEvent.MAX_SOURCE_LABEL_LENGTH,
			service.capture(loot(longName, Collections.singletonList(new ItemStack(101, 1))),
				"Alice", Instant.EPOCH, 2L, 0L).get().getSourceLabel().length());

		when(itemManager.canonicalize(102)).thenReturn(-1);
		assertFalse(service.capture(loot("Boss", Collections.singletonList(new ItemStack(102, 1))),
			"Alice", Instant.EPOCH, 3L, 0L).isPresent());
	}

	@Test
	public void boundsDistinctItemPayloadAndRejectsInvalidGeneratedIds()
	{
		List<ItemStack> tooMany = new ArrayList<>();
		for (int index = 0; index <= SharedLootEvent.MAX_ITEMS; index++)
		{
			tooMany.add(new ItemStack(100_000 + index, 1));
		}
		assertFalse(service.capture(loot("Boss", tooMany), "Alice", Instant.EPOCH, 1L, 0L).isPresent());

		LootCaptureService badIds = new LootCaptureService(itemManager, () -> " ");
		assertFalse(badIds.capture(loot("Boss", Collections.singletonList(new ItemStack(100, 1))),
			"Alice", Instant.EPOCH, 1L, 0L).isPresent());
	}

	private static LootReceived loot(String name, List<ItemStack> items)
	{
		return new LootReceived(name, 0, LootRecordType.NPC, items, 1, null);
	}
}
