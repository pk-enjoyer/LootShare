/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.persistence;

import com.splitmanager.models.Session;
import com.splitmanager.models.SplitThread;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

public class SessionStorageData
{
	public static final int CURRENT_SCHEMA_VERSION = 3;

	@Setter
	@Getter
	private int schemaVersion = CURRENT_SCHEMA_VERSION;
	@Setter
	@Getter
	private String currentSessionId;
	@Setter
	@Getter
	private boolean historyLoaded;
	private List<Session> sessions = new ArrayList<>();
	private List<SplitThread> threads = new ArrayList<>();

	public List<Session> getSessions()
	{
		if (sessions == null)
		{
			sessions = new ArrayList<>();
		}
		return sessions;
	}

	public void setSessions(List<Session> sessions)
	{
		this.sessions = sessions == null ? new ArrayList<>() : sessions;
	}

	public List<SplitThread> getThreads()
	{
		if (threads == null)
		{
			threads = new ArrayList<>();
		}
		return threads;
	}

	public void setThreads(List<SplitThread> threads)
	{
		this.threads = threads == null ? new ArrayList<>() : threads;
	}
}
