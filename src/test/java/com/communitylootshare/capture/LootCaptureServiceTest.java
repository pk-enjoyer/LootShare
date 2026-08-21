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
	public void capturesAggregatedCanonicalPriceSnapshots()
	{
		List<ItemStack> stacks = Arrays.asList(new ItemStack(100, 2), new ItemStack(100, 1));
		Optional<SharedLootEvent> result = service.capture("Boss", stacks, "Alice",
			Instant.ofEpochSecond(1, 123_456_789L), 5L);

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
	public void capturesEveryItemInAnNpcDropIncludingZeroValueItems()
	{
		when(itemManager.getItemPrice(100)).thenReturn(100_000);
		when(itemManager.getItemPrice(101)).thenReturn(25);
		when(itemManager.getItemPrice(102)).thenReturn(0);
		List<ItemStack> stacks = Arrays.asList(
			new ItemStack(100, 1), new ItemStack(101, 4), new ItemStack(102, 3));

		SharedLootEvent event = service.capture("Boss", stacks, "Alice",
			Instant.EPOCH, 6L).get();

		assertEquals(3, event.getItems().size());
		assertEquals(100, event.getItems().get(0).getItemId());
		assertEquals(1L, event.getItems().get(0).getQuantity());
		assertEquals(101, event.getItems().get(1).getItemId());
		assertEquals(4L, event.getItems().get(1).getQuantity());
		assertEquals(102, event.getItems().get(2).getItemId());
		assertEquals(3L, event.getItems().get(2).getQuantity());
		assertEquals(100_100L, event.getTotal());
	}

	@Test
	public void capturesGrandExchangePricesForAllItems()
	{
		when(itemManager.getItemPrice(100)).thenReturn(75);
		when(itemManager.getItemPrice(101)).thenReturn(1);
		List<ItemStack> stacks = Arrays.asList(
			new ItemStack(100, 2), new ItemStack(101, 25));

		SharedLootEvent event = service.capture("Boss", stacks, "Alice", Instant.EPOCH, 7L).get();

		assertEquals(75L, event.getItems().get(0).getUnitPrice());
		assertEquals(1L, event.getItems().get(1).getUnitPrice());
		assertEquals(175L, event.getTotal());
	}

	@Test
	public void suppressesOnlyIdenticalSameTickCapturesAndCanReset()
	{
		List<ItemStack> stacks = Collections.singletonList(new ItemStack(100, 2));
		assertTrue(service.capture("Boss", stacks, "Alice", Instant.EPOCH, 10L).isPresent());
		assertFalse(service.capture("Boss", stacks, "Alice", Instant.EPOCH.plusSeconds(1), 10L).isPresent());
		assertTrue(service.capture("Boss", stacks, "Alice", Instant.EPOCH.plusSeconds(2), 11L).isPresent());
		service.resetDeduplication();
		assertTrue(service.capture("Boss", stacks, "Alice", Instant.EPOCH.plusSeconds(3), 11L).isPresent());
	}

	@Test
	public void capturesLowValueDropsAndRejectsInvalidCaptureInputs()
	{
		List<ItemStack> oneItem = Collections.singletonList(new ItemStack(100, 1));
		assertTrue(service.capture("Boss", oneItem, "Alice", Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture(null, oneItem, "Alice", Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture(" ", oneItem, "Alice", Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture("Boss", oneItem, null, Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture("Boss", oneItem, " ", Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture("Boss", oneItem, "Alice", null, 1L).isPresent());
		assertFalse(service.capture("Boss", null, "Alice", Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture("Boss", Collections.emptyList(), "Alice", Instant.EPOCH, 1L).isPresent());
		assertFalse(service.capture("Boss", Arrays.asList(null, new ItemStack(-1, 1), new ItemStack(2, 0)),
			"Alice", Instant.EPOCH, 1L).isPresent());
	}

	@Test
	public void truncatesLabelsAndSkipsInvalidPricingIds()
	{
		String longName = String.join("", Collections.nCopies(200, "x"));
		assertEquals(SharedLootEvent.MAX_SOURCE_LABEL_LENGTH,
			service.capture(longName, Collections.singletonList(new ItemStack(101, 1)),
				"Alice", Instant.EPOCH, 2L).get().getSourceLabel().length());

		when(itemManager.canonicalize(102)).thenReturn(-1);
		assertFalse(service.capture("Boss", Collections.singletonList(new ItemStack(102, 1)),
			"Alice", Instant.EPOCH, 3L).isPresent());
	}

	@Test
	public void boundsDistinctItemPayloadAndRejectsInvalidGeneratedIds()
	{
		List<ItemStack> tooMany = new ArrayList<>();
		for (int index = 0; index <= SharedLootEvent.MAX_ITEMS; index++)
		{
			tooMany.add(new ItemStack(100_000 + index, 1));
		}
		assertFalse(service.capture("Boss", tooMany, "Alice", Instant.EPOCH, 1L).isPresent());

		LootCaptureService badIds = new LootCaptureService(itemManager, () -> " ");
		assertFalse(badIds.capture("Boss", Collections.singletonList(new ItemStack(100, 1)),
			"Alice", Instant.EPOCH, 1L).isPresent());
	}
}
