/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.splitmanager.ManagerSession;
import com.splitmanager.PluginConfig;
import com.splitmanager.models.Session;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.sessions.SplitCalculator;
import com.splitmanager.utils.Formats;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.table.AbstractTableModel;
import lombok.extern.slf4j.Slf4j;

/**
 * Headless-safe model for editing the event timeline of a loaded history session.
 */
@Slf4j
final class HistoryTableModel extends AbstractTableModel
{
	static final int DRAG_COLUMN = 0;
	static final int PLAYER_COLUMN = 2;
	static final int LOOT_COLUMN = 3;
	static final int TAX_COLUMN = 4;
	static final int AFTER_TAX_COLUMN = 5;
	static final int TYPE_COLUMN = 6;
	static final int DELETE_COLUMN = 7;

	private final String[] columns = {"Move", "Time", "Player", "Loot", "Tax", "After Tax", "Type", "X"};
	private final ManagerSession sessionManager;
	private final PluginConfig config;
	private final Icon dragIcon;
	private final Icon deleteIcon;
	private final Consumer<String> messageSink;
	private final Runnable mutationListener;
	private List<SplitEvent> events = new ArrayList<>();
	private int pendingRosterDeleteRow = -1;
	private int pendingRosterDeleteLinkedRow = -1;
	private SplitEvent pendingRosterDeleteEvent;
	private SplitEvent pendingRosterDeleteLinkedEvent;
	private int hoveredRow = -1;

	HistoryTableModel(ManagerSession sessionManager, PluginConfig config, Icon dragIcon, Icon deleteIcon,
	                  Consumer<String> messageSink, Runnable mutationListener)
	{
		this.sessionManager = sessionManager;
		this.config = config;
		this.dragIcon = dragIcon;
		this.deleteIcon = deleteIcon;
		this.messageSink = messageSink;
		this.mutationListener = mutationListener;
		refresh();
	}

	void refresh()
	{
		events = new ArrayList<>(sessionManager.getAllEvents());
		clearPendingRosterDelete();
		fireTableDataChanged();
	}

	boolean isPendingRosterDelete(int rowIndex)
	{
		if (rowIndex < 0 || rowIndex >= events.size())
		{
			return false;
		}
		SplitEvent event = events.get(rowIndex);
		return event == pendingRosterDeleteEvent
			|| event == pendingRosterDeleteLinkedEvent
			|| rowIndex == pendingRosterDeleteRow
			|| rowIndex == pendingRosterDeleteLinkedRow;
	}

	boolean isHoveredRow(int rowIndex)
	{
		return rowIndex >= 0 && rowIndex == hoveredRow;
	}

	void setHoveredRow(int hoveredRow)
	{
		if (this.hoveredRow == hoveredRow)
		{
			return;
		}
		int previous = this.hoveredRow;
		this.hoveredRow = hoveredRow;
		if (previous >= 0 && previous < getRowCount())
		{
			fireTableRowsUpdated(previous, previous);
		}
		if (hoveredRow >= 0 && hoveredRow < getRowCount())
		{
			fireTableRowsUpdated(hoveredRow, hoveredRow);
		}
	}

	void markPendingRosterDelete(int rowIndex, int linkedRowIndex)
	{
		pendingRosterDeleteRow = rowIndex;
		pendingRosterDeleteLinkedRow = linkedRowIndex;
		pendingRosterDeleteEvent = getEventAt(rowIndex).orElse(null);
		pendingRosterDeleteLinkedEvent = getEventAt(linkedRowIndex).orElse(null);
		fireTableRowsUpdated(Math.min(rowIndex, linkedRowIndex), Math.max(rowIndex, linkedRowIndex));
	}

	void clearPendingRosterDelete()
	{
		pendingRosterDeleteRow = -1;
		pendingRosterDeleteLinkedRow = -1;
		pendingRosterDeleteEvent = null;
		pendingRosterDeleteLinkedEvent = null;
	}

