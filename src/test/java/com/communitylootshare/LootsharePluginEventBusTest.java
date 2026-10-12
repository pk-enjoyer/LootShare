/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare;

import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

public class LootsharePluginEventBusTest
{
	@Test
	public void registersAllPluginSubscribersWithRuneLiteEventBus()
	{
		EventBus eventBus = new EventBus();
		LootsharePlugin plugin = new LootsharePlugin();
		eventBus.register(plugin);
		eventBus.unregister(plugin);
	}
}
