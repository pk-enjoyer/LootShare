/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.sessions;

import com.splitmanager.models.Session;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.models.SplitSegment;
import com.splitmanager.models.SplitThread;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between legacy mother/child Session rows and the canonical split-thread model.
 */
public final class SplitThreadAdapter
{
	private SplitThreadAdapter()
	{
	}

	public static List<SplitThread> fromSessions(List<Session> sessions)
	{
		Map<String, Session> byId = new LinkedHashMap<>();
		for (Session session : safeSessions(sessions))
		{
			if (session != null && session.getId() != null)
			{
				byId.put(session.getId(), session);
			}
		}

		List<SplitThread> threads = new ArrayList<>();
		for (Session session : byId.values())
		{
			if (session.getMotherId() != null)
			{
				continue;
			}
			SplitThread thread = new SplitThread(session.getId(), session.getStart());
			thread.setEnd(session.getEnd());
			thread.setPluginVersion(session.getPluginVersion());
			thread.setSettlementConfigAtStart(session.getSettlementConfigAtStart());
			thread.setSettlementConfigAtEnd(session.getSettlementConfigAtEnd());
			thread.setAltMappingAtStart(session.getAltMappingAtStart());
			thread.setAltMappingAtEnd(session.getAltMappingAtEnd());
			for (Session child : byId.values())
			{
				if (!session.getId().equals(child.getMotherId()))
				{
					continue;
				}
				SplitSegment segment = new SplitSegment(child.getId(), child.getStart());
				segment.setEnd(child.getEnd());
				segment.getRoster().addAll(child.getPlayers());
				for (SplitEvent event : child.getEvents())
				{
					if (event != null)
					{
						segment.getEvents().add(copyEventForSession(event, child.getId()));
					}
				}
				thread.getSegments().add(segment);
			}
			threads.add(thread);
		}
		return threads;
	}

	public static List<Session> toSessions(List<SplitThread> threads)
	{
		List<Session> sessions = new ArrayList<>();
		if (threads == null)
		{
			return sessions;
		}
		for (SplitThread thread : threads)
		{
			if (thread == null || thread.getId() == null || thread.getStart() == null)
			{
				continue;
			}
			Session mother = new Session(thread.getId(), thread.getStart(), null);
			mother.setEnd(thread.getEnd());
			mother.setPluginVersion(thread.getPluginVersion());
			mother.setSettlementConfigAtStart(thread.getSettlementConfigAtStart());
			mother.setSettlementConfigAtEnd(thread.getSettlementConfigAtEnd());
			mother.setAltMappingAtStart(thread.getAltMappingAtStart());
			mother.setAltMappingAtEnd(thread.getAltMappingAtEnd());
			sessions.add(mother);
			for (SplitSegment segment : thread.getSegments())
			{
				if (segment == null || segment.getId() == null || segment.getStart() == null)
				{
					continue;
				}
				Session child = new Session(segment.getId(), segment.getStart(), thread.getId());
				child.setEnd(segment.getEnd());
				child.setPluginVersion(thread.getPluginVersion());
				child.getPlayers().addAll(segment.getRoster());
				for (SplitEvent event : segment.getEvents())
				{
					if (event != null)
					{
						child.getEvents().add(copyEventForSession(event, child.getId()));
					}
				}
				sessions.add(child);
			}
		}
		return sessions;
	}

	private static List<Session> safeSessions(List<Session> sessions)
	{
		return sessions == null ? new ArrayList<>() : sessions;
	}

	private static SplitEvent copyEventForSession(SplitEvent event, String sessionId)
	{
		SplitEvent copy = new SplitEvent(sessionId, event.getPlayer(), event.getAmount(), event.getAt());
		copy.setType(event.getType());
		return copy;
	}
}
