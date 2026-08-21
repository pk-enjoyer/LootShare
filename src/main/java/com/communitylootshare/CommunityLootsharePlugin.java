package com.communitylootshare;

import com.google.inject.Provides;
import com.communitylootshare.integration.CommunityLootshareController;
import com.communitylootshare.party.CommunityLootshareDecisionMessage;
import com.communitylootshare.party.CommunityLootshareProposalMessage;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;

/** Community Lootshare entry point for capture, party transport, and persisted owner-approved splits. */
@PluginDescriptor(
	name = "Community Lootshare",
	description = "Owner-approved party loot proposals with immutable price history and split calculations"
)
public class CommunityLootsharePlugin extends Plugin
{
	@Inject
	private CommunityLootshareController controller;

	@Inject
	private WSClient wsClient;

	@Override
	protected void startUp()
	{
		wsClient.registerMessage(CommunityLootshareProposalMessage.class);
		wsClient.registerMessage(CommunityLootshareDecisionMessage.class);
		controller.start();
	}

	@Override
	protected void shutDown()
	{
		controller.stop();
		wsClient.unregisterMessage(CommunityLootshareProposalMessage.class);
		wsClient.unregisterMessage(CommunityLootshareDecisionMessage.class);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		controller.onLootReceived(event);
	}

	@Subscribe
	public void onCommunityLootshareProposalMessage(CommunityLootshareProposalMessage message)
	{
		controller.onProposalMessage(message);
	}

	@Subscribe
	public void onCommunityLootshareDecisionMessage(CommunityLootshareDecisionMessage message)
	{
		controller.onDecisionMessage(message);
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		controller.onPartyChanged(event);
	}

	@Subscribe
	public void onUserSync(UserSync event)
	{
		controller.onUserSync(event);
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		controller.onProfileChanged();
	}

	@Provides
	CommunityLootshareConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CommunityLootshareConfig.class);
	}
}
