/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.debug.DebugSession;
import com.communitylootshare.debug.DebugSession.LootPreset;
import com.communitylootshare.ui.lootshare.PanelState.DebugState;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Developer-mode controls for the isolated Community Lootshare simulation.
 */
final class DebugPanel extends JPanel
{
	private static final Color SUCCESS_COLOR = new Color(110, 190, 110);
	private static final Color ERROR_COLOR = new Color(220, 110, 110);

	private final PanelActions actions;
	private final JButton simulationButton = new JButton("Start simulation");
	private final JPanel simulationControls = new JPanel();
	private final JTextField playerNameField = new JTextField();
	private final JButton addPlayerButton = new JButton("Add fake player");
	private final JComboBox<MemberChoice> removablePlayers = new JComboBox<>();
	private final JButton removePlayerButton = new JButton("Remove player");
	private final JComboBox<LootPreset> lootPresets = new JComboBox<>(LootPreset.values());
	private final JComboBox<MemberChoice> lootOwners = new JComboBox<>();
	private final JSpinner lootValue = new JSpinner(new SpinnerNumberModel(
		500_000L, 1L, DebugSession.MAX_SAMPLE_VALUE, 50_000L));
	private final JPanel lootValueControl;
	private final JLabel itemPriceValue = new JLabel();
	private final JPanel itemPriceControl;
	private final JButton addLootButton = new JButton("Add sample loot");
	private final JButton resetButton = new JButton("Reset simulation");
	private final JLabel feedback = new JLabel(" ");

	private boolean simulationActive;
	private DebugState debugState = DebugState.unavailable();

	DebugPanel(PanelActions actions)
	{
		this.actions = actions;
		setLayout(new BorderLayout(0, 5));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setVisible(false);

		JLabel title = new JLabel("Developer simulation");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		add(title, BorderLayout.NORTH);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.setBorder(new EmptyBorder(8, 8, 8, 8));
		JLabel warning = new JLabel("<html>Local-only fake data. Nothing is sent to Party or saved.</html>");
		warning.setFont(FontManager.getRunescapeSmallFont());
		warning.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		body.add(warning);
		body.add(Box.createRigidArea(new Dimension(0, 6)));
		body.add(simulationButton);
		body.add(Box.createRigidArea(new Dimension(0, 6)));

		simulationControls.setLayout(new BoxLayout(simulationControls, BoxLayout.Y_AXIS));
		simulationControls.setOpaque(false);
		simulationControls.add(labeledField("Fake player name", playerNameField));
		simulationControls.add(addPlayerButton);
		simulationControls.add(Box.createRigidArea(new Dimension(0, 5)));
		simulationControls.add(labeledField("Remove fake player", removablePlayers));
		simulationControls.add(removePlayerButton);
		simulationControls.add(Box.createRigidArea(new Dimension(0, 5)));
		simulationControls.add(labeledField("Loot item", lootPresets));
		simulationControls.add(labeledField("Loot recipient", lootOwners));
		lootValueControl = labeledField("Coins value (gp)", lootValue);
		itemPriceValue.setFont(FontManager.getRunescapeSmallFont());
		itemPriceValue.setForeground(Color.WHITE);
		itemPriceControl = labeledField("RuneLite item price", itemPriceValue);
		simulationControls.add(lootValueControl);
		simulationControls.add(itemPriceControl);
		simulationControls.add(addLootButton);
		simulationControls.add(Box.createRigidArea(new Dimension(0, 5)));
		simulationControls.add(resetButton);
		body.add(simulationControls);

		feedback.setFont(FontManager.getRunescapeSmallFont());
		feedback.setBorder(new EmptyBorder(5, 0, 0, 0));
		body.add(feedback);
		add(body, BorderLayout.CENTER);

		for (JButton button : new JButton[]{simulationButton, addPlayerButton, removePlayerButton,
			addLootButton, resetButton})
		{
			button.setFocusable(false);
			button.setAlignmentX(LEFT_ALIGNMENT);
		}
		lootValue.setEditor(new JSpinner.NumberEditor(lootValue, "#,##0"));
		bindActions();
		updateLootPricingControls();
	}

	private static String formatCoins(long value)
	{
		return String.format(Locale.US, "%,d gp", value);
	}

