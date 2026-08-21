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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Party wire payload for a pending captured-loot or manual-GP proposal.
 *
 * <p>The RuneLite Party message sender is authoritative; decoding never trusts a serialized sender
 * ID to identify the transport origin.</p>
 */
public class ProposalMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 2;

	private int protocolVersion = PROTOCOL_VERSION;
	private String proposalId;
	private long ownerMemberId;
	private boolean manualGp;
	private String recipient;
	private String sourceLabel;
	private long capturedAtEpochMilli;
	private List<ItemPayload> items = new ArrayList<>();

	public ProposalMessage()
	{
	}

	public ProposalMessage(LootProposal proposal)
	{
		this(proposal, false);
	}

	public ProposalMessage(LootProposal proposal, boolean manualGp)
	{
		if (proposal == null)
		{
			throw new IllegalArgumentException("Proposal is required");
		}
		SharedLootEvent event = proposal.getEvent();
		this.ownerMemberId = proposal.getOwnerMemberId();
		this.manualGp = manualGp;
		this.proposalId = event.getProposalId();
		this.recipient = event.getRecipient();
		this.sourceLabel = event.getSourceLabel();
		this.capturedAtEpochMilli = event.getCapturedAt().toEpochMilli();
		for (SharedLootItem item : event.getItems())
		{
			items.add(new ItemPayload(item.getItemId(), item.getPricingId(), item.getQuantity(), item.getUnitPrice()));
		}
	}

	/**
	 * Validates this untrusted wire payload and returns the immutable proposal plus its manual-GP
	 * marker. Version 1 is accepted for backwards compatibility.
	 */
	public Optional<DecodedProposal> decodeWithMetadata(long partyId)
	{
		if ((protocolVersion != 1 && protocolVersion != PROTOCOL_VERSION) || getMemberId() <= 0L || partyId <= 0L
			|| items == null || items.isEmpty() || items.size() > SharedLootEvent.MAX_ITEMS)
		{
			return Optional.empty();
		}
		try
		{
			List<SharedLootItem> decodedItems = new ArrayList<>(items.size());
			for (ItemPayload item : items)
			{
				if (item == null)
				{
					return Optional.empty();
				}
				decodedItems.add(new SharedLootItem(item.itemId, item.pricingId, item.quantity, item.unitPrice));
			}
			long decodedOwnerMemberId = protocolVersion == 1 ? getMemberId() : ownerMemberId;
			if (decodedOwnerMemberId <= 0L)
			{
				return Optional.empty();
			}
			SharedLootEvent event = new SharedLootEvent(proposalId, recipient, sourceLabel,
				Instant.ofEpochMilli(capturedAtEpochMilli), decodedItems);
			return Optional.of(new DecodedProposal(LootProposal.pending(partyId, decodedOwnerMemberId, event), manualGp));
		}
		catch (RuntimeException ignored)
		{
			return Optional.empty();
		}
	}

	public Optional<LootProposal> decode(long partyId)
	{
		return decodeWithMetadata(partyId).map(DecodedProposal::getProposal);
	}

	public int getProtocolVersion()
	{
		return protocolVersion;
	}

	public String getProposalId()
	{
		return proposalId;
	}

	public long getOwnerMemberId()
	{
		return ownerMemberId;
	}

	public boolean isManualGp()
	{
		return manualGp;
	}

	public List<ItemPayload> getItems()
	{
		return Collections.unmodifiableList(items);
	}

	public static class ItemPayload
	{
		private int itemId;
		private int pricingId;
		private long quantity;
		private long unitPrice;

		public ItemPayload()
		{
		}

		private ItemPayload(int itemId, int pricingId, long quantity, long unitPrice)
		{
			this.itemId = itemId;
			this.pricingId = pricingId;
			this.quantity = quantity;
			this.unitPrice = unitPrice;
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
	}

	public static final class DecodedProposal
	{
		private final LootProposal proposal;
		private final boolean manualGp;

		private DecodedProposal(LootProposal proposal, boolean manualGp)
		{
			this.proposal = proposal;
			this.manualGp = manualGp;
		}

		public LootProposal getProposal()
		{
			return proposal;
		}

		public boolean isManualGp()
		{
			return manualGp;
		}
	}
}
