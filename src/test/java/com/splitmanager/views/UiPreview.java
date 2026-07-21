/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.splitmanager.controllers.PanelController;
import com.splitmanager.controllers.PanelCoordinator;
import com.splitmanager.testing.RecordingUiInteractions;
import com.splitmanager.testing.UiScenarioFixture;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Manual visual preview backed entirely by local in-memory fixtures.
 */
public final class UiPreview
{
	private UiPreview()
	{
	}

	public static void main(String[] args)
	{
		SwingUtilities.invokeLater(UiPreview::show);
	}

	private static void show()
	{
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Sidebar", buildSidebarPreview());
		tabs.addTab("Popout + history", buildPopoutPreview());

		JFrame frame = new JFrame("Auto Split Manager UI Preview (stub data)");
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.setLayout(new BorderLayout());
		frame.add(tabs, BorderLayout.CENTER);
		frame.setMinimumSize(new Dimension(1050, 700));
		frame.setSize(new Dimension(1200, 800));
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private static JScrollPane buildSidebarPreview()
	{
		UiScenarioFixture fixture = UiScenarioFixture.active();
		RecordingUiInteractions interactions = new RecordingUiInteractions();
		PanelController controller = new PanelController(
			fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), new PreviewCoordinator(), interactions);
		PanelView view = new PanelView(
			fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), controller, interactions);
		controller.setView(view);
		controller.refreshAllView();
		JScrollPane scrollPane = new JScrollPane(view);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		return scrollPane;
	}

	private static PopoutView buildPopoutPreview()
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(true);
		RecordingUiInteractions interactions = new RecordingUiInteractions();
		PanelController controller = new PanelController(
			fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), new PreviewCoordinator(), interactions);
		PopoutView view = new PopoutView(
			fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), controller, interactions);
		controller.setView(view.getSidebarView());
		controller.refreshAllView();
		return view;
	}

	private static final class PreviewCoordinator implements PanelCoordinator
	{
		@Override public void refreshAllViews() { }
		@Override public void showPopout(boolean editMode) { }
	}
}
