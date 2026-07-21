package com.communitylootshare.domain;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SharedLootEventTest
{
	@Test
	public void totalsUseImmutableSnapshots()
	{
		SharedLootEvent event = new SharedLootEvent("p1", "alice", "PvP loot",
			Arrays.asList(new SharedLootItem(1, 1, 2, 50), new SharedLootItem(2, 2, 1, 25)));
		assertEquals(125L, event.getTotal());
		assertEquals("PvP loot", event.getSourceLabel());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidQuantity()
	{
		new SharedLootItem(1, 1, 0, 1);
	}
}
