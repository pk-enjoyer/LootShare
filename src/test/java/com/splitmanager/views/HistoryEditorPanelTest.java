/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.splitmanager.ManagerSession;
import com.splitmanager.PluginConfig;
import com.splitmanager.controllers.PanelActions;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.testing.RecordingPanelActions;
import com.splitmanager.testing.RecordingUiInteractions;
import com.splitmanager.testing.UiScenarioFixture;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.Test;

public class HistoryEditorPanelTest
{
	@Test
	public void supportsHeadlessEditingUndoRedoAndGuardedDeletion() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(true);
		RecordingPanelActions actions = new RecordingPanelActions();
		RecordingUiInteractions interactions = new RecordingUiInteractions();

		SwingUtilities.invokeAndWait(() -> {
			HistoryEditorPanel editor = new HistoryEditorPanel(
				fixture.getSessions(), fixture.getConfig(), actions, interactions);
			HistoryTableModel model = editor.getHistoryModel();
			int aliceLoot = findRow(model, "Alice", null);
			long originalAmount = model.getEventAt(aliceLoot).get().getAmount();

			model.setValueAt("125m", aliceLoot, HistoryTableModel.LOOT_COLUMN);
			assertTrue(editor.getUndoButton().isEnabled());
			assertEquals(125_000_000L, findEvent(model, "Alice", null).getAmount().longValue());

			editor.getUndoButton().doClick();
			assertEquals(originalAmount, findEvent(model, "Alice", null).getAmount().longValue());
			assertTrue(editor.getRedoButton().isEnabled());
			editor.getRedoButton().doClick();
			assertEquals(125_000_000L, findEvent(model, "Alice", null).getAmount().longValue());

			int joined = findRow(model, "Bob", SplitEvent.TYPE_JOINED);
			int beforeDelete = model.getRowCount();
			editor.requestDelete(joined);
			assertEquals(beforeDelete, model.getRowCount());
			assertEquals("Cannot remove a join period with assigned splits.", interactions.getLastMessage());
			assertFalse(editor.moveEvent(-1, 0));
		});
	}

	@Test
	public void confirmationControlsLinkedRosterDeletion() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(false);
		RecordingPanelActions actions = new RecordingPanelActions();
		RecordingUiInteractions interactions = new RecordingUiInteractions();

		SwingUtilities.invokeAndWait(() -> {
			HistoryEditorPanel editor = new HistoryEditorPanel(
				fixture.getSessions(), fixture.getConfig(), actions, interactions);
			HistoryTableModel model = editor.getHistoryModel();
			int joined = findRow(model, "Bob", SplitEvent.TYPE_JOINED);
			int beforeDelete = model.getRowCount();

			interactions.setConfirmResult(false);
			editor.requestDelete(joined);
			assertEquals(beforeDelete, model.getRowCount());

			interactions.setConfirmResult(true);
			editor.requestDelete(findRow(model, "Bob", SplitEvent.TYPE_JOINED));
			assertEquals(beforeDelete - 2, model.getRowCount());
		});
	}

	@Test
	public void exercisesEditorsRenderersShortcutsPointerAndLocalTransfersHeadlessly() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(false);
		RecordingPanelActions actions = new RecordingPanelActions();
		RecordingUiInteractions interactions = new RecordingUiInteractions();

		SwingUtilities.invokeAndWait(() -> {
			HistoryEditorPanel editor = new HistoryEditorPanel(
				fixture.getSessions(), fixture.getConfig(), actions, interactions);
			HistoryTableModel model = editor.getHistoryModel();
			JTable table = editor.getHistoryTable();
			int aliceLoot = findRow(model, "Alice", null);
			int joined = findRow(model, "Bob", SplitEvent.TYPE_JOINED);
			int left = findRow(model, "Bob", SplitEvent.TYPE_LEFT);
			assertTrue(aliceLoot >= 0);

			TableCellEditor playerEditor = table.getColumnModel()
				.getColumn(HistoryTableModel.PLAYER_COLUMN).getCellEditor();
			Component playerComponent = playerEditor.getTableCellEditorComponent(
				table, "Guest", true, aliceLoot, HistoryTableModel.PLAYER_COLUMN);
			assertTrue(playerComponent instanceof JComboBox);
			JComboBox<?> players = (JComboBox<?>) playerComponent;
			assertEquals("Guest", players.getSelectedItem());
			assertTrue(comboContains(players, "Alice"));
			assertTrue(comboContains(players, "Guest"));

			TableCellRenderer normalRenderer = table.getCellRenderer(aliceLoot, HistoryTableModel.PLAYER_COLUMN);
			Component normal = normalRenderer.getTableCellRendererComponent(
				table, "Alice", false, false, aliceLoot, HistoryTableModel.PLAYER_COLUMN);
			assertTrue(normal instanceof JLabel);
			Component selected = normalRenderer.getTableCellRendererComponent(
				table, "Alice", true, false, aliceLoot, HistoryTableModel.PLAYER_COLUMN);
			assertTrue(selected.isOpaque());

			model.setHoveredRow(aliceLoot);
			normalRenderer.getTableCellRendererComponent(
				table, "Alice", false, false, aliceLoot, HistoryTableModel.PLAYER_COLUMN);
			TableCellRenderer dragRenderer = table.getCellRenderer(aliceLoot, HistoryTableModel.DRAG_COLUMN);
			JLabel drag = (JLabel) dragRenderer.getTableCellRendererComponent(
				table, null, false, false, aliceLoot, HistoryTableModel.DRAG_COLUMN);
			assertEquals("Drag to reorder", drag.getToolTipText());

			TableCellRenderer deleteRenderer = table.getCellRenderer(aliceLoot, HistoryTableModel.DELETE_COLUMN);
			JButton delete = (JButton) deleteRenderer.getTableCellRendererComponent(
				table, null, false, false, aliceLoot, HistoryTableModel.DELETE_COLUMN);
			assertEquals("Remove entry", delete.getToolTipText());
			model.markPendingRosterDelete(joined, left);
			JButton pendingDelete = (JButton) deleteRenderer.getTableCellRendererComponent(
				table, null, false, false, joined, HistoryTableModel.DELETE_COLUMN);
			assertEquals("Confirm linked roster event removal", pendingDelete.getToolTipText());
			normalRenderer.getTableCellRendererComponent(
				table, "Bob", false, false, joined, HistoryTableModel.PLAYER_COLUMN);
			model.clearPendingRosterDelete();

			TransferHandler transferHandler = table.getTransferHandler();
			assertNotNull(transferHandler);
			assertEquals(TransferHandler.MOVE, transferHandler.getSourceActions(table));
			TransferHandler.TransferSupport stringSupport = new TransferHandler.TransferSupport(
				table, new StringSelection(String.valueOf(aliceLoot)));
			assertTrue(transferHandler.canImport(stringSupport));
			assertFalse(transferHandler.importData(stringSupport));
			TransferHandler.TransferSupport unsupported = new TransferHandler.TransferSupport(
				table, imageOnlyTransferable());
			assertFalse(transferHandler.canImport(unsupported));
			assertFalse(transferHandler.importData(unsupported));

			Clipboard clipboard = new Clipboard("history-editor-test");
			table.setRowSelectionInterval(aliceLoot, aliceLoot);
			transferHandler.exportToClipboard(table, clipboard, TransferHandler.MOVE);
			Transferable selectedRow = clipboard.getContents(null);
			assertEquals(String.valueOf(aliceLoot), transferText(selectedRow));
			assertEquals(1, selectedRow.getTransferDataFlavors().length);
			assertTrue(selectedRow.isDataFlavorSupported(DataFlavor.stringFlavor));
			assertFalse(selectedRow.isDataFlavorSupported(DataFlavor.imageFlavor));
			table.clearSelection();
			transferHandler.exportToClipboard(table, clipboard, TransferHandler.MOVE);
			assertEquals("-1", transferText(clipboard.getContents(null)));

			Action undo = table.getActionMap().get("history-undo");
			Action redo = table.getActionMap().get("history-redo");
			assertNotNull(undo);
			assertNotNull(redo);
			undo.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "undo"));
			redo.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "redo"));

			table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 1L, 0,
				5, aliceLoot * table.getRowHeight() + 5, 0, false));
			assertTrue(model.isHoveredRow(aliceLoot));
			table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_EXITED, 2L, 0,
				-1, -1, 0, false));
			assertFalse(model.isHoveredRow(aliceLoot));
			table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, 3L, 0,
				-1, -1, 1, false));

			int beforeDelete = model.getRowCount();
			TableCellEditor deleteEditor = table.getColumnModel()
				.getColumn(HistoryTableModel.DELETE_COLUMN).getCellEditor();
			JButton deleteEditorButton = (JButton) deleteEditor.getTableCellEditorComponent(
				table, null, true, aliceLoot, HistoryTableModel.DELETE_COLUMN);
			assertNotNull(deleteEditor.getCellEditorValue());
			deleteEditorButton.doClick();
			assertEquals(beforeDelete - 1, model.getRowCount());
			assertEquals("refreshAllView", actions.getLastAction());

			editor.refresh();
		});
	}

	@Test
	public void handlesLockedAndFailedStubbedEditorOperations() throws Exception
	{
		ManagerSession sessions = mock(ManagerSession.class);
		PluginConfig config = mock(PluginConfig.class);
		PanelActions actions = mock(PanelActions.class);
		RecordingUiInteractions interactions = new RecordingUiInteractions();
		SplitEvent joined = event("session", " ", SplitEvent.TYPE_JOINED);
		SplitEvent left = event("session", " ", SplitEvent.TYPE_LEFT);
		when(sessions.getAllEvents()).thenReturn(Arrays.asList(joined, left));
		when(sessions.isCurrentHistoryEditLocked()).thenReturn(false);

		SwingUtilities.invokeAndWait(() -> {
			HistoryEditorPanel editor = new HistoryEditorPanel(sessions, config, actions, interactions);
			assertFalse(editor.moveEvent(0, 1));
			when(sessions.getLastMoveEventFailureMessage()).thenReturn("Roster order is invalid.");
			assertFalse(editor.moveEvent(0, 1));
			assertEquals("Roster order is invalid.", interactions.getLastMessage());

			when(sessions.moveEvent(0, 2)).thenReturn(true);
			assertTrue(editor.moveEvent(0, 2));

			editor.requestDelete(-1);
			when(sessions.removeEventsAt(anyList())).thenReturn(false, true);
			when(sessions.getLastMoveEventFailureMessage()).thenReturn(null);
			editor.requestDelete(0);
			assertEquals("Cannot remove that history row.", interactions.getLastMessage());
			editor.requestDelete(0);

			when(sessions.removeEventAt(1)).thenReturn(false);
			when(sessions.getLastMoveEventFailureMessage()).thenReturn("Removal refused.");
			editor.requestDelete(1);
			assertEquals("Removal refused.", interactions.getLastMessage());

			editor.undo();
			editor.redo();
		});

		ManagerSession lockedSessions = mock(ManagerSession.class);
		when(lockedSessions.getAllEvents()).thenReturn(Collections.emptyList());
		when(lockedSessions.isCurrentHistoryEditLocked()).thenReturn(true);
		SwingUtilities.invokeAndWait(() -> {
			HistoryEditorPanel locked = new HistoryEditorPanel(
				lockedSessions, config, actions, interactions);
			JTable lockedTable = locked.getHistoryTable();
			assertNotNull(lockedTable.getTransferHandler());
			assertNull(lockedTable.getColumnModel().getColumn(HistoryTableModel.DELETE_COLUMN).getCellEditor());
			assertNull(lockedTable.getTableHeader().getToolTipText());
			assertEquals(Cursor.DEFAULT_CURSOR, lockedTable.getCursor().getType());
			assertFalse(locked.getUndoButton().isEnabled());
			assertFalse(locked.getRedoButton().isEnabled());
			lockedTable.dispatchEvent(new MouseEvent(lockedTable, MouseEvent.MOUSE_PRESSED, 4L, 0,
				0, 0, 1, false));
		});
	}

	private static SplitEvent event(String sessionId, String player, String type)
	{
		SplitEvent event = new SplitEvent(sessionId, player, 0L, Instant.EPOCH);
		event.setType(type);
		return event;
	}

	private static boolean comboContains(JComboBox<?> comboBox, String value)
	{
		for (int index = 0; index < comboBox.getItemCount(); index++)
		{
			if (value.equals(comboBox.getItemAt(index)))
			{
				return true;
			}
		}
		return false;
	}

	private static Transferable imageOnlyTransferable()
	{
		return new Transferable()
		{
			@Override
			public DataFlavor[] getTransferDataFlavors()
			{
				return new DataFlavor[]{DataFlavor.imageFlavor};
			}

			@Override
			public boolean isDataFlavorSupported(DataFlavor flavor)
			{
				return DataFlavor.imageFlavor.equals(flavor);
			}

			@Override
			public Object getTransferData(DataFlavor flavor)
			{
				return null;
			}
		};
	}

	private static String transferText(Transferable transferable)
	{
		try
		{
			return (String) transferable.getTransferData(DataFlavor.stringFlavor);
		}
		catch (Exception exception)
		{
			throw new AssertionError(exception);
		}
	}

	private static SplitEvent findEvent(HistoryTableModel model, String player, String type)
	{
		int row = findRow(model, player, type);
		return row < 0 ? null : model.getEventAt(row).orElse(null);
	}

	private static int findRow(HistoryTableModel model, String player, String type)
	{
		for (int row = 0; row < model.getRowCount(); row++)
		{
			SplitEvent event = model.getEventAt(row).orElse(null);
			if (event != null && player.equals(event.getPlayer())
				&& (type == null ? event.isLootEvent() : type.equalsIgnoreCase(event.getType())))
			{
				return row;
			}
		}
		return -1;
	}
}