	private static JPanel labeledField(String labelText, java.awt.Component field)
	{
		JPanel panel = new JPanel(new GridLayout(0, 1, 0, 2));
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(0, 0, 4, 0));
		JLabel label = new JLabel(labelText);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label);
		panel.add(field);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		return panel;
	}

	private static Long selectedMemberId(JComboBox<MemberChoice> comboBox)
	{
		MemberChoice selected = (MemberChoice) comboBox.getSelectedItem();
		return selected == null ? null : selected.memberId;
	}

	private static void restoreSelection(JComboBox<MemberChoice> comboBox, Long memberId)
	{
		if (memberId == null)
		{
			return;
		}
		for (int index = 0; index < comboBox.getItemCount(); index++)
		{
			if (comboBox.getItemAt(index).memberId == memberId)
			{
				comboBox.setSelectedIndex(index);
				return;
			}
		}
	}

	void render(PanelState state)
	{
		DebugState debug = state.getDebug();
		debugState = debug;
		setVisible(debug.isAvailable());
		simulationActive = debug.isSimulationActive();
		simulationButton.setText(simulationActive ? "Return to live Party" : "Start simulation");
		simulationControls.setVisible(simulationActive);
		if (simulationActive)
		{
			populatePlayers(state.getMembers(), debug.getOwnerMemberId());
		}
		else
		{
			removablePlayers.removeAllItems();
			lootOwners.removeAllItems();
		}
		updateLootPricingControls();
	}

	private void bindActions()
	{
		simulationButton.addActionListener(event -> {
			clearFeedback();
			if (simulationActive)
			{
				actions.stopDebugSimulation();
			}
			else
			{
				actions.startDebugSimulation();
			}
		});
		addPlayerButton.addActionListener(event -> {
			if (actions.addDebugPlayer(playerNameField.getText()))
			{
				playerNameField.setText("");
				showFeedback("Fake player added.", true);
			}
			else
			{
				showFeedback("Enter a unique player name (64 characters max).", false);
			}
		});
		playerNameField.addActionListener(event -> addPlayerButton.doClick());
		lootPresets.addActionListener(event -> updateLootPricingControls());
		removePlayerButton.addActionListener(event -> {
			MemberChoice selected = (MemberChoice) removablePlayers.getSelectedItem();
			if (selected != null && actions.removeDebugPlayer(selected.memberId))
			{
				showFeedback("Fake player removed. Existing splits keep their frozen roster.", true);
			}
			else
			{
				showFeedback("Choose a fake player to remove.", false);
			}
		});
		addLootButton.addActionListener(event -> {
			MemberChoice selected = (MemberChoice) lootOwners.getSelectedItem();
			LootPreset lootPreset = (LootPreset) lootPresets.getSelectedItem();
			long value = lootPreset != null && lootPreset.usesItemPrice()
				? debugState.getLootPresetPrice(lootPreset)
				: ((Number) lootValue.getValue()).longValue();
			if (selected != null && lootPreset != null
				&& actions.addDebugLoot(selected.memberId, lootPreset, value))
			{
				showFeedback(lootPreset.getDisplayName() + " added at " + formatCoins(value) + ".", true);
			}
			else
			{
				showFeedback("Choose a recipient and ensure the loot price is available.", false);
			}
		});
		resetButton.addActionListener(event -> {
			actions.resetDebugSimulation();
			clearFeedback();
		});
	}

	private void populatePlayers(List<MemberLoot> members, long ownerMemberId)
	{
		Long selectedRemove = selectedMemberId(removablePlayers);
		Long selectedOwner = selectedMemberId(lootOwners);
		removablePlayers.removeAllItems();
		lootOwners.removeAllItems();
		for (MemberLoot member : members)
		{
			MemberChoice choice = new MemberChoice(member.getMemberId(), member.getDisplayName());
			lootOwners.addItem(choice);
			if (member.getMemberId() != ownerMemberId)
			{
				removablePlayers.addItem(choice);
			}
		}
		restoreSelection(removablePlayers, selectedRemove);
		restoreSelection(lootOwners, selectedOwner);
		removePlayerButton.setEnabled(removablePlayers.getItemCount() > 0);
	}

	private void updateLootPricingControls()
	{
		LootPreset lootPreset = (LootPreset) lootPresets.getSelectedItem();
		boolean usesItemPrice = lootPreset != null && lootPreset.usesItemPrice();
		long itemPrice = usesItemPrice ? debugState.getLootPresetPrice(lootPreset) : 0L;
		lootValueControl.setVisible(!usesItemPrice);
		itemPriceControl.setVisible(usesItemPrice);
		itemPriceValue.setText(itemPrice > 0L ? formatCoins(itemPrice) : "Price unavailable");
		addLootButton.setEnabled(lootOwners.getItemCount() > 0 && (!usesItemPrice || itemPrice > 0L));
		revalidate();
		repaint();
	}

	private void showFeedback(String message, boolean success)
	{
		feedback.setText("<html>" + message + "</html>");
		feedback.setForeground(success ? SUCCESS_COLOR : ERROR_COLOR);
	}

	private void clearFeedback()
	{
		feedback.setText(" ");
	}

	JButton getSimulationButton()
	{
		return simulationButton;
	}

	JPanel getSimulationControls()
	{
		return simulationControls;
	}

	JTextField getPlayerNameField()
	{
		return playerNameField;
	}

	JButton getAddPlayerButton()
	{
		return addPlayerButton;
	}

	JComboBox<MemberChoice> getRemovablePlayers()
	{
		return removablePlayers;
	}

	JButton getRemovePlayerButton()
	{
		return removePlayerButton;
	}

	JComboBox<MemberChoice> getLootOwners()
	{
		return lootOwners;
	}

	JComboBox<LootPreset> getLootPresets()
	{
		return lootPresets;
	}

	JSpinner getLootValue()
	{
		return lootValue;
	}

	JPanel getLootValueControl()
	{
		return lootValueControl;
	}

	JPanel getItemPriceControl()
	{
		return itemPriceControl;
	}

	JLabel getItemPriceValue()
	{
		return itemPriceValue;
	}

	JButton getAddLootButton()
	{
		return addLootButton;
	}

	JButton getResetButton()
	{
		return resetButton;
	}

	JLabel getFeedback()
	{
		return feedback;
	}

	static final class MemberChoice
	{
		private final long memberId;
		private final String displayName;

		private MemberChoice(long memberId, String displayName)
		{
			this.memberId = memberId;
			this.displayName = displayName;
		}

		long getMemberId()
		{
			return memberId;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
