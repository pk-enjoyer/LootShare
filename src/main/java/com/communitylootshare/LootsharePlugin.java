package com.communitylootshare;

import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.party.DecisionMessage;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.party.ProposalMessage;
import com.communitylootshare.ui.lootshare.Panel;
import com.communitylootshare.ui.lootshare.UiController;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.LootManager;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(name = "Community Lootshare", description = "Share your loot with your party members")
public class LootsharePlugin extends Plugin
{
	private static final BufferedImage ICON = ImageUtil.loadImageResource(LootsharePlugin.class, "/com/communitylootshare/icons/icon.png");
	@Inject
	private LootshareController controller;
	@Inject
	private WSClient wsClient;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private LootManager lootManager;
	@Inject
	private UiController uiController;
	private NavigationButton navigationButton;

	@Override
	protected void startUp()
	{
		wsClient.registerMessage(ProposalMessage.class);
		wsClient.registerMessage(DecisionMessage.class);
		wsClient.registerMessage(HostMessage.class);
		controller.start();
		SwingUtilities.invokeLater(() -> {
			Panel panel = new Panel();
			navigationButton = NavigationButton.builder().tooltip("Community Lootshare").icon(ICON).panel(panel).build();
			clientToolbar.addNavigation(navigationButton);
			uiController.start(panel);
		});
	}

	@Override
	protected void shutDown()
	{
		uiController.stop();
		NavigationButton current = navigationButton;
		navigationButton = null;
		SwingUtilities.invokeLater(() -> {
			if (current != null)
			{
				clientToolbar.removeNavigation(current);
			}
		});
		controller.stop();
		wsClient.unregisterMessage(ProposalMessage.class);
		wsClient.unregisterMessage(DecisionMessage.class);
		wsClient.unregisterMessage(HostMessage.class);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		controller.onLootReceived(event);
		uiController.refresh();
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		controller.onServerNpcLoot(event);
		uiController.refresh();
	}

	@Subscribe
	public void onProposal(ProposalMessage message)
	{
		controller.onProposalMessage(message);
		uiController.refresh();
	}

	@Subscribe
	public void onDecision(DecisionMessage message)
	{
		controller.onDecisionMessage(message);
		uiController.refresh();
	}

	@Subscribe
	public void onHost(HostMessage message)
	{
		controller.onHostMessage(message);
		uiController.refresh();
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		controller.onPartyChanged(event);
		uiController.refresh();
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		controller.onUserJoin(event);
		uiController.refresh();
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		controller.onUserPart(event);
		uiController.refresh();
	}

	@Subscribe
	public void onUserSync(UserSync event)
	{
		controller.onUserSync(event);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event != null && LootshareConfig.GROUP.equals(event.getGroup()))
		{
			controller.onLocalConfigurationChanged();
		}
	}

	@Provides
	LootshareConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(LootshareConfig.class);
	}
}
