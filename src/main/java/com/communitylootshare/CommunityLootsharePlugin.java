package com.communitylootshare;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/** Community Lootshare entry point. Party and Loot Tracker integration is staged behind this boundary. */
@PluginDescriptor(name = "Community Lootshare", description = "Shared loot tracking for RuneLite Party groups")
public class CommunityLootsharePlugin extends Plugin
{
	@Inject
	private CommunityLootshareConfig config;

	@Provides
	CommunityLootshareConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CommunityLootshareConfig.class);
	}
}
