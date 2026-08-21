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
	String MINIMUM_SHARED_LOOT_VALUE_KEY = "minimumSharedLootValue";
	String LOOT_VALUE_BASIS_KEY = "lootValueBasis";
	String CAPTURE_NPC_LOOT_KEY = "captureNpcLoot";
	String CAPTURE_EVENT_LOOT_KEY = "captureEventLoot";
	String CAPTURE_PLAYER_LOOT_KEY = "capturePlayerLoot";
	String CAPTURE_PICKPOCKET_LOOT_KEY = "capturePickpocketLoot";
	String CAPTURE_UNKNOWN_LOOT_KEY = "captureUnknownLoot";
	String INCLUDE_LOGGED_OUT_MEMBERS_KEY = "includeLoggedOutMembers";
	String ALLOW_MEMBER_MANUAL_GP_KEY = "allowMemberManualGp";
	int DEFAULT_MINIMUM_SHARED_LOOT_VALUE = 100_000;

	@ConfigSection(
		name = "Hosted party settings",
		description = "Your saved values become authoritative while you host. While you are a guest, "
			+ "Community Lootshare uses the host's values without overwriting yours.",
		position = 0
	)
	String HOSTED_PARTY_SETTINGS = "hostedPartySettings";

	@Range(min = 0)
	@Units(" gp")
	@ConfigItem(
		keyName = MINIMUM_SHARED_LOOT_VALUE_KEY,
		name = "Minimum shared loot value",
		description = "Minimum total drop value included in split calculations. All enabled drops are still captured "
			+ "and shared. Only the current party host's setting is used.",
		position = 0,
		section = HOSTED_PARTY_SETTINGS
	)
	default int minimumSharedLootValue()
	{
		return DEFAULT_MINIMUM_SHARED_LOOT_VALUE;
	}

	@ConfigItem(
		keyName = LOOT_VALUE_BASIS_KEY,
		name = "Loot value basis",
		description = "Price source used for newly captured item values.",
		position = 1,
		section = HOSTED_PARTY_SETTINGS
	)
	default LootValueBasis lootValueBasis()
	{
		return LootValueBasis.GRAND_EXCHANGE;
	}

	@ConfigItem(
		keyName = CAPTURE_NPC_LOOT_KEY,
		name = "Capture NPC loot",
		description = "Capture loot dropped by NPCs.",
		position = 2,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean captureNpcLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = CAPTURE_EVENT_LOOT_KEY,
		name = "Capture activity loot",
		description = "Capture rewards from activities, minigames, raids, and similar events.",
		position = 3,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean captureEventLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = CAPTURE_PLAYER_LOOT_KEY,
		name = "Capture player/PvP loot",
		description = "Capture RuneLite loot records produced by player kills.",
		position = 4,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean capturePlayerLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = CAPTURE_PICKPOCKET_LOOT_KEY,
		name = "Capture pickpocket loot",
		description = "Capture RuneLite pickpocket loot records.",
		position = 5,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean capturePickpocketLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = CAPTURE_UNKNOWN_LOOT_KEY,
		name = "Capture other loot",
		description = "Capture RuneLite loot records whose source type is unknown.",
		position = 6,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean captureUnknownLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = INCLUDE_LOGGED_OUT_MEMBERS_KEY,
		name = "Include logged-out members",
		description = "Include Party members who are logged out when an accepted proposal freezes its split roster.",
		position = 7,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean includeLoggedOutMembers()
	{
		return false;
	}

	@ConfigItem(
		keyName = ALLOW_MEMBER_MANUAL_GP_KEY,
		name = "Allow member manual GP",
		description = "Allow approved Party members to add manual GP to themselves. The host can always add GP to an approved member.",
		position = 8,
		section = HOSTED_PARTY_SETTINGS
	)
	default boolean allowMemberManualGp()
	{
		return false;
	}
}
