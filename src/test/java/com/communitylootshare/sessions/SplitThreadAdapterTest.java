/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.sessions;

import com.communitylootshare.models.Session;
import com.communitylootshare.models.SplitEvent;
import com.communitylootshare.models.SplitSegment;
import com.communitylootshare.models.SplitThread;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SplitThreadAdapterTest
{
	@Test
	public void skipsInvalidPersistedThreadsAndSegments()
	{
		assertTrue(SplitThreadAdapter.toSessions(null).isEmpty());

		SplitThread invalidThread = new SplitThread(null, Instant.EPOCH);
		SplitThread validThread = new SplitThread("thread", Instant.EPOCH);
		validThread.getSegments().add(null);
		validThread.getSegments().add(new SplitSegment(null, Instant.EPOCH.plusSeconds(1)));
		SplitSegment validSegment = new SplitSegment("segment", Instant.EPOCH.plusSeconds(2));
		validSegment.getRoster().add("Alice");
		validSegment.getEvents().add(null);
		validSegment.getEvents().add(new SplitEvent("stale-id", "Alice", 100L, Instant.EPOCH.plusSeconds(3)));
		validThread.getSegments().add(validSegment);

		List<Session> sessions = SplitThreadAdapter.toSessions(Arrays.asList(null, invalidThread, validThread));

		assertEquals(2, sessions.size());
		assertEquals("thread", sessions.get(0).getId());
		assertEquals("segment", sessions.get(1).getId());
		assertEquals("thread", sessions.get(1).getMotherId());
		assertEquals("segment", sessions.get(1).getEvents().get(0).getSessionId());

		sessions.add(null);
		List<SplitThread> roundTripped = SplitThreadAdapter.fromSessions(sessions);
		assertEquals(1, roundTripped.size());
		assertEquals(1, roundTripped.get(0).getSegments().size());
		assertEquals("Alice", roundTripped.get(0).getSegments().get(0).getEvents().get(0).getPlayer());
	}
}
