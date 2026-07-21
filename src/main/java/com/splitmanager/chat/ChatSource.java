/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.chat;

import lombok.Getter;

@Getter
public enum ChatSource
{
	CLAN("Clan"),
	FRIENDS("Friends");

	private final String label;

	ChatSource(String label)
	{
		this.label = label;
	}

}
