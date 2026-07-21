package com.communitylootshare.domain;

import java.util.Collections;
import java.util.List;

public final class SharedLootEvent
{
	private final String proposalId;
	private final String recipient;
	private final String sourceLabel;
	private final List<SharedLootItem> items;

	public SharedLootEvent(String proposalId, String recipient, String sourceLabel, List<SharedLootItem> items)
	{
		if (proposalId == null || recipient == null || sourceLabel == null || items == null || items.size() > 128)
			throw new IllegalArgumentException("Invalid loot proposal");
		this.proposalId = proposalId;
		this.recipient = recipient;
		this.sourceLabel = sourceLabel;
		this.items = Collections.unmodifiableList(items);
	}

	public String getProposalId() { return proposalId; }
	public String getRecipient() { return recipient; }
	public String getSourceLabel() { return sourceLabel; }
	public List<SharedLootItem> getItems() { return items; }
	public long getTotal() { return items.stream().mapToLong(SharedLootItem::getLineTotal).reduce(0, Math::addExact); }
}
