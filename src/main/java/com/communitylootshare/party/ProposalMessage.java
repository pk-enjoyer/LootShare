/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/** Beta capture payload; RuneLite's sender ID is the proposal owner. */
public class ProposalMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;
	private int protocolVersion = PROTOCOL_VERSION;
	private String proposalId;
	private String recipient;
	private String sourceLabel;
	private long capturedAtEpochMilli;
	private List<ItemPayload> items = new ArrayList<>();

	public ProposalMessage() { }
	public ProposalMessage(LootProposal proposal)
	{
		SharedLootEvent event = proposal.getEvent();
		proposalId = event.getProposalId(); recipient = event.getRecipient(); sourceLabel = event.getSourceLabel();
		capturedAtEpochMilli = event.getCapturedAt().toEpochMilli();
		for (SharedLootItem item : event.getItems())
		{
			items.add(new ItemPayload(item.getItemId(), item.getPricingId(), item.getQuantity(), item.getUnitPrice()));
		}
	}

	public Optional<LootProposal> decode(long partyId)
	{
		if (protocolVersion != PROTOCOL_VERSION || getMemberId() <= 0L || partyId <= 0L || items == null
			|| items.isEmpty() || items.size() > SharedLootEvent.MAX_ITEMS)
		{
			return Optional.empty();
		}
		try
		{
			List<SharedLootItem> decoded = new ArrayList<>();
			for (ItemPayload item : items)
			{
				if (item == null) { return Optional.empty(); }
				decoded.add(new SharedLootItem(item.itemId, item.pricingId, item.quantity, item.unitPrice));
			}
			return Optional.of(LootProposal.pending(partyId, getMemberId(),
				new SharedLootEvent(proposalId, recipient, sourceLabel, Instant.ofEpochMilli(capturedAtEpochMilli), decoded)));
		}
		catch (RuntimeException ignored) { return Optional.empty(); }
	}

	public static class ItemPayload
	{
		private int itemId; private int pricingId; private long quantity; private long unitPrice;
		public ItemPayload() { }
		private ItemPayload(int itemId, int pricingId, long quantity, long unitPrice)
		{ this.itemId = itemId; this.pricingId = pricingId; this.quantity = quantity; this.unitPrice = unitPrice; }
	}
}
