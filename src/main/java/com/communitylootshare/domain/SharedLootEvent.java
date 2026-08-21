package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class SharedLootEvent
{
	public static final int MAX_ITEMS = 128;
	public static final int MAX_PROPOSAL_ID_LENGTH = 64;
	public static final int MAX_RECIPIENT_LENGTH = 64;
	public static final int MAX_SOURCE_LABEL_LENGTH = 128;

	private final String proposalId;
	private final String recipient;
	private final String sourceLabel;
	private final Instant capturedAt;
	private final List<SharedLootItem> items;

	public SharedLootEvent(String proposalId, String recipient, String sourceLabel, List<SharedLootItem> items)
	{
		this(proposalId, recipient, sourceLabel, Instant.EPOCH, items);
	}

	public SharedLootEvent(String proposalId, String recipient, String sourceLabel, Instant capturedAt,
	                       List<SharedLootItem> items)
	{
		validateText(proposalId, MAX_PROPOSAL_ID_LENGTH, "Proposal ID");
		validateText(recipient, MAX_RECIPIENT_LENGTH, "Recipient");
		validateText(sourceLabel, MAX_SOURCE_LABEL_LENGTH, "Source label");
		if (capturedAt == null)
		{
			throw new IllegalArgumentException("Capture time is required");
		}
		if (items == null || items.isEmpty() || items.size() > MAX_ITEMS)
		{
			throw new IllegalArgumentException("Loot proposal must contain between 1 and " + MAX_ITEMS + " items");
		}

		List<SharedLootItem> copy = new ArrayList<>(items.size());
		long total = 0L;
		for (SharedLootItem item : items)
		{
			if (item == null)
			{
				throw new IllegalArgumentException("Loot proposal items cannot be null");
			}
			copy.add(item);
			total = Math.addExact(total, item.getLineTotal());
		}

		this.proposalId = proposalId.trim();
		this.recipient = recipient.trim();
		this.sourceLabel = sourceLabel.trim();
		this.capturedAt = capturedAt;
		this.items = Collections.unmodifiableList(copy);
	}

	private static void validateText(String value, int maximumLength, String field)
	{
		if (value == null || value.trim().isEmpty() || value.trim().length() > maximumLength)
		{
			throw new IllegalArgumentException(field + " must be non-blank and at most " + maximumLength + " characters");
		}
	}

	public String getProposalId()
	{
		return proposalId;
	}

	public String getRecipient()
	{
		return recipient;
	}

	public String getSourceLabel()
	{
		return sourceLabel;
	}

	public Instant getCapturedAt()
	{
		return capturedAt;
	}

	public List<SharedLootItem> getItems()
	{
		return items;
	}

	public long getTotal()
	{
		long total = 0L;
		for (SharedLootItem item : items)
		{
			total = Math.addExact(total, item.getLineTotal());
		}
		return total;
	}
}
