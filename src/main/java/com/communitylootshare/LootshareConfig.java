package com.communitylootshare;

import com.communitylootshare.domain.LootValueBasis;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(LootshareConfig.GROUP)
public interface LootshareConfig extends Config
{
	String GROUP = "community-lootshare";
	int DEFAULT_MINIMUM_SHARED_LOOT_VALUE = 100_000;

	@ConfigSection(name = "Hosted party settings", description = "The Party host shares these settings with guests.", position = 0)
	String HOSTED_PARTY_SETTINGS = "hostedPartySettings";

	@Range(min = 0)
	@Units(" gp")
	@ConfigItem(keyName = "minimumSharedLootValue", name = "Minimum shared loot value",
		description = "Drops below this value are not shared with the Party.", position = 0, section = HOSTED_PARTY_SETTINGS)
	default int minimumSharedLootValue()
	{
		return DEFAULT_MINIMUM_SHARED_LOOT_VALUE;
	}

	@ConfigItem(keyName = "lootValueBasis", name = "Loot value basis",
		description = "Price source used for newly captured item values.", position = 1, section = HOSTED_PARTY_SETTINGS)
	default LootValueBasis lootValueBasis()
	{
		return LootValueBasis.GRAND_EXCHANGE;
	}

	@ConfigItem(keyName = "includeLoggedOutMembers", name = "Include logged-out members",
		description = "Include logged-out Party members in future equal splits.", position = 2, section = HOSTED_PARTY_SETTINGS)
	default boolean includeLoggedOutMembers()
	{
		return false;
	}
}