	List<Integer> getPendingRosterDeleteRows()
	{
		List<Integer> rows = new ArrayList<>();
		addPendingEventRow(rows, pendingRosterDeleteEvent);
		addPendingEventRow(rows, pendingRosterDeleteLinkedEvent);
		if (rows.isEmpty())
		{
			if (pendingRosterDeleteRow >= 0)
			{
				rows.add(pendingRosterDeleteRow);
			}
			if (pendingRosterDeleteLinkedRow >= 0 && pendingRosterDeleteLinkedRow != pendingRosterDeleteRow)
			{
				rows.add(pendingRosterDeleteLinkedRow);
			}
		}
		return rows;
	}

	private void addPendingEventRow(List<Integer> rows, SplitEvent event)
	{
		if (event == null)
		{
			return;
		}
		for (int i = 0; i < events.size(); i++)
		{
			if (events.get(i) == event && !rows.contains(i))
			{
				rows.add(i);
				return;
			}
		}
	}

	int findLinkedRosterEventRow(int rowIndex)
	{
		SplitEvent selected = getEventAt(rowIndex).orElse(null);
		if (selected == null || !selected.isRosterEvent())
		{
			return -1;
		}
		if (SplitEvent.TYPE_JOINED.equalsIgnoreCase(selected.getType()))
		{
			return findNextRosterEvent(rowIndex, selected.getPlayer(), SplitEvent.TYPE_LEFT);
		}
		if (SplitEvent.TYPE_LEFT.equalsIgnoreCase(selected.getType()))
		{
			return findNextRosterEvent(rowIndex, selected.getPlayer(), SplitEvent.TYPE_JOINED);
		}
		return -1;
	}

	boolean hasAssignedSplitsInRosterPeriod(int rowIndex)
	{
		SplitEvent selected = getEventAt(rowIndex).orElse(null);
		if (selected == null || !SplitEvent.TYPE_JOINED.equalsIgnoreCase(selected.getType()))
		{
			return false;
		}
		String player = selected.getPlayer();
		for (int i = rowIndex + 1; i < events.size(); i++)
		{
			SplitEvent event = events.get(i);
			if (event != null && event.isRosterEvent() && samePlayer(player, event.getPlayer()))
			{
				return false;
			}
			if (event != null && event.isLootEvent() && samePlayer(player, event.getPlayer()))
			{
				return true;
			}
		}
		return false;
	}

	private int findNextRosterEvent(int rowIndex, String player, String expectedType)
	{
		for (int i = rowIndex + 1; i < events.size(); i++)
		{
			SplitEvent candidate = events.get(i);
			if (candidate != null && samePlayer(player, candidate.getPlayer()) && candidate.isRosterEvent())
			{
				return expectedType.equalsIgnoreCase(candidate.getType()) ? i : -1;
			}
		}
		return -1;
	}

	private boolean samePlayer(String first, String second)
	{
		return first != null && first.equalsIgnoreCase(second);
	}

	Optional<SplitEvent> getEventAt(int rowIndex)
	{
		return rowIndex >= 0 && rowIndex < events.size()
			? Optional.ofNullable(events.get(rowIndex))
			: Optional.empty();
	}

	@Override
	public int getRowCount()
	{
		return events.size();
	}

	@Override
	public int getColumnCount()
	{
		return columns.length;
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex)
	{
		SplitEvent event = events.get(rowIndex);
		switch (columnIndex)
		{
			case DRAG_COLUMN:
				return dragIcon;
			case 1:
				return event.getAt() == null ? "" : Formats.getLocalTime().format(
					ZonedDateTime.ofInstant(event.getAt(), ZoneId.systemDefault()));
			case PLAYER_COLUMN:
				return event.getPlayer();
			case LOOT_COLUMN:
				return event.isRosterEvent() ? "" : formatEventAmount(event.getAmount());
			case TAX_COLUMN:
				return formatTaxAmount(event);
			case AFTER_TAX_COLUMN:
				return formatAfterTaxAmount(event);
			case TYPE_COLUMN:
				return event.getType() == null ? "LOOT" : event.getType();
			case DELETE_COLUMN:
				return deleteIcon;
			default:
				return null;
		}
	}

