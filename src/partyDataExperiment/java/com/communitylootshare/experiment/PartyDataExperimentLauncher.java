/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.experiment;

import com.communitylootshare.LootsharePlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PartyDataExperimentLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LootsharePlugin.class, PartyDataExperimentPlugin.class);
		RuneLite.main(args);
	}
}
