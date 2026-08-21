/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.capture;

import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.loottracker.LootReceived;

@Singleton
public class LootCaptureService
{
	private final ItemManager itemManager;
	private final Supplier<String> proposalIdSupplier;
	private long lastCaptureTick = Long.MIN_VALUE;
	private CaptureFingerprint lastFingerprint;

	@Inject
	public LootCaptureService(ItemManager itemManager)
	{
		this(itemManager, () -> UUID.randomUUID().toString());
	}

	LootCaptureService(ItemManager itemManager, Supplier<String> proposalIdSupplier)
	{
		this.itemManager = Objects.requireNonNull(itemManager, "itemManager");
		this.proposalIdSupplier = Objects.requireNonNull(proposalIdSupplier, "proposalIdSupplier");
	}

	public synchronized Optional<SharedLootEvent> capture(LootReceived received, String recipient,
	                                                     Instant capturedAt, long captureTick,
	                                                     long minimumBundleValue)
	{
		if (received == null || recipient == null || recipient.trim().isEmpty() || capturedAt == null
			|| minimumBundleValue < 0L)
		{
			return Optional.empty();
		}

		Collection<ItemStack> stacks = received.getItems();
		if (stacks == null || stacks.isEmpty())
		{
			return Optional.empty();
		}

		Map<ItemKey, Long> quantities = new LinkedHashMap<>();
		try
		{
			for (ItemStack stack : stacks)
			{
				if (stack == null || stack.getId() < 0 || stack.getQuantity() <= 0)
				{
					continue;
				}
				int pricingId = ItemVariationMapping.map(itemManager.canonicalize(stack.getId()));
				if (pricingId < 0)
				{
					continue;
				}
				long unitPrice = Math.max(0, itemManager.getItemPrice(pricingId));
				ItemKey key = new ItemKey(stack.getId(), pricingId, unitPrice);
				quantities.put(key, Math.addExact(quantities.getOrDefault(key, 0L), stack.getQuantity()));
			}
		}
		catch (ArithmeticException ignored)
		{
			return Optional.empty();
		}

		if (quantities.isEmpty() || quantities.size() > SharedLootEvent.MAX_ITEMS)
		{
			return Optional.empty();
		}

		List<Map.Entry<ItemKey, Long>> entries = new ArrayList<>(quantities.entrySet());
		entries.sort(Comparator
			.comparingInt((Map.Entry<ItemKey, Long> entry) -> entry.getKey().itemId)
			.thenComparingInt(entry -> entry.getKey().pricingId));
		List<SharedLootItem> items = new ArrayList<>(entries.size());
		for (Map.Entry<ItemKey, Long> entry : entries)
		{
			ItemKey key = entry.getKey();
			items.add(new SharedLootItem(key.itemId, key.pricingId, entry.getValue(), key.unitPrice));
		}

		String sourceLabel = sourceLabel(received);
		final SharedLootEvent event;
		try
		{
			Instant wirePrecisionCaptureTime = Instant.ofEpochMilli(capturedAt.toEpochMilli());
			event = new SharedLootEvent(proposalIdSupplier.get(), recipient, sourceLabel, wirePrecisionCaptureTime, items);
		}
		catch (IllegalArgumentException | ArithmeticException ignored)
		{
			return Optional.empty();
		}
		if (event.getTotal() < minimumBundleValue)
		{
			return Optional.empty();
		}

		CaptureFingerprint fingerprint = new CaptureFingerprint(event.getRecipient(), event.getSourceLabel(), event.getItems());
		if (captureTick == lastCaptureTick && fingerprint.equals(lastFingerprint))
		{
			return Optional.empty();
		}
		lastCaptureTick = captureTick;
		lastFingerprint = fingerprint;
		return Optional.of(event);
	}

	public synchronized void resetDeduplication()
	{
		lastCaptureTick = Long.MIN_VALUE;
		lastFingerprint = null;
	}

	private static String sourceLabel(LootReceived received)
	{
		String label = received.getName();
		if (label == null || label.trim().isEmpty())
		{
			label = received.getType() == null ? "Loot" : received.getType().name().replace('_', ' ');
		}
		label = label.trim();
		return label.length() <= SharedLootEvent.MAX_SOURCE_LABEL_LENGTH
			? label
			: label.substring(0, SharedLootEvent.MAX_SOURCE_LABEL_LENGTH);
	}

	private static final class ItemKey
	{
		private final int itemId;
		private final int pricingId;
		private final long unitPrice;

		private ItemKey(int itemId, int pricingId, long unitPrice)
		{
			this.itemId = itemId;
			this.pricingId = pricingId;
			this.unitPrice = unitPrice;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof ItemKey))
			{
				return false;
			}
			ItemKey itemKey = (ItemKey) other;
			return itemId == itemKey.itemId && pricingId == itemKey.pricingId && unitPrice == itemKey.unitPrice;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(itemId, pricingId, unitPrice);
		}
	}

	private static final class CaptureFingerprint
	{
		private final String recipient;
		private final String sourceLabel;
		private final List<SharedLootItem> items;

		private CaptureFingerprint(String recipient, String sourceLabel, List<SharedLootItem> items)
		{
			this.recipient = recipient;
			this.sourceLabel = sourceLabel;
			this.items = new ArrayList<>(items);
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof CaptureFingerprint))
			{
				return false;
			}
			CaptureFingerprint that = (CaptureFingerprint) other;
			return recipient.equals(that.recipient) && sourceLabel.equals(that.sourceLabel) && items.equals(that.items);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(recipient, sourceLabel, items);
		}
	}
}
