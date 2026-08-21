/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

import java.util.ArrayList;
import java.util.List;

public class LootshareState
{
	public static final int CURRENT_SCHEMA_VERSION = 4;

	private int schemaVersion = CURRENT_SCHEMA_VERSION;
	private String activeSessionId;
	private List<LootProposal> proposals = new ArrayList<>();
	private List<LootshareSession> sessions = new ArrayList<>();

	public int getSchemaVersion()
	{
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion)
	{
		this.schemaVersion = schemaVersion;
	}

	public String getActiveSessionId()
	{
		return activeSessionId;
	}

	public void setActiveSessionId(String activeSessionId)
	{
		this.activeSessionId = activeSessionId;
	}

	public List<LootProposal> getProposals()
	{
		if (proposals == null)
		{
			proposals = new ArrayList<>();
		}
		return proposals;
	}

	public void setProposals(List<LootProposal> proposals)
	{
		this.proposals = proposals == null ? new ArrayList<>() : proposals;
	}

	public List<LootshareSession> getSessions()
	{
		if (sessions == null)
		{
			sessions = new ArrayList<>();
		}
		return sessions;
	}

	public void setSessions(List<LootshareSession> sessions)
	{
		this.sessions = sessions == null ? new ArrayList<>() : sessions;
	}
}
