/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.util.ArrayList;
import java.util.List;

/** Schema beta.1: one active Party ledger only. */
public class LootshareState
{
	public static final int CURRENT_SCHEMA_VERSION = 1;
	private int schemaVersion = CURRENT_SCHEMA_VERSION;
	private LootshareSession activeSession;
	private List<LootProposal> proposals = new ArrayList<>();
	public int getSchemaVersion() { return schemaVersion; }
	public void setSchemaVersion(int value) { schemaVersion = value; }
	public LootshareSession getActiveSession() { return activeSession; }
	public void setActiveSession(LootshareSession value) { activeSession = value; }
	public List<LootProposal> getProposals() { return proposals == null ? new ArrayList<>() : proposals; }
	public void setProposals(List<LootProposal> value) { proposals = value == null ? new ArrayList<>() : value; }
}
