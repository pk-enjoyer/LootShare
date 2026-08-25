/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.ui.UiInteractionGateway;
import com.communitylootshare.ui.lootshare.PanelState.HostedSettings;
import com.communitylootshare.ui.lootshare.PanelState.LootItem;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;

/**
 * Community Lootshare sidebar with Party controls and collapsible per-member loot grids.
 */
@Slf4j
public class Panel extends PluginPanel
{
	private static final int ITEMS_PER_ROW = 5;
	private static final int ITEM_SLOT_SIZE = 36;
	private static final Insets CONNECTION_BUTTON_MARGIN = new Insets(3, 8, 3, 8);
	private static final Color DISCONNECTED_COLOR = new Color(122, 122, 122);

	private final PanelActions actions;
	private final UiInteractionGateway interactions;
	private final ItemManager itemManager;
	private final JPanel connectionPanel = new JPanel(new GridBagLayout());
	private final JPanel hostedSettingsSection = sectionPanel();
	private final JPanel hostedSettingsBody = new JPanel();
	private final JPanel memberList = new JPanel();
	private final JPanel settlementSection = sectionPanel();
	private final SettlementView settlementView = new SettlementView();
	private final JLabel connectionStatus = new JLabel();
	private final JLabel memberHeaderLabel = new JLabel("Party members");
	private final JButton primaryButton = new JButton();
	private final JButton secondaryButton = new JButton("Join party");
	private final JButton previousPartyButton = new JButton("Join previous party");
	private final JButton copyButton = new JButton("Copy passphrase");
	private final JButton hostedSettingsToggle = new JButton();
	private final JButton settlementToggle = new JButton();
	private final Set<Long> expandedMembers = new HashSet<>();
	private final Map<Long, MemberCard> memberCards = new HashMap<>();

	private boolean inParty;
	private boolean localHost;
	private boolean hostedSettingsExpanded;
	private boolean settlementExpanded;
	private boolean settlementAutoOpened;
	private String settlementSessionId;
	private String partyPassphrase;

	public Panel(PanelActions actions, UiInteractionGateway interactions,
	             ItemManager itemManager)
	{
		this.actions = actions;
		this.interactions = interactions;
		this.itemManager = itemManager;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.add(buildConnectionSection());
		content.add(Box.createRigidArea(new Dimension(0, 8)));
		content.add(buildHostedSettingsSection());
		content.add(Box.createRigidArea(new Dimension(0, 8)));
		content.add(buildMembersSection());
		content.add(Box.createRigidArea(new Dimension(0, 8)));
		content.add(buildSettlementSection());
		add(content, BorderLayout.NORTH);

		primaryButton.addActionListener(event -> onPrimaryAction());
		secondaryButton.addActionListener(event -> onJoinAction());
		previousPartyButton.addActionListener(event -> actions.joinPreviousParty());
		copyButton.addActionListener(event -> interactions.writeClipboardText(partyPassphrase));
	}

	private static JPanel settingRow(String label, String value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel keyLabel = new JLabel(label);
		keyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		keyLabel.setFont(FontManager.getRunescapeSmallFont());
		JLabel valueLabel = new JLabel(value, SwingConstants.RIGHT);
		valueLabel.setForeground(Color.WHITE);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		row.add(keyLabel, BorderLayout.WEST);
		row.add(valueLabel, BorderLayout.EAST);
		return row;
	}

	private static String included(boolean included)
	{
		return included ? "Included" : "Excluded";
	}

	private static JPanel sectionPanel()
	{
		JPanel section = new JPanel(new BorderLayout(0, 5));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return section;
	}

	private static JLabel sectionTitle(String text)
	{
		JLabel title = new JLabel(text);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		return title;
	}

	private static JLabel emptyLabel(String text)
	{
		JLabel label = new JLabel("<html><center>" + text + "</center></html>", SwingConstants.CENTER);
		label.setAlignmentX(CENTER_ALIGNMENT);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(new EmptyBorder(14, 6, 10, 6));
		return label;
	}

	private static String memberDisplayName(MemberLoot member)
	{
		if (member.isLocal() && member.isHost())
		{
			return member.getDisplayName() + " (you, host)";
		}
		if (member.isLocal())
		{
			return member.getDisplayName() + " (you)";
		}
		return member.isHost() ? member.getDisplayName() + " (host)" : member.getDisplayName();
	}

