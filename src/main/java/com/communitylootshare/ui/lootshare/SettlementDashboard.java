/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.ui.lootshare.PanelState.SettlementState;
import com.communitylootshare.views.graph.SessionGraphMode;
import com.communitylootshare.views.graph.SessionGraphPanel;
import com.communitylootshare.views.graph.SessionGraphSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Standalone settlement dashboard shown in the reusable popout window.
 */
public final class SettlementDashboard extends JPanel
{
	private static final int REFRESH_INTERVAL_MILLIS = 60_000;

	private final SettlementView settlementView = new SettlementView();
	private final SessionGraphPanel graphPanel = new SessionGraphPanel();
	private final JComboBox<SessionGraphMode> graphModes = new JComboBox<>(SessionGraphMode.values());
	private final JLabel totalLoot = statValue();
	private final JLabel gpPerHour = statValue();
	private final JLabel topEarner = statValue();
	private final Timer refreshTimer;
	private SettlementState state = SettlementState.empty();

	public SettlementDashboard(Runnable refreshAction)
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel left = new JPanel(new BorderLayout());
		left.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		left.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		left.add(settlementView, BorderLayout.NORTH);

		JPanel right = new JPanel(new BorderLayout(0, 8));
		right.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		controls.setOpaque(false);
		JLabel graphLabel = new JLabel("Graph");
		graphLabel.setForeground(Color.WHITE);
		graphLabel.setFont(FontManager.getRunescapeBoldFont());
		controls.add(graphLabel);
		controls.add(graphModes);
		right.add(controls, BorderLayout.NORTH);
		right.add(graphPanel, BorderLayout.CENTER);
		right.add(buildStats(), BorderLayout.SOUTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setBorder(null);
		split.setResizeWeight(0.36d);
		split.setDividerLocation(360);
		add(split, BorderLayout.CENTER);
		setMinimumSize(new Dimension(760, 480));

		graphModes.addActionListener(event -> updateGraph());
		refreshTimer = new Timer(REFRESH_INTERVAL_MILLIS, event -> {
			if (refreshAction != null)
			{
				refreshAction.run();
			}
		});
		refreshTimer.start();
		render(state);
	}

	private static JPanel stat(String label, JLabel value)
	{
		JPanel panel = new JPanel(new BorderLayout(0, 2));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(7, 8, 7, 8));
		JLabel key = new JLabel(label);
		key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		key.setFont(FontManager.getRunescapeSmallFont());
		panel.add(key, BorderLayout.NORTH);
		panel.add(value, BorderLayout.CENTER);
		return panel;
	}

	private static JLabel statValue()
	{
		JLabel label = new JLabel("—");
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.putClientProperty("html.disable", Boolean.TRUE);
		return label;
	}

	private static String compact(long value)
	{
		return QuantityFormatter.quantityToStackSize(value);
	}

	private static String exact(long value)
	{
		return String.format(Locale.US, "%,d gp", value);
	}

	public void render(SettlementState state)
	{
		this.state = state == null ? SettlementState.empty() : state;
		settlementView.render(this.state);
		updateGraph();
	}

	public void stop()
	{
		refreshTimer.stop();
	}

	private JPanel buildStats()
	{
		JPanel stats = new JPanel(new GridLayout(1, 3, 8, 0));
		stats.setOpaque(false);
		stats.add(stat("Shared loot", totalLoot));
		stats.add(stat("Current GP/hr", gpPerHour));
		stats.add(stat("Highest earnings", topEarner));
		return stats;
	}

	private void updateGraph()
	{
		SessionGraphMode mode = (SessionGraphMode) graphModes.getSelectedItem();
		SessionGraphSnapshot snapshot = state.getGraph(mode);
		graphPanel.setSnapshot(snapshot);
		totalLoot.setText(compact(snapshot.getTotalLoot()) + " gp");
		totalLoot.setToolTipText(exact(snapshot.getTotalLoot()));
		gpPerHour.setText(compact(snapshot.getGpPerHour()) + " gp/hr");
		gpPerHour.setToolTipText(exact(snapshot.getGpPerHour()) + "/hr");
		topEarner.setText(snapshot.getTopPlayer().isEmpty()
			? "—"
			: snapshot.getTopPlayer() + " · " + compact(snapshot.getTopPlayerTotal()));
		topEarner.setToolTipText(snapshot.getTopPlayer().isEmpty()
			? null
			: snapshot.getTopPlayer() + ": " + exact(snapshot.getTopPlayerTotal()));
	}

	SettlementView getSettlementView()
	{
		return settlementView;
	}

	SessionGraphPanel getGraphPanel()
	{
		return graphPanel;
	}

	JComboBox<SessionGraphMode> getGraphModes()
	{
		return graphModes;
	}

	boolean isRefreshTimerRunning()
	{
		return refreshTimer.isRunning();
	}
}
