package com.communitylootshare;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

@ConfigGroup("community-lootshare")
public interface CommunityLootshareConfig extends Config
{
	int DEFAULT_MINIMUM_BUNDLE_VALUE = 100_000;
}
