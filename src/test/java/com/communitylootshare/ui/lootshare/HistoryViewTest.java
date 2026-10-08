/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.*;
import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.testing.RecordingUiInteractions;
import com.communitylootshare.ui.PopoutWindow;
import com.communitylootshare.ui.PopoutWindowFactory;
import com.communitylootshare.ui.lootshare.PanelState.HostedSettings;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import com.communitylootshare.ui.lootshare.PanelState.SettlementState;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.party.PartyConfig;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HistoryViewTest
{
	@Test public void hostOnlyApplicationCarriesDisplayedAuthorityAndHistoryWorksOutsideParty() throws Exception
	{
		PanelActions actions = mock(PanelActions.class);
		SwingUtilities.invokeAndWait(() -> {
			Panel panel = new Panel(actions, new RecordingUiInteractions(), mock(ItemManager.class));
			panel.render(state(true, true, true));
			JButton apply = button(panel, "Apply my settings");
			assertTrue(apply.isVisible()); assertTrue(apply.isEnabled());
			apply.doClick(); verify(actions).applyMySettings(77L, 1L, 9L);
			panel.render(state(true, true, false));
			assertFalse(button(panel, "Apply my settings").isEnabled());
			panel.render(state(true, false, true));
			assertNull(findButton(panel, "Apply my settings"));
			LootshareSession stored = new LootshareSession("stored", 77L, Instant.EPOCH);
			stored.setHostState(1L, LootshareSettings.defaults(), 1L);
			panel.render(new PanelState(true, false, null, Collections.emptyList(), false, HostedSettings.waiting(), null,
				SettlementState.empty(), Collections.singletonList(stored), "History could not be saved."));
			assertTrue(button(panel, "History ↗").isEnabled());
			button(panel, "History ↗").doClick(); verify(actions).openHistory();
		});
	}

	@Test public void historyWindowIsReusedReopenedAndDisposedWithController() throws Exception
	{
		ClientThread thread = mock(ClientThread.class);
		doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(thread).invokeLater(any(Runnable.class));
		LootshareController backend = mock(LootshareController.class); when(backend.isReady()).thenReturn(true);
		RecordingFactory windows = new RecordingFactory();
		UiController controller = new UiController(mock(Client.class), thread, mock(PartyService.class), mock(ItemManager.class),
			backend, (PartyConfig) null, windows);
		controller.start(mock(Panel.class));
		SwingUtilities.invokeAndWait(() -> {
			controller.openHistory(); controller.openHistory();
			assertEquals(1, windows.opens); assertEquals(1, windows.window.focusCount);
			assertTrue(windows.content instanceof HistoryView);
			windows.window.dispose(); windows.onClosed.run();
			controller.openHistory(); assertEquals(2, windows.opens);
		});
		controller.applyMySettings(77L, 1L, 4L); verify(backend).applyMySettings(77L, 1L, 4L);
		controller.stop(); SwingUtilities.invokeAndWait(() -> { });
		assertTrue(windows.window.disposed);
		SwingUtilities.invokeAndWait(controller::openHistory); assertEquals(2, windows.opens);
	}

	@Test public void failedOrSynchronouslyClosedPopoutsCanBeOpenedAgain() throws Exception
	{
		ClientThread thread = mock(ClientThread.class);
		doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(thread).invokeLater(any(Runnable.class));
		PopoutWindowFactory windows = mock(PopoutWindowFactory.class);
		when(windows.open(anyString(), any(JComponent.class), any(Dimension.class), any(Dimension.class), any(Runnable.class)))
			.thenThrow(new IllegalStateException("No window"));
		UiController controller = new UiController(mock(Client.class), thread, mock(PartyService.class), mock(ItemManager.class),
			mock(LootshareController.class), (PartyConfig) null, windows);
		controller.start(mock(Panel.class));
		SwingUtilities.invokeAndWait(controller::openHistory);
		doAnswer(call -> { ((Runnable) call.getArgument(4)).run(); return mock(PopoutWindow.class); })
			.when(windows).open(anyString(), any(JComponent.class), any(Dimension.class), any(Dimension.class), any(Runnable.class));
		SwingUtilities.invokeAndWait(() -> { controller.openHistory(); controller.openHistory(); });
		verify(windows, times(3)).open(anyString(), any(JComponent.class), any(Dimension.class), any(Dimension.class), any(Runnable.class));
		controller.stop(); SwingUtilities.invokeAndWait(() -> { });
	}

	@Test public void completedSessionsShowFrozenTotalsSettingsTimelineAndUnknownHistory() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		LootshareSession session = new LootshareSession("stored", 77L, Instant.EPOCH);
		session.setHostState(2L, LootshareSettings.defaults(2000), 3L);
		session.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		HostPeriod first = new HostPeriod("first", 1L, "Alice", null, Instant.EPOCH, null);
		HostPeriod next = new HostPeriod("second", 2L, "Bob", "first", Instant.ofEpochSecond(2), null);
		SettlementSummary before = new SettlementSummary(1000,
			Arrays.asList(new LootshareCalculation.Balance(1L, "Alice", 1000, 500), new LootshareCalculation.Balance(2L, "Bob", 0, 500)),
			Collections.singletonList(new LootshareCalculation.Transfer(1L, "Alice", 2L, "Bob", 500)), Collections.singletonList("loot"));
		SettlementSummary empty = SettlementSummary.from(LootshareCalculation.empty());
		HostHistoryEvent audit = new HostHistoryEvent("apply", 77L, 2L, first, Instant.ofEpochSecond(1), HostHistoryEvent.Kind.SETTINGS_APPLIED,
			HostHistoryEvent.Reason.SETTINGS, LootshareSettings.defaults(100), LootshareSettings.defaults(2000), before, empty, true, null).sign(1L, key);
		HostHistoryEvent closure = new HostHistoryEvent("close:first", 77L, 3L, first.end(Instant.ofEpochSecond(2)), Instant.ofEpochSecond(2),
			HostHistoryEvent.Kind.PERIOD_CLOSED, HostHistoryEvent.Reason.DEPARTURE, LootshareSettings.defaults(2000), LootshareSettings.defaults(2000), empty, empty, false, null).sign(1L, key);
		Map<String, HistoryCommitment> anchors = new LinkedHashMap<>();
		anchors.put(audit.getEventId(), new HistoryCommitment(2L, audit.commitment()));
		anchors.put(closure.getEventId(), new HistoryCommitment(3L, closure.commitment()));
		session.setHistoryMetadata(next, anchors, true, true); session.addHistoryEvent(closure); session.addHistoryEvent(audit);
		SwingUtilities.invokeAndWait(() -> {
			HistoryView view = new HistoryView(); view.render(Collections.singletonList(session));
			JTree tree = component(view, JTree.class); JTextArea text = component(view, JTextArea.class);
			DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
			DefaultMutableTreeNode party = (DefaultMutableTreeNode) root.getChildAt(0);
			DefaultMutableTreeNode completed = (DefaultMutableTreeNode) party.getChildAt(party.getChildCount() - 1);
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) completed.getChildAt(0); tree.setSelectionPath(new TreePath(node.getPath()));
			assertTrue(text.getText().contains("Cumulative checkpoint total: 0 gp"));
			assertTrue(text.getText().contains("INCOMPLETE")); assertTrue(text.getText().contains("Before"));
			assertTrue(text.getText().contains("Cumulative checkpoint total: 1,000 gp"));
			assertTrue(text.getText().contains("Alice → Bob: 500 gp"));
			assertTrue(text.getText().contains("Earlier history omitted")); assertTrue(text.getText().contains("Earlier host chain unknown"));
			view.render(Collections.singletonList(session));
			assertTrue(text.getText().contains("INCOMPLETE"));
			view.render(Collections.emptyList()); assertEquals("No stored host history.", text.getText());
		});
	}
	private static PanelState state(boolean inParty, boolean localHost, boolean enabled)
	{
		List<MemberLoot> members = Collections.singletonList(new MemberLoot(1L, "Alice", localHost, true, true, null, 0, 0, 0L, Collections.emptyList()));
		return new PanelState(true, inParty, "pass", members, false,
			HostedSettings.available(1L, "Alice", localHost, LootshareSettings.defaults(), 77L, 9L, enabled));
	}
	private static JButton button(Container parent, String text)
	{
		JButton button = findButton(parent, text); assertNotNull(button); return button;
	}
	private static JButton findButton(Container parent, String text)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JButton && ((JButton) child).getText().equals(text)) { return (JButton) child; }
			if (child instanceof Container) { JButton result = findButton((Container) child, text); if (result != null) { return result; } }
		}
		return null;
	}
	private static <T> T component(Container parent, Class<T> type)
	{
		for (Component child : parent.getComponents())
		{
			if (type.isInstance(child)) { return type.cast(child); }
			if (child instanceof Container) { T result = component((Container) child, type); if (result != null) { return result; } }
		}
		return null;
	}
	private static final class RecordingFactory implements PopoutWindowFactory
	{
		private int opens;
		private RecordingWindow window;
		private JComponent content;
		private Runnable onClosed;
		@Override public PopoutWindow open(String title, JComponent content, Dimension min, Dimension size, Runnable close)
		{
			opens++; this.content = content; onClosed = close; window = new RecordingWindow(); return window;
		}
	}
	private static final class RecordingWindow implements PopoutWindow
	{
		private boolean disposed;
		private int focusCount;
		@Override public boolean isDisplayable() { return !disposed; }
		@Override public void focus() { focusCount++; }
		@Override public void dispose() { disposed = true; }
	}
}