	@Override
	public String getColumnName(int column)
	{
		return column >= 0 && column < columns.length ? columns[column] : "";
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex)
	{
		if (sessionManager.isCurrentHistoryEditLocked())
		{
			return false;
		}
		if (columnIndex == DELETE_COLUMN)
		{
			return true;
		}
		SplitEvent event = getEventAt(rowIndex).orElse(null);
		return event != null && event.isLootEvent()
			&& (columnIndex == PLAYER_COLUMN || columnIndex == LOOT_COLUMN);
	}

	@Override
	public void setValueAt(Object value, int rowIndex, int columnIndex)
	{
		if (!sessionManager.prepareHistoryMutation())
		{
			return;
		}
		if (columnIndex == PLAYER_COLUMN)
		{
			if (!sessionManager.updateHistoryLootEventAt(rowIndex, value == null ? "" : value.toString(), null))
			{
				showMutationFailure("Cannot edit that history row.");
				return;
			}
		}
		else if (columnIndex == LOOT_COLUMN)
		{
			try
			{
				Long amount = Formats.OsrsAmountFormatter.stringAmountToLongAmount(
					value == null ? "" : value.toString(), config);
				if (!sessionManager.updateHistoryLootEventAt(rowIndex, null, amount))
				{
					showMutationFailure("Cannot edit that history row.");
					return;
				}
			}
			catch (Exception e)
			{
				log.warn("Invalid history loot amount '{}' at row {}", value, rowIndex, e);
				messageSink.accept("Invalid loot amount.");
				return;
			}
		}
		else
		{
			return;
		}
		mutationListener.run();
		fireTableCellUpdated(rowIndex, columnIndex);
	}

	private void showMutationFailure(String fallback)
	{
		String message = sessionManager.getLastMoveEventFailureMessage();
		messageSink.accept(message == null || message.isBlank() ? fallback : message);
	}

	private String formatEventAmount(Long amount)
	{
		return amount == null ? "" : formatAmount(amount);
	}

	private String formatTaxAmount(SplitEvent event)
	{
		long geTax = geTaxAmount(event);
		return geTax > 0L ? formatAmount(geTax) : "";
	}

	private String formatAfterTaxAmount(SplitEvent event)
	{
		if (event == null || !event.isLootEvent() || event.getAmount() == null)
		{
			return "";
		}
		return formatAmount(Math.max(event.getAmount() - geTaxAmount(event), 0L));
	}

	private long geTaxAmount(SplitEvent event)
	{
		if (event == null || !event.isLootEvent() || event.getAmount() == null)
		{
			return 0L;
		}
		Session session = sessionForEvent(event);
		return SplitCalculator.computeGeTax(
			event.getAmount(),
			sessionManager.getGeTaxSettingsFor(sessionManager.getSettlementConfigSnapshotFor(session)));
	}

	private Session sessionForEvent(SplitEvent event)
	{
		if (event.getSessionId() == null)
		{
			return null;
		}
		for (Session session : sessionManager.getAllSessionsNewestFirst())
		{
			if (event.getSessionId().equals(session.getId()))
			{
				return session;
			}
		}
		return null;
	}

	private String formatAmount(long amount)
	{
		long absolute = Math.abs(amount);
		if (absolute >= 1_000_000_000L)
		{
			return Formats.OsrsAmountFormatter.toSuffixString(amount, 'b');
		}
		if (absolute >= 1_000_000L)
		{
			return Formats.OsrsAmountFormatter.toSuffixString(amount, 'm');
		}
		return Formats.OsrsAmountFormatter.toSuffixString(amount, 'k');
	}
}
