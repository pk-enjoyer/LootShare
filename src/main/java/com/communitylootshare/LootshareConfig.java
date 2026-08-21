package com.communitylootshare;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(LootshareConfig.GROUP)
public interface LootshareConfig extends Config
{
	String GROUP = "community-lootshare";
	String MINIMUM_SHARED_LOOT_VALUE_KEY = "minimumSharedLootValue";
	int DEFAULT_MINIMUM_SHARED_LOOT_VALUE = 100_000;

	@Range(min = 0)
	@Units(" gp")
	@ConfigItem(
		keyName = MINIMUM_SHARED_LOOT_VALUE_KEY,
		name = "Minimum shared loot value",
		description = "Minimum total drop value included in split calculations. All enabled drops are still captured "
			+ "and shared. Only the current party host's setting is used.",
		position = 0
	)
	default int minimumSharedLootValue()
	{
		return DEFAULT_MINIMUM_SHARED_LOOT_VALUE;
	}
}
