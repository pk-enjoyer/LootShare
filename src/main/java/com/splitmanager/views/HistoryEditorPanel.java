/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.splitmanager.ManagerSession;
import com.splitmanager.PluginConfig;
import com.splitmanager.controllers.PanelActions;
import com.splitmanager.models.Session;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.ui.UiInteractionGateway;
import com.splitmanager.views.components.PanelTheme;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.swing.AbstractAction;
import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

/**
 * Standalone, headless-safe history editor used by the popout dashboard.
 */
final class HistoryEditorPanel extends JPanel
{
	private static final Color TABLE_BACKGROUND = PanelTheme.DARKER_GRAY;
	private static final Color ROW_BACKGROUND = PanelTheme.DARK_GRAY;
	private static final Color HOVER_BACKGROUND = new Color(58, 52, 43);
	private static final Color SELECTED_BACKGROUND = new Color(72, 61, 44);
	private static final Border EDIT_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createLineBorder(PanelTheme.BRAND_ORANGE, 2),
		BorderFactory.createEmptyBorder(3, 3, 3, 3));
	private static final Border LOCKED_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createLineBorder(PanelTheme.BORDER, 2),
		BorderFactory.createEmptyBorder(3, 3, 3, 3));
	private static final ImageIcon DELETE_ICON = loadRasterIcon("/com/splitmanager/icons/trash-solid-full.png", 16);
	private static final FlatSVGIcon DRAG_HANDLE_ICON = loadSvgIcon("/com/splitmanager/icons/drag-handle.svg");
	private static final FlatSVGIcon UNDO_ICON = loadSvgIcon("/com/splitmanager/icons/undo.svg");
	private static final FlatSVGIcon REDO_ICON = loadSvgIcon("/com/splitmanager/icons/redo.svg");

	private final ManagerSession sessionManager;
	private final PanelActions actions;
	private final UiInteractionGateway interactions;
	private final HistoryTableModel model;
	private final JTable table;
	private final JButton undoButton;
	private final JButton redoButton;

	HistoryEditorPanel(ManagerSession sessionManager, PluginConfig config, PanelActions actions,
	                   UiInteractionGateway interactions)
	{
		super(new BorderLayout(0, 8));
		this.sessionManager = sessionManager;
		this.actions = actions;
		this.interactions = interactions;
		setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

		model = new HistoryTableModel(
			sessionManager,
			config,
			DRAG_HANDLE_ICON,
			DELETE_ICON,
			this::showMessage,
			this::refreshAfterMutation);
		table = new JTable(model);
		boolean editable = !sessionManager.isCurrentHistoryEditLocked();
		configureTable(editable);

		JPanel titlePanel = new JPanel();
		titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.X_AXIS));
		JLabel title = new JLabel("Edit Session History");
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize2D() + 2.0f));
		titlePanel.add(title);
		titlePanel.add(Box.createHorizontalStrut(8));
		undoButton = createHeaderButton(UNDO_ICON, "Undo (Ctrl+Z)");
		redoButton = createHeaderButton(REDO_ICON, "Redo (Ctrl+Y / Ctrl+Shift+Z)");
		undoButton.addActionListener(e -> undo());
		redoButton.addActionListener(e -> redo());
		titlePanel.add(undoButton);
		titlePanel.add(Box.createHorizontalStrut(4));
		titlePanel.add(redoButton);

		JPanel header = new JPanel(new BorderLayout());
		header.add(titlePanel, BorderLayout.WEST);
		if (!editable)
		{
			JLabel locked = new JLabel("Read-only legacy history");
			locked.setForeground(PanelTheme.BRAND_ORANGE);
			header.add(locked, BorderLayout.EAST);
		}
		add(header, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setBorder(editable ? EDIT_BORDER : LOCKED_BORDER);
		scrollPane.getViewport().setBackground(TABLE_BACKGROUND);
		add(scrollPane, BorderLayout.CENTER);

		JLabel hint = new JLabel("Click trash to delete. Linked joins ask for confirmation. Drag to reorder.",
			SwingConstants.CENTER);
		hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() + 1.0f));
		add(hint, BorderLayout.SOUTH);
		refreshUndoRedoButtons();
	}

	private static FlatSVGIcon loadSvgIcon(String path)
	{
		java.net.URL resource = HistoryEditorPanel.class.getResource(path);
		return resource == null ? null : new FlatSVGIcon(resource).derive(16, 16);
	}

	private static ImageIcon loadRasterIcon(String path, int size)
	{
		java.net.URL resource = HistoryEditorPanel.class.getResource(path);
		if (resource == null)
		{
			return new ImageIcon();
		}
		ImageIcon source = new ImageIcon(resource);
		return new ImageIcon(source.getImage().getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH));
	}

	private static JButton createHeaderButton(FlatSVGIcon icon, String tooltip)
	{
		JButton button = new JButton(icon);
		Dimension size = new Dimension(24, 24);
		button.setPreferredSize(size);
		button.setMinimumSize(size);
		button.setMaximumSize(size);
		button.setToolTipText(tooltip);
		button.setFocusable(false);
		button.setBorder(BorderFactory.createEmptyBorder());
		button.setMargin(new java.awt.Insets(0, 0, 0, 0));
		PanelTheme.styleIconButton(button);
		return button;
	}

	private static JButton createDeleteButton()
	{
		JButton button = new JButton(DELETE_ICON);
		button.setToolTipText("Remove entry");
		button.setOpaque(true);
		button.setRolloverEnabled(true);
		button.setFocusable(false);
		PanelTheme.styleIconButton(button);
		button.setBorder(BorderFactory.createLineBorder(PanelTheme.DARK_GRAY));
		return button;
	}

	private void configureTable(boolean editable)
	{
		table.setRowHeight(30);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0, 1));
		table.setFillsViewportHeight(true);
		table.setBackground(TABLE_BACKGROUND);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setDragEnabled(editable && !java.awt.GraphicsEnvironment.isHeadless());
		table.setCursor(editable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
		table.getTableHeader().setReorderingAllowed(false);
		table.getTableHeader().setToolTipText(editable
			? "Loot is before GE tax. Tax is deducted from split math. After Tax is the amount actually split."
			: null);

		HistoryCellRenderer renderer = new HistoryCellRenderer();
		table.setDefaultRenderer(Object.class, renderer);
		table.setDefaultRenderer(Long.class, renderer);
		table.getColumnModel().getColumn(HistoryTableModel.DRAG_COLUMN).setCellRenderer(new DragHandleRenderer());
		registerUndoRedoShortcuts();

		table.getColumnModel().getColumn(HistoryTableModel.DRAG_COLUMN).setMinWidth(38);
		table.getColumnModel().getColumn(HistoryTableModel.DRAG_COLUMN).setMaxWidth(46);
		table.getColumnModel().getColumn(HistoryTableModel.LOOT_COLUMN).setPreferredWidth(90);
		table.getColumnModel().getColumn(HistoryTableModel.TAX_COLUMN).setPreferredWidth(70);
		table.getColumnModel().getColumn(HistoryTableModel.AFTER_TAX_COLUMN).setPreferredWidth(90);
		table.getColumnModel().getColumn(HistoryTableModel.TYPE_COLUMN).setPreferredWidth(62);
		table.getColumnModel().getColumn(HistoryTableModel.DELETE_COLUMN).setMinWidth(34);
		table.getColumnModel().getColumn(HistoryTableModel.DELETE_COLUMN).setMaxWidth(42);
		table.getColumnModel().getColumn(HistoryTableModel.DELETE_COLUMN).setCellRenderer(new DeleteButtonRenderer());

		if (editable)
		{
			table.getColumnModel().getColumn(HistoryTableModel.PLAYER_COLUMN).setCellEditor(new HistoryPlayerCellEditor());
			table.getColumnModel().getColumn(HistoryTableModel.DELETE_COLUMN).setCellEditor(new DeleteButtonEditor());
			table.setDropMode(javax.swing.DropMode.INSERT_ROWS);
			table.setTransferHandler(new HistoryTransferHandler());
		}

		table.addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent event)
			{
				setHoveredRow(event.getPoint());
			}
		});
		table.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent event)
			{
				if (!editable || table.getTransferHandler() == null)
				{
					return;
				}
				int row = table.rowAtPoint(event.getPoint());
				int column = table.columnAtPoint(event.getPoint());
				if (row < 0 || column < 0
					|| table.convertColumnIndexToModel(column) != HistoryTableModel.DRAG_COLUMN)
				{
					return;
				}
				table.getSelectionModel().setSelectionInterval(row, row);
				table.getTransferHandler().exportAsDrag(table, event, TransferHandler.MOVE);
				event.consume();
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				model.setHoveredRow(-1);
			}
		});
	}

	private void registerUndoRedoShortcuts()
	{
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "history-undo");
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), "history-redo");
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "history-redo");
		table.getActionMap().put("history-undo", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent event)
			{
				undo();
			}
		});
		table.getActionMap().put("history-redo", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent event)
			{
				redo();
			}
		});
	}

	void refresh()
	{
		model.refresh();
		refreshUndoRedoButtons();
	}

	void undo()
	{
		stopCellEditing();
		if (sessionManager.undoHistoryEdit())
		{
			refreshAfterMutation();
		}
		else
		{
			refreshUndoRedoButtons();
		}
	}

	void redo()
	{
		stopCellEditing();
		if (sessionManager.redoHistoryEdit())
		{
			refreshAfterMutation();
		}
		else
		{
			refreshUndoRedoButtons();
		}
	}

	boolean moveEvent(int fromIndex, int toIndex)
	{
		if (!sessionManager.moveEvent(fromIndex, toIndex))
		{
			String message = sessionManager.getLastMoveEventFailureMessage();
			if (message != null && !message.isBlank())
			{
				showMessage(message);
			}
			return false;
		}
		refreshAfterMutation();
		return true;
	}

	void requestDelete(int row)
	{
		if (row < 0 || row >= model.getRowCount())
		{
			return;
		}
		if (model.isPendingRosterDelete(row))
		{
			removeRows(model.getPendingRosterDeleteRows());
			return;
		}
		SplitEvent selected = model.getEventAt(row).orElse(null);
		if (selected != null && selected.isRosterEvent())
		{
			if (SplitEvent.TYPE_JOINED.equalsIgnoreCase(selected.getType())
				&& model.hasAssignedSplitsInRosterPeriod(row))
			{
				model.clearPendingRosterDelete();
				showMessage("Cannot remove a join period with assigned splits.");
				return;
			}
			int linkedRow = model.findLinkedRosterEventRow(row);
			if (linkedRow >= 0)
			{
				model.markPendingRosterDelete(row, linkedRow);
				highlightLinkedRows(row, linkedRow);
				String player = selected.getPlayer() == null || selected.getPlayer().trim().isEmpty()
					? "this player" : selected.getPlayer().trim();
				if (interactions.confirm(this, "Remove player period",
					"Remove the highlighted join/leave events for " + player + "?"))
				{
					removeRows(Arrays.asList(row, linkedRow));
				}
				else
				{
					model.clearPendingRosterDelete();
					table.repaint();
				}
				return;
			}
		}

		model.clearPendingRosterDelete();
		if (sessionManager.removeEventAt(row))
		{
			refreshAfterMutation();
		}
		else
		{
			showMutationFailure("Cannot remove that history row.");
		}
	}

	private void removeRows(List<Integer> rows)
	{
		if (sessionManager.removeEventsAt(rows))
		{
			refreshAfterMutation();
		}
		else
		{
			showMutationFailure("Cannot remove that history row.");
		}
	}

	private void refreshAfterMutation()
	{
		model.refresh();
		actions.refreshAllView();
		refreshUndoRedoButtons();
	}

	private void refreshUndoRedoButtons()
	{
		boolean unlocked = !sessionManager.isCurrentHistoryEditLocked();
		undoButton.setEnabled(unlocked && sessionManager.canUndoHistoryEdit());
		redoButton.setEnabled(unlocked && sessionManager.canRedoHistoryEdit());
	}

	private void stopCellEditing()
	{
		if (table.isEditing() && table.getCellEditor() != null)
		{
			table.getCellEditor().stopCellEditing();
		}
	}

	private void setHoveredRow(Point point)
	{
		int row = table.rowAtPoint(point);
		model.setHoveredRow(row < 0 ? -1 : table.convertRowIndexToModel(row));
	}

	private void highlightLinkedRows(int first, int second)
	{
		int firstView = table.convertRowIndexToView(first);
		int secondView = table.convertRowIndexToView(second);
		table.clearSelection();
		if (firstView >= 0)
		{
			table.addRowSelectionInterval(firstView, firstView);
		}
		if (secondView >= 0)
		{
			table.addRowSelectionInterval(secondView, secondView);
			table.scrollRectToVisible(table.getCellRect(secondView, 0, true));
		}
		table.repaint();
	}

	private void showMutationFailure(String fallback)
	{
		String message = sessionManager.getLastMoveEventFailureMessage();
		showMessage(message == null || message.isBlank() ? fallback : message);
	}

	private void showMessage(String message)
	{
		interactions.showMessage(this, message);
	}

	HistoryTableModel getHistoryModel()
	{
		return model;
	}

	JTable getHistoryTable()
	{
		return table;
	}

	JButton getUndoButton()
	{
		return undoButton;
	}

	JButton getRedoButton()
	{
		return redoButton;
	}

	private Color rowBackground(int row, boolean selected)
	{
		if (model.isPendingRosterDelete(row))
		{
			return new Color(74, 49, 34);
		}
		if (selected)
		{
			return SELECTED_BACKGROUND;
		}
		return model.isHoveredRow(row) ? HOVER_BACKGROUND : ROW_BACKGROUND;
	}

	private Border cellBorder(int row)
	{
		Color color = model.isHoveredRow(row) || model.isPendingRosterDelete(row)
			? PanelTheme.BRAND_ORANGE : PanelTheme.BORDER;
		return BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 1, 0, color),
			BorderFactory.createEmptyBorder(0, 8, 0, 8));
	}

	private class HistoryPlayerCellEditor extends DefaultCellEditor
	{
		private final JComboBox<String> playerCombo;

		HistoryPlayerCellEditor()
		{
			this(new JComboBox<>());
		}

		private HistoryPlayerCellEditor(JComboBox<String> playerCombo)
		{
			super(playerCombo);
			this.playerCombo = playerCombo;
		}

		@Override
		public Component getTableCellEditorComponent(JTable currentTable, Object value, boolean selected,
		                                             int row, int column)
		{
			Set<String> players = new LinkedHashSet<>();
			int modelRow = currentTable.convertRowIndexToModel(row);
			SplitEvent event = model.getEventAt(modelRow).orElse(null);
			if (event != null && event.getSessionId() != null)
			{
				Session session = sessionManager.getAllSessionsNewestFirst().stream()
					.filter(candidate -> event.getSessionId().equals(candidate.getId()))
					.findFirst().orElse(null);
				if (session != null)
				{
					players.addAll(session.getPlayers());
				}
			}
			String current = value == null ? "" : value.toString();
			if (!current.isEmpty() && players.stream().noneMatch(player -> player.equalsIgnoreCase(current)))
			{
				players.add(current);
			}
			playerCombo.setModel(new DefaultComboBoxModel<>(players.toArray(new String[0])));
			playerCombo.setSelectedItem(current);
			return super.getTableCellEditorComponent(currentTable, value, selected, row, column);
		}
	}

	private class HistoryCellRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable currentTable, Object value, boolean selected,
		                                               boolean focused, int row, int column)
		{
			JLabel label = (JLabel) super.getTableCellRendererComponent(
				currentTable, value, selected, false, row, column);
			int modelRow = currentTable.convertRowIndexToModel(row);
			label.setBackground(rowBackground(modelRow, selected));
			label.setForeground(model.isPendingRosterDelete(modelRow) ? PanelTheme.BRAND_ORANGE : Color.WHITE);
			label.setBorder(cellBorder(modelRow));
			return label;
		}
	}

	private class DragHandleRenderer extends HistoryCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable currentTable, Object value, boolean selected,
		                                               boolean focused, int row, int column)
		{
			JLabel label = (JLabel) super.getTableCellRendererComponent(
				currentTable, value, selected, focused, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			label.setIcon(DRAG_HANDLE_ICON);
			label.setText(null);
			label.setToolTipText("Drag to reorder");
			return label;
		}
	}

	private class DeleteButtonRenderer implements TableCellRenderer
	{
		private final JButton button = createDeleteButton();

		@Override
		public Component getTableCellRendererComponent(JTable currentTable, Object value, boolean selected,
		                                               boolean focused, int row, int column)
		{
			int modelRow = currentTable.convertRowIndexToModel(row);
			button.setBackground(rowBackground(modelRow, selected));
			button.setBorder(cellBorder(modelRow));
			button.setToolTipText(model.isPendingRosterDelete(modelRow)
				? "Confirm linked roster event removal" : "Remove entry");
			return button;
		}
	}

	private class DeleteButtonEditor extends AbstractCellEditor implements TableCellEditor
	{
		private final JButton button = createDeleteButton();
		private int row = -1;

		DeleteButtonEditor()
		{
			button.addActionListener(event -> {
				int rowToDelete = row;
				fireEditingStopped();
				row = -1;
				requestDelete(rowToDelete);
			});
		}

		@Override
		public Object getCellEditorValue()
		{
			return DELETE_ICON;
		}

		@Override
		public Component getTableCellEditorComponent(JTable currentTable, Object value, boolean selected,
		                                             int viewRow, int column)
		{
			row = currentTable.convertRowIndexToModel(viewRow);
			button.setBackground(rowBackground(row, selected));
			button.setBorder(cellBorder(row));
			return button;
		}
	}

	private class HistoryTransferHandler extends TransferHandler
	{
		@Override
		public boolean importData(TransferSupport support)
		{
			if (!canImport(support))
			{
				return false;
			}
			try
			{
				int from = Integer.parseInt((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
				JTable.DropLocation location = (JTable.DropLocation) support.getDropLocation();
				JTable currentTable = (JTable) support.getComponent();
				int dropRow = location.getRow();
				int to = dropRow >= currentTable.getRowCount()
					? model.getRowCount() : currentTable.convertRowIndexToModel(dropRow);
				return from >= 0 && (from == to || moveEvent(from, to));
			}
			catch (Exception exception)
			{
				return false;
			}
		}

		@Override
		public boolean canImport(TransferSupport support)
		{
			return support.isDataFlavorSupported(DataFlavor.stringFlavor);
		}

		@Override
		public int getSourceActions(JComponent component)
		{
			return MOVE;
		}

		@Override
		protected Transferable createTransferable(JComponent component)
		{
			JTable currentTable = (JTable) component;
			int selected = currentTable.getSelectedRow();
			int index = selected < 0 ? -1 : currentTable.convertRowIndexToModel(selected);
			return new Transferable()
			{
				@Override
				public DataFlavor[] getTransferDataFlavors()
				{
					return new DataFlavor[]{DataFlavor.stringFlavor};
				}

				@Override
				public boolean isDataFlavorSupported(DataFlavor flavor)
				{
					return DataFlavor.stringFlavor.equals(flavor);
				}

				@Override
				@Nonnull
				public Object getTransferData(DataFlavor flavor)
				{
					return String.valueOf(index);
				}
			};
		}
	}
}
