/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.capture;

import com.communitylootshare.domain.LootValueBasis;
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
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemVariationMapping;

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

	private static String normalizeSourceLabel(String sourceLabel)
	{
		if (sourceLabel == null)
		{
			return null;
		}
		String label = sourceLabel.trim();
		if (label.isEmpty())
		{
			return null;
		}
		return label.length() <= SharedLootEvent.MAX_SOURCE_LABEL_LENGTH
			? label
			: label.substring(0, SharedLootEvent.MAX_SOURCE_LABEL_LENGTH);
	}

	public synchronized Optional<SharedLootEvent> capture(String sourceLabel, Collection<ItemStack> stacks,
	                                                      String recipient, Instant capturedAt,
	                                                      long captureTick)
	{
		return capture(sourceLabel, stacks, recipient, capturedAt, captureTick,
			LootValueBasis.GRAND_EXCHANGE);
	}

	public synchronized Optional<SharedLootEvent> capture(String sourceLabel, Collection<ItemStack> stacks,
	                                                      String recipient, Instant capturedAt,
	                                                      long captureTick, LootValueBasis lootValueBasis)
	{
		String normalizedSourceLabel = normalizeSourceLabel(sourceLabel);
		if (normalizedSourceLabel == null || recipient == null || recipient.trim().isEmpty()
			|| capturedAt == null || lootValueBasis == null)
		{
			return Optional.empty();
		}

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
				int canonicalId = itemManager.canonicalize(stack.getId());
				int pricingId = ItemVariationMapping.map(canonicalId);
				if (pricingId < 0)
				{
					continue;
				}
				long unitPrice = resolveUnitPrice(canonicalId, pricingId, lootValueBasis);
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

		final SharedLootEvent event;
		try
		{
			Instant wirePrecisionCaptureTime = Instant.ofEpochMilli(capturedAt.toEpochMilli());
			event = new SharedLootEvent(proposalIdSupplier.get(), recipient, normalizedSourceLabel,
				wirePrecisionCaptureTime, items);
		}
		catch (IllegalArgumentException | ArithmeticException ignored)
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

	private long resolveUnitPrice(int canonicalId, int pricingId, LootValueBasis lootValueBasis)
	{
		if (lootValueBasis == LootValueBasis.GRAND_EXCHANGE)
		{
			return Math.max(0, itemManager.getItemPrice(pricingId));
		}
		if (canonicalId == ItemID.COINS)
		{
			return 1L;
		}
		if (canonicalId == ItemID.PLATINUM)
		{
			return 1_000L;
		}
		ItemComposition composition = itemManager.getItemComposition(canonicalId);
		return composition == null ? 0L : Math.max(0, composition.getHaPrice());
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
		public int hashCode()
		{
			return Objects.hash(itemId, pricingId, unitPrice);
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
		public int hashCode()
		{
			return Objects.hash(recipient, sourceLabel, items);
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
	}
}