	private static String memberSummary(MemberLoot member)
	{
		if ("Name unavailable".equals(member.getDisplayName()))
		{
			return "Excluded from new splits until resolved";
		}
		String drops = member.getProposalCount() == 1 ? "1 drop" : member.getProposalCount() + " drops";
		String pending = member.getPendingProposalCount() == 0
			? ""
			: " • " + member.getPendingProposalCount() + " pending";
		return drops + " • " + QuantityFormatter.quantityToStackSize(member.getTotalValue()) + " gp" + pending;
	}

	private static String itemToolTip(LootItem item)
	{
		return "<html>" + item.getName()
			+ " x " + QuantityFormatter.formatNumber(item.getQuantity())
			+ "<br>Captured value: " + QuantityFormatter.formatNumber(item.getTotalValue()) + " gp</html>";
	}

	private static int imageQuantity(long quantity)
	{
		return quantity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) quantity;
	}

	public void render(PanelState state)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> render(state));
			return;
		}
		inParty = state.isInParty();
		localHost = state.getMembers().stream().anyMatch(member -> member.isLocal() && member.isHost());
		partyPassphrase = state.getPartyPassphrase();
		renderConnection(state);
		renderHostedSettings(state.getHostedSettings());
		renderMembers(state.getMembers());
		renderSettlement(state);
		revalidate();
		repaint();
	}

	private JPanel buildConnectionSection()
	{
		JPanel section = sectionPanel();
		JLabel title = sectionTitle("Lootshare party");
		section.add(title, BorderLayout.NORTH);

		connectionPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		connectionPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 2, 5, 2);
		constraints.weightx = 0.5;
		constraints.gridx = 0;
		constraints.gridy = 0;
		connectionPanel.add(primaryButton, constraints);
		constraints.gridx = 1;
		connectionPanel.add(secondaryButton, constraints);
		connectionPanel.add(copyButton, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 2;
		constraints.weightx = 1.0;
		connectionPanel.add(previousPartyButton, constraints);

		connectionStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		connectionStatus.setFont(FontManager.getRunescapeSmallFont());
		constraints.gridx = 0;
		constraints.gridy = 2;
		constraints.gridwidth = 2;
		constraints.weightx = 1.0;
		constraints.insets = new Insets(2, 2, 0, 2);
		connectionPanel.add(connectionStatus, constraints);

		for (JButton button : new JButton[]{primaryButton, secondaryButton, previousPartyButton, copyButton})
		{
			button.setFocusable(false);
			button.setMargin(CONNECTION_BUTTON_MARGIN);
		}
		section.add(connectionPanel, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildHostedSettingsSection()
	{
		hostedSettingsToggle.setHorizontalAlignment(SwingConstants.LEFT);
		hostedSettingsToggle.setFont(FontManager.getRunescapeBoldFont());
		hostedSettingsToggle.setForeground(Color.WHITE);
		hostedSettingsToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		hostedSettingsToggle.setBorder(new EmptyBorder(6, 8, 6, 8));
		hostedSettingsToggle.setFocusPainted(false);
		hostedSettingsToggle.setFocusable(false);
		hostedSettingsToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		hostedSettingsToggle.setToolTipText(
			"Effective Party policy. Guests keep their own saved values for when they host.");
		hostedSettingsToggle.addActionListener(event -> {
			hostedSettingsExpanded = !hostedSettingsExpanded;
			hostedSettingsBody.setVisible(hostedSettingsExpanded);
			updateHostedSettingsToggleText();
			hostedSettingsSection.revalidate();
		});

		hostedSettingsBody.setLayout(new BoxLayout(hostedSettingsBody, BoxLayout.Y_AXIS));
		hostedSettingsBody.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		hostedSettingsBody.setBorder(new EmptyBorder(2, 8, 8, 8));
		hostedSettingsSection.add(hostedSettingsToggle, BorderLayout.NORTH);
		hostedSettingsSection.add(hostedSettingsBody, BorderLayout.CENTER);
		hostedSettingsSection.setVisible(false);
		return hostedSettingsSection;
	}

	private JPanel buildMembersSection()
	{
		JPanel section = sectionPanel();
		memberHeaderLabel.setFont(FontManager.getRunescapeBoldFont());
		memberHeaderLabel.setForeground(Color.WHITE);
		section.add(memberHeaderLabel, BorderLayout.NORTH);

		memberList.setLayout(new BoxLayout(memberList, BoxLayout.Y_AXIS));
		memberList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.add(memberList, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildSettlementSection()
	{
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		settlementToggle.setHorizontalAlignment(SwingConstants.LEFT);
		settlementToggle.setFont(FontManager.getRunescapeBoldFont());
		settlementToggle.setForeground(Color.WHITE);
		settlementToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		settlementToggle.setBorder(new EmptyBorder(6, 8, 6, 8));
		settlementToggle.setFocusPainted(false);
		settlementToggle.setFocusable(false);
		settlementToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		settlementToggle.addActionListener(event -> {
			settlementExpanded = !settlementExpanded;
			settlementView.setVisible(settlementExpanded);
			updateSettlementToggleText();
			settlementSection.revalidate();
		});
		header.add(settlementToggle, BorderLayout.CENTER);
		settlementSection.add(header, BorderLayout.NORTH);
		settlementSection.add(settlementView, BorderLayout.CENTER);
		updateSettlementToggleText();
		return settlementSection;
	}

	private void renderConnection(PanelState state)
	{
		primaryButton.setText(inParty ? "Leave" : "Create party");
		secondaryButton.setVisible(!inParty);
		previousPartyButton.setVisible(!inParty);
		copyButton.setVisible(inParty);
		copyButton.setEnabled(partyPassphrase != null && !partyPassphrase.trim().isEmpty());
		primaryButton.setEnabled(state.isReady());
		secondaryButton.setEnabled(state.isReady());
		previousPartyButton.setEnabled(state.isReady() && state.isPreviousPartyAvailable());
		if (!state.isReady())
		{
			connectionStatus.setText("Loading profile...");
		}
		else if (!inParty)
		{
			connectionStatus.setText("Create or join a RuneLite Party to begin.");
		}
		else if (partyPassphrase == null || partyPassphrase.trim().isEmpty())
		{
			connectionStatus.setText("Connected to RuneLite Party.");
		}
		else
		{
			connectionStatus.setText("Passphrase: " + partyPassphrase);
		}
	}

	private void renderHostedSettings(HostedSettings hostedSettings)
	{
		boolean visible = inParty;
		hostedSettingsSection.setVisible(visible);
		if (!visible)
		{
			return;
		}

		hostedSettingsBody.removeAll();
		if (!hostedSettings.isAvailable())
		{
			hostedSettingsToggle.putClientProperty("hostName", null);
			JLabel waiting = new JLabel("Waiting for host settings...");
			waiting.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			waiting.setFont(FontManager.getRunescapeSmallFont());
			hostedSettingsBody.add(waiting);
		}
		else
		{
			String hostName = hostedSettings.getHostDisplayName()
				+ (hostedSettings.isLocalHost() ? " (you)" : "");
			hostedSettingsToggle.putClientProperty("hostName", hostName);
			LootshareSettings settings = hostedSettings.getSettings();
			hostedSettingsBody.add(settingRow("Host", hostName));
			hostedSettingsBody.add(settingRow("Minimum split",
				String.format(Locale.US, "%,d gp", settings.getMinimumSharedLootValue())));
		}
		hostedSettingsBody.setVisible(hostedSettingsExpanded);
		updateHostedSettingsToggleText();
	}

	private void updateHostedSettingsToggleText()
	{
		Object hostName = hostedSettingsToggle.getClientProperty("hostName");
		hostedSettingsToggle.setText((hostedSettingsExpanded ? "▾" : "▸") + " Host settings"
			+ (hostName == null ? "" : " — " + hostName));
	}

	private void renderMembers(List<MemberLoot> members)
	{
		memberHeaderLabel.setText("Party members (" + members.size() + ")");
		memberList.removeAll();
		memberCards.clear();
		if (!inParty)
		{
			memberList.add(emptyLabel("Join a party to see its members."));
			return;
		}
		if (members.isEmpty())
		{
			memberList.add(emptyLabel("Waiting for Party member sync..."));
			return;
		}

		Set<Long> currentMemberIds = new HashSet<>();
		for (MemberLoot member : members)
		{
			currentMemberIds.add(member.getMemberId());
			MemberCard card = new MemberCard(member, expandedMembers.contains(member.getMemberId()));
			memberCards.put(member.getMemberId(), card);
			memberList.add(card);
		}
		expandedMembers.retainAll(currentMemberIds);
	}

	private void renderSettlement(PanelState state)
	{
		settlementSection.setVisible(inParty);
		if (!inParty)
		{
			settlementSessionId = null;
			settlementExpanded = false;
			settlementAutoOpened = false;
			return;
		}
		if (!java.util.Objects.equals(settlementSessionId, state.getSessionId()))
		{
			settlementSessionId = state.getSessionId();
			settlementExpanded = false;
			settlementAutoOpened = false;
		}
		if (state.getSettlement().hasData() && !settlementAutoOpened)
		{
			settlementExpanded = true;
			settlementAutoOpened = true;
		}
		settlementView.render(state.getSettlement());
		settlementView.setVisible(settlementExpanded);
		updateSettlementToggleText();
	}

	private void updateSettlementToggleText()
	{
		settlementToggle.setText((settlementExpanded ? "▾" : "▸") + " Settlement");
	}

	private void onPrimaryAction()
	{
		if (!inParty)
		{
			actions.createParty();
			return;
		}
		if (interactions.confirm(this, "Leave party?", "Are you sure you want to leave the party?"))
		{
			actions.leaveParty();
		}
	}

	private void onJoinAction()
	{
		String passphrase = interactions.prompt(this, "Party passphrase", "Please enter the party passphrase:");
		if (passphrase != null && !actions.joinParty(passphrase))
		{
			interactions.showMessage(this,
				"Party passphrase must contain only letters, numbers, or hyphens.");
		}
	}

	void setExpanded(long memberId, boolean expanded)
	{
		if (expanded)
		{
			expandedMembers.add(memberId);
		}
		else
		{
			expandedMembers.remove(memberId);
		}
		MemberCard card = memberCards.get(memberId);
		if (card != null)
		{
			card.setExpanded(expanded);
		}
	}

	JButton getPrimaryButton()
	{
		return primaryButton;
	}

	JButton getSecondaryButton()
	{
		return secondaryButton;
	}

	JButton getCopyButton()
	{
		return copyButton;
	}

	JButton getPreviousPartyButton()
	{
		return previousPartyButton;
	}

	JLabel getConnectionStatus()
	{
		return connectionStatus;
	}

	JButton getHostedSettingsToggle()
	{
		return hostedSettingsToggle;
	}

	JPanel getHostedSettingsSection()
	{
		return hostedSettingsSection;
	}

	JPanel getHostedSettingsBody()
	{
		return hostedSettingsBody;
	}

	JButton getSettlementToggle()
	{
		return settlementToggle;
	}

	SettlementView getSettlementView()
	{
		return settlementView;
	}

	MemberCard getMemberCard(long memberId)
	{
		return memberCards.get(memberId);
	}

	final class MemberCard extends JPanel
	{
		private final long memberId;
		private final int lootItemCount;
		private final JLabel chevron = new JLabel();
		private final JPanel lootContainer = new JPanel(new BorderLayout());
		private final JPanel header;

		private MemberCard(MemberLoot member, boolean expanded)
		{
			memberId = member.getMemberId();
			lootItemCount = member.getItems().size();
			setLayout(new BorderLayout(0, 1));
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			setBorder(new EmptyBorder(5, 0, 0, 0));

			header = buildMemberHeader(member);
			MouseAdapter toggleListener = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent event)
				{
					if (SwingUtilities.isLeftMouseButton(event))
					{
						Panel.this.setExpanded(memberId, !lootContainer.isVisible());
					}
				}
			};
			installHeaderInteraction(header, toggleListener,
				"Show or hide " + member.getDisplayName() + "'s drops");
			add(header, BorderLayout.NORTH);

			lootContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			lootContainer.setBorder(new EmptyBorder(5, 5, 5, 5));
			lootContainer.add(buildLootGrid(member), BorderLayout.CENTER);
			add(lootContainer, BorderLayout.CENTER);
			setExpanded(expanded);
		}

		private void installHeaderInteraction(Component component, MouseAdapter listener, String tooltip)
		{
			if (component instanceof AbstractButton)
			{
				return;
			}
			component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			component.addMouseListener(listener);
			if (component instanceof JComponent)
			{
				((JComponent) component).setToolTipText(tooltip);
			}
			if (component instanceof Container)
			{
				for (Component child : ((Container) component).getComponents())
				{
					installHeaderInteraction(child, listener, tooltip);
				}
			}
		}

		private JPanel buildMemberHeader(MemberLoot member)
		{
			JPanel header = new JPanel(new BorderLayout(6, 0));
			header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			header.setBorder(new EmptyBorder(6, 6, 6, 6));

			JLabel avatar = new JLabel();
			avatar.setHorizontalAlignment(SwingConstants.CENTER);
			avatar.setVerticalAlignment(SwingConstants.CENTER);
			avatar.setPreferredSize(new Dimension(34, 34));
			avatar.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
			BufferedImage avatarImage = member.getAvatar();
			if (avatarImage != null)
			{
				avatar.setIcon(new ImageIcon(ImageUtil.resizeImage(avatarImage, 32, 32)));
			}
			header.add(avatar, BorderLayout.WEST);

			JPanel labels = new JPanel();
			labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
			labels.setOpaque(false);
			JLabel name = new JLabel(memberDisplayName(member));
			name.setFont(FontManager.getRunescapeSmallFont());
			name.setForeground(member.isLoggedIn() ? Color.WHITE : DISCONNECTED_COLOR);
			name.putClientProperty("html.disable", Boolean.TRUE);
			JLabel summary = new JLabel(memberSummary(member));
			summary.setFont(FontManager.getRunescapeSmallFont());
			summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			labels.add(name);
			labels.add(summary);
			header.add(labels, BorderLayout.CENTER);

			chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			chevron.setHorizontalAlignment(SwingConstants.CENTER);
			chevron.setPreferredSize(new Dimension(14, 34));
			JPanel right = new JPanel(new BorderLayout(4, 0));
			right.setOpaque(false);
			right.add(chevron, BorderLayout.EAST);
			header.add(right, BorderLayout.EAST);
			return header;
		}

		private JPanel buildLootGrid(MemberLoot member)
		{
			if (member.getItems().isEmpty())
			{
				JPanel empty = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
				empty.setOpaque(false);
				JLabel text = new JLabel("No captured drops yet");
				text.setFont(FontManager.getRunescapeSmallFont());
				text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.add(text);
				return empty;
			}

			int rows = (member.getItems().size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
			JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, 1, 1));
			grid.setOpaque(false);
			for (int index = 0; index < rows * ITEMS_PER_ROW; index++)
			{
				JPanel slot = new JPanel(new BorderLayout());
				slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				slot.setPreferredSize(new Dimension(ITEM_SLOT_SIZE, ITEM_SLOT_SIZE));
				if (index < member.getItems().size())
				{
					LootItem item = member.getItems().get(index);
					JLabel image = new JLabel();
					image.setHorizontalAlignment(SwingConstants.CENTER);
					image.setVerticalAlignment(SwingConstants.CENTER);
					image.setToolTipText(itemToolTip(item));
					try
					{
						AsyncBufferedImage itemImage = itemManager.getImage(
							item.getItemId(), imageQuantity(item.getQuantity()), item.getQuantity() > 1L);
						if (itemImage != null)
						{
							itemImage.addTo(image);
						}
					}
					catch (RuntimeException e)
					{
						log.debug("Unable to render Community Lootshare item image for {}", item.getItemId(), e);
					}
					slot.add(image, BorderLayout.CENTER);
				}
				grid.add(slot);
			}
			return grid;
		}

		boolean isExpanded()
		{
			return lootContainer.isVisible();
		}

		private void setExpanded(boolean expanded)
		{
			lootContainer.setVisible(expanded);
			chevron.setText(expanded ? "▾" : "▸");
			revalidate();
			repaint();
		}

		int getLootItemCount()
		{
			return lootItemCount;
		}

		JPanel getHeader()
		{
			return header;
		}

	}
}
