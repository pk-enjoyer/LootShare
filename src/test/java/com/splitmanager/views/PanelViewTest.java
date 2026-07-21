/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.splitmanager.models.PendingValue;
import com.splitmanager.testing.RecordingPanelActions;
import com.splitmanager.testing.RecordingUiInteractions;
import com.splitmanager.testing.UiScenarioFixture;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PanelViewTest
{
	@Test
	public void forwardsSidebarInputsToPanelActionsOnEdt() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.empty();
		RecordingPanelActions actions = new RecordingPanelActions();
		RecordingUiInteractions interactions = new RecordingUiInteractions();
		AtomicReference<PanelView> viewReference = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() -> viewReference.set(new PanelView(
			fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), actions, interactions)));
		PanelView view = viewReference.get();

		SwingUtilities.invokeAndWait(() -> {
			assertSame(JPanel.class, view.getClass().getSuperclass());

			view.getNewPlayerField().setText("Dora");
			view.getNewPlayerField().postActionEvent();
			assertEquals("addKnownPlayer", actions.getLastAction());
			assertEquals("Dora", actions.getFirstValue());

			view.getNotInCurrentSessionPlayerDropdown().addItem("Alice");
			view.getNotInCurrentSessionPlayerDropdown().setSelectedItem("Alice");
			view.getBtnAddToSession().setEnabled(true);
			view.getBtnAddToSession().doClick();
			assertEquals("addPlayerToSession", actions.getLastAction());
			assertEquals("Alice", actions.getFirstValue());

			view.getBtnAddLoot().setEnabled(true);
			view.getBtnAddLoot().doClick();
			assertEquals("addLootFromInputs", actions.getLastAction());

			PendingValue pending = PendingValue.of(
				PendingValue.Type.ADD, "Clan", "!add 100", 100_000L, "Alice");
			view.getWaitlistTableModel().setData(Collections.singletonList(pending));
			view.getWaitlistTable().setRowSelectionInterval(0, 0);
			view.getBtnWaitlistAdd().setEnabled(true);
			view.getBtnWaitlistAdd().doClick();
			assertEquals("applySelectedPendingValue", actions.getLastAction());
			assertEquals(0, actions.getRow());

			view.getBtnCopyJson().doClick();
			assertEquals("copyMetricsJson", actions.getLastAction());
			view.getBtnCopyMd().doClick();
			assertEquals("copyMetricsMarkdown", actions.getLastAction());

			view.getBtnToggleEdit().doClick();
			assertEquals("togglePopout", actions.getLastAction());
			assertTrue(actions.getFlag());
		});
	}
}
