package com.communitylootshare.domain;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class SharedLootItem
{
	private final int itemId;
	private final int pricingId;
	private final long quantity;
	private final long unitPrice;

	public SharedLootItem(int itemId, int pricingId, long quantity, long unitPrice)
	{
		if (itemId < 0 || pricingId < 0)
		{
			throw new IllegalArgumentException("Item and pricing IDs must be non-negative");
		}
		if (quantity <= 0 || unitPrice < 0)
		{
			throw new IllegalArgumentException("Item quantity must be positive and price must be non-negative");
		}
		this.itemId = itemId;
		this.pricingId = pricingId;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
		Math.multiplyExact(quantity, unitPrice);
	}

	public int getItemId()
	{
		return itemId;
	}

	public int getPricingId()
	{
		return pricingId;
	}

	public long getQuantity()
	{
		return quantity;
	}

	public long getUnitPrice()
	{
		return unitPrice;
	}

	public long getLineTotal()
	{
		return Math.multiplyExact(quantity, unitPrice);
	}
}
