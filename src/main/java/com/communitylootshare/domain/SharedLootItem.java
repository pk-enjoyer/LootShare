package com.communitylootshare.domain;

public final class SharedLootItem
{
	private final int itemId;
	private final int pricingId;
	private final long quantity;
	private final long unitPrice;

	public SharedLootItem(int itemId, int pricingId, long quantity, long unitPrice)
	{
		if (quantity <= 0 || unitPrice < 0) throw new IllegalArgumentException("Invalid item quantity or price");
		this.itemId = itemId;
		this.pricingId = pricingId;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
		Math.multiplyExact(quantity, unitPrice);
	}

	public int getItemId() { return itemId; }
	public int getPricingId() { return pricingId; }
	public long getQuantity() { return quantity; }
	public long getUnitPrice() { return unitPrice; }
	public long getLineTotal() { return Math.multiplyExact(quantity, unitPrice); }
}
