/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.splitmanager.testing.RecordingPanelActions;
import com.splitmanager.testing.RecordingUiInteractions;
import com.splitmanager.testing.UiScenarioFixture;
import com.splitmanager.views.graph.SessionGraphMode;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PopoutViewTest
{
	@Test
	public void constructsGraphAndHistorySurfacesInHeadlessMode() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(true);
		RecordingPanelActions actions = new RecordingPanelActions();
		RecordingUiInteractions interactions = new RecordingUiInteractions();

		SwingUtilities.invokeAndWait(() -> {
			PopoutView popout = new PopoutView(
				fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), actions, interactions);
			assertNotNull(popout.getSidebarView());
			assertNotNull(popout.getGraphModeDropdown());
			assertFalse(popout.isGraphRefreshTimerRunning());

			popout.getGraphModeDropdown().setSelectedItem(SessionGraphMode.HIGHEST_EARNINGS);
			assertEquals(SessionGraphMode.HIGHEST_EARNINGS.getDescription(),
				popout.getGraphDescriptionLabel().getText());
			popout.getShowSleepingPlayersToggle().doClick();

			popout.setEditMode(true);
			assertNotNull(popout.getHistoryEditor());
			assertTrue(popout.getHistoryEditor().getHistoryModel().getRowCount() > 0);

			popout.addNotify();
			assertTrue(popout.isGraphRefreshTimerRunning());
			popout.removeNotify();
			assertFalse(popout.isGraphRefreshTimerRunning());
		});
	}
}
