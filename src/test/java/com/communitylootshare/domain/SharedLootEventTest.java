package com.communitylootshare.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class SharedLootEventTest
{
	@Test
	public void totalsUseImmutableSnapshots()
	{
		List<SharedLootItem> items = new ArrayList<>(Arrays.asList(
			new SharedLootItem(1, 10, 2, 50), new SharedLootItem(2, 20, 1, 25)));
		SharedLootEvent event = new SharedLootEvent("p1", "alice", "PvP loot",
			Instant.ofEpochSecond(10), items);
		items.clear();

		assertEquals(125L, event.getTotal());
		assertEquals("p1", event.getProposalId());
		assertEquals("alice", event.getRecipient());
		assertEquals("PvP loot", event.getSourceLabel());
		assertEquals(Instant.ofEpochSecond(10), event.getCapturedAt());
		assertEquals(2, event.getItems().size());
		assertEquals(1, event.getItems().get(0).getItemId());
		assertEquals(10, event.getItems().get(0).getPricingId());
		assertEquals(2L, event.getItems().get(0).getQuantity());
		assertEquals(50L, event.getItems().get(0).getUnitPrice());
		assertEquals(100L, event.getItems().get(0).getLineTotal());
		assertNotEquals(event.getItems().get(0), event.getItems().get(1));
		try
		{
			event.getItems().clear();
			fail("Expected an immutable item list");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	@Test
	public void legacyConstructorUsesEpochCaptureTime()
	{
		SharedLootEvent event = new SharedLootEvent(" p1 ", " alice ", " loot ",
			Collections.singletonList(new SharedLootItem(1, 1, 1, 1)));
		assertEquals("p1", event.getProposalId());
		assertEquals("alice", event.getRecipient());
		assertEquals("loot", event.getSourceLabel());
		assertEquals(Instant.EPOCH, event.getCapturedAt());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidQuantity()
	{
		new SharedLootItem(1, 1, 0, 1);
	}

	@Test
	public void rejectsInvalidItemFieldsAndOverflow()
	{
		expectIllegal(() -> new SharedLootItem(-1, 1, 1, 1));
		expectIllegal(() -> new SharedLootItem(1, -1, 1, 1));
		expectIllegal(() -> new SharedLootItem(1, 1, 1, -1));
		expectArithmetic(() -> new SharedLootItem(1, 1, Long.MAX_VALUE, 2));
	}

	@Test
	public void rejectsInvalidProposalFields()
	{
		List<SharedLootItem> oneItem = Collections.singletonList(new SharedLootItem(1, 1, 1, 1));
		expectIllegal(() -> new SharedLootEvent(null, "Alice", "Loot", Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent(" ", "Alice", "Loot", Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent(repeat('x', 65), "Alice", "Loot", Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent("p", " ", "Loot", Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent("p", repeat('x', 65), "Loot", Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent("p", "Alice", " ", Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent("p", "Alice", repeat('x', 129), Instant.EPOCH, oneItem));
		expectIllegal(() -> new SharedLootEvent("p", "Alice", "Loot", null, oneItem));
		expectIllegal(() -> new SharedLootEvent("p", "Alice", "Loot", Instant.EPOCH, null));
		expectIllegal(() -> new SharedLootEvent("p", "Alice", "Loot", Instant.EPOCH, Collections.emptyList()));

		List<SharedLootItem> tooMany = new ArrayList<>();
		for (int index = 0; index <= SharedLootEvent.MAX_ITEMS; index++)
		{
			tooMany.add(new SharedLootItem(index, index, 1, 1));
		}
		expectIllegal(() -> new SharedLootEvent("p", "Alice", "Loot", Instant.EPOCH, tooMany));
		expectIllegal(() -> new SharedLootEvent("p", "Alice", "Loot", Instant.EPOCH,
			Arrays.asList(new SharedLootItem(1, 1, 1, 1), null)));
	}

	@Test
	public void rejectsProposalTotalOverflow()
	{
		expectArithmetic(() -> new SharedLootEvent("p", "Alice", "Loot", Instant.EPOCH,
			Arrays.asList(new SharedLootItem(1, 1, Long.MAX_VALUE, 1), new SharedLootItem(2, 2, 1, 1))));
	}

	private static String repeat(char value, int count)
	{
		char[] chars = new char[count];
		Arrays.fill(chars, value);
		return new String(chars);
	}

	private static void expectIllegal(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected.
		}
	}

	private static void expectArithmetic(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected ArithmeticException");
		}
		catch (ArithmeticException expected)
		{
			// Expected.
		}
	}
}
