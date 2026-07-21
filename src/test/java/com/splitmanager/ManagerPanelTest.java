/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager;

import com.splitmanager.testing.RecordingUiInteractions;
import com.splitmanager.testing.UiScenarioFixture;
import com.splitmanager.ui.PopoutWindow;
import com.splitmanager.ui.PopoutWindowFactory;
import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ManagerPanelTest
{
	@Test
	public void hostsPlainSidebarAndReusesStubbedPopoutWindow() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.active();
		RecordingUiInteractions interactions = new RecordingUiInteractions();
		RecordingWindowFactory windows = new RecordingWindowFactory();
		ManagerPanel managerPanel = new ManagerPanel(
			fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), interactions, windows);

		SwingUtilities.invokeAndWait(() -> {
			managerPanel.init();
			assertNotNull(managerPanel.getRuneLitePanel());
			assertSame(managerPanel.getView(), managerPanel.getRuneLitePanel().getContent());

			managerPanel.showPopout(false);
			assertEquals(1, windows.openCount);
			assertNotNull(windows.content);
			managerPanel.showPopout(true);
			assertEquals(1, windows.openCount);
			assertEquals(1, windows.window.focusCount);

			windows.onClosed.run();
			managerPanel.showPopout(false);
			assertEquals(2, windows.openCount);
			managerPanel.restart();
			assertTrue(windows.window.disposed);
			managerPanel.showPopout(false);
			managerPanel.shutDown();
			assertTrue(windows.window.disposed);
		});
	}

	private static final class RecordingWindowFactory implements PopoutWindowFactory
	{
		private int openCount;
		private JComponent content;
		private Runnable onClosed;
		private RecordingWindow window;

		@Override
		public PopoutWindow open(String title, JComponent content, Dimension minimumSize, Dimension size,
		                         Runnable onClosed)
		{
			openCount++;
			this.content = content;
			this.onClosed = onClosed;
			this.window = new RecordingWindow();
			return window;
		}
	}

	private static final class RecordingWindow implements PopoutWindow
	{
		private int focusCount;
		private boolean disposed;

		@Override public boolean isDisplayable() { return !disposed; }
		@Override public void focus() { focusCount++; }
		@Override public void dispose() { disposed = true; }
	}
}
