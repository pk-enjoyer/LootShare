package com.communitylootshare;

import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.party.DecisionMessage;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.party.MemberIdentityMessage;
import com.communitylootshare.party.ProposalMessage;
import com.communitylootshare.ui.SwingUiInteractionGateway;
import com.communitylootshare.ui.lootshare.Panel;
import com.communitylootshare.ui.lootshare.UiController;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.PartyMemberAvatar;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.LootManager;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.party.messages.StatusUpdate;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Community Lootshare entry point for capture, Party transport, and persisted host-managed splits.
 */
@PluginDescriptor(
	name = "Community Lootshare",
	description = "Host-managed Party loot sharing with immutable price history and exact settlements"
)
public class LootsharePlugin extends Plugin
{
	private static final BufferedImage ICON = ImageUtil.loadImageResource(
		LootsharePlugin.class, "/com/communitylootshare/icons/icon.png");

	@Inject
	private LootshareController controller;

	@Inject
	private WSClient wsClient;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	/**
	 * Ensures RuneLite's low-level NPC loot source is active even when Loot Tracker is disabled.
	 */
	@Inject
	private LootManager lootManager;

	@Inject
	private SwingUiInteractionGateway interactions;

	@Inject
	private UiController uiController;

	private Panel panel;
	private NavigationButton navigationButton;
	private volatile boolean started;

	@Override
	protected void startUp()
	{
		started = true;
		wsClient.registerMessage(ProposalMessage.class);
		wsClient.registerMessage(DecisionMessage.class);
		wsClient.registerMessage(HostMessage.class);
		wsClient.registerMessage(MemberIdentityMessage.class);
		controller.start();
		SwingUtilities.invokeLater(() -> {
			if (!started)
			{
				return;
			}
			panel = new Panel(uiController, interactions, itemManager);
			navigationButton = NavigationButton.builder()
				.tooltip("Community Lootshare")
				.icon(ICON)
				.priority(5)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navigationButton);
			uiController.start(panel);
		});
	}

	@Override
	protected void shutDown()
	{
		started = false;
		uiController.stop();
		NavigationButton currentNavigation = navigationButton;
		navigationButton = null;
		panel = null;
		SwingUtilities.invokeLater(() -> {
			if (currentNavigation != null)
			{
				clientToolbar.removeNavigation(currentNavigation);
			}
		});
		controller.stop();
		wsClient.unregisterMessage(ProposalMessage.class);
		wsClient.unregisterMessage(DecisionMessage.class);
		wsClient.unregisterMessage(HostMessage.class);
		wsClient.unregisterMessage(MemberIdentityMessage.class);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		controller.onLootReceived(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		controller.onChatMessage(event);
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		controller.onServerNpcLoot(event);
	}

	@Subscribe
	public void onProposalMessage(ProposalMessage message)
	{
		controller.onProposalMessage(message);
	}

	@Subscribe
	public void onDecisionMessage(DecisionMessage message)
	{
		controller.onDecisionMessage(message);
	}

	@Subscribe
	public void onHostMessage(HostMessage message)
	{
		controller.onHostMessage(message);
	}

	@Subscribe
	public void onMemberIdentityMessage(MemberIdentityMessage message)
	{
		controller.onMemberIdentityMessage(message);
		uiController.refresh();
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		controller.onPartyChanged(event);
		uiController.onPartyChanged(event);
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
	public void onConfigChanged(ConfigChanged event)
	{
		if (event != null && LootshareConfig.GROUP.equals(event.getGroup()))
		{
			controller.onLocalConfigurationChanged();
			uiController.refresh();
		}
	}

	@Subscribe
	public void onPartyMemberAvatar(PartyMemberAvatar event)
	{
		uiController.refresh();
	}

	// PartyPlugin populates the PartyMember display name from this message. Run after it so the
	// sidebar rebuild sees the resolved name instead of the initial member ID placeholder.
	@Subscribe(priority = -1)
	public void onStatusUpdate(StatusUpdate event)
	{
		uiController.refresh();
	}

	@Subscribe
	public void onUserSync(UserSync event)
	{
		controller.onUserSync(event);
		uiController.refresh();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		controller.onGameStateChanged(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		controller.onGameTick();
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		controller.onProfileChanged();
		uiController.refresh();
	}

	@Provides
	LootshareConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootshareConfig.class);
	}
}
