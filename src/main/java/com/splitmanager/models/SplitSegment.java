/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.models;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Canonical split calculation segment. One roster applies to every loot event in this segment.
 */
@Getter
public class SplitSegment
{
	private final String id;
	private final Instant start;
	private final Set<String> roster = new LinkedHashSet<>();
	private final List<SplitEvent> events = new ArrayList<>();
	@Setter
	private Instant end;

	public SplitSegment(String id, Instant start)
	{
		this.id = id;
		this.start = start;
	}
}
