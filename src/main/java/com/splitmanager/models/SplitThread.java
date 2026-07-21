/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.models;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * Canonical persisted split thread. Segments, not roster events, are authoritative for split math.
 */
@Getter
public class SplitThread
{
	private final String id;
	private final Instant start;
	private final List<SplitSegment> segments = new ArrayList<>();
	@Setter
	private Instant end;
	@Setter
	private String pluginVersion;
	@Setter
	private SettlementConfigSnapshot settlementConfigAtStart;
	@Setter
	private SettlementConfigSnapshot settlementConfigAtEnd;
	@Setter
	private Map<String, String> altMappingAtStart;
	@Setter
	private Map<String, String> altMappingAtEnd;

	public SplitThread(String id, Instant start)
	{
		this.id = id;
		this.start = start;
		this.pluginVersion = Session.CURRENT_PLUGIN_VERSION;
	}

	public Map<String, String> getAltMappingAtStart()
	{
		return altMappingAtStart == null ? null : new LinkedHashMap<>(altMappingAtStart);
	}

	public Map<String, String> getAltMappingAtEnd()
	{
		return altMappingAtEnd == null ? null : new LinkedHashMap<>(altMappingAtEnd);
	}
}
