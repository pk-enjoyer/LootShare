/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import com.splitmanager.ManagerSession;
import com.splitmanager.PluginConfig;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.models.SettlementConfigSnapshot;
import com.splitmanager.sessions.SplitCalculator;
import com.splitmanager.testing.UiScenarioFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.Test;

public class HistoryTableModelTest
{
	@Test
	public void displaysEditsAndLinksHistoryRowsWithoutDesktopUi() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(true);
		List<String> messages = new ArrayList<>();
		AtomicInteger mutations = new AtomicInteger();

		SwingUtilities.invokeAndWait(() -> {
			HistoryTableModel model = new HistoryTableModel(
				fixture.getSessions(), fixture.getConfig(), null, null, messages::add, mutations::incrementAndGet);

			assertEquals(8, model.getColumnCount());
			assertEquals("After Tax", model.getColumnName(HistoryTableModel.AFTER_TAX_COLUMN));
			assertEquals("", model.getColumnName(99));

			int aliceLoot = findRow(model, "Alice", null);
			int joined = findRow(model, "Bob", SplitEvent.TYPE_JOINED);
			int left = findRow(model, "Bob", SplitEvent.TYPE_LEFT);
			assertTrue(aliceLoot >= 0);
			assertTrue(joined >= 0);
			assertTrue(left > joined);
			assertEquals("100M", model.getValueAt(aliceLoot, HistoryTableModel.LOOT_COLUMN));
			assertEquals("2M", model.getValueAt(aliceLoot, HistoryTableModel.TAX_COLUMN));
			assertEquals("98M", model.getValueAt(aliceLoot, HistoryTableModel.AFTER_TAX_COLUMN));
			assertNull(model.getValueAt(aliceLoot, 99));
			assertEquals("Alice", model.getValueAt(aliceLoot, HistoryTableModel.PLAYER_COLUMN));
			assertEquals("LOOT", model.getValueAt(aliceLoot, HistoryTableModel.TYPE_COLUMN));
			assertNull(model.getValueAt(aliceLoot, HistoryTableModel.DRAG_COLUMN));
			assertNull(model.getValueAt(aliceLoot, HistoryTableModel.DELETE_COLUMN));
			assertTrue(model.isCellEditable(aliceLoot, HistoryTableModel.LOOT_COLUMN));
			assertFalse(model.isCellEditable(joined, HistoryTableModel.LOOT_COLUMN));
			assertEquals(left, model.findLinkedRosterEventRow(joined));
			assertEquals(-1, model.findLinkedRosterEventRow(aliceLoot));
			assertEquals(-1, model.findLinkedRosterEventRow(-1));
			assertTrue(model.hasAssignedSplitsInRosterPeriod(joined));
			assertFalse(model.hasAssignedSplitsInRosterPeriod(aliceLoot));
			assertFalse(model.getEventAt(-1).isPresent());
			assertFalse(model.isPendingRosterDelete(-1));

			model.setHoveredRow(aliceLoot);
			assertTrue(model.isHoveredRow(aliceLoot));
			model.setHoveredRow(aliceLoot);
			model.setHoveredRow(-1);
			assertFalse(model.isHoveredRow(aliceLoot));

			model.setValueAt("120m", aliceLoot, HistoryTableModel.LOOT_COLUMN);
			assertEquals(120_000_000L, model.getEventAt(aliceLoot).get().getAmount().longValue());
			assertEquals(1, mutations.get());

			model.setValueAt("not-coins", aliceLoot, HistoryTableModel.LOOT_COLUMN);
			assertEquals(120_000_000L, model.getEventAt(aliceLoot).get().getAmount().longValue());
			assertEquals("Invalid loot amount.", messages.get(messages.size() - 1));

			model.markPendingRosterDelete(joined, left);
			assertTrue(model.isPendingRosterDelete(joined));
			assertEquals(2, model.getPendingRosterDeleteRows().size());
			model.clearPendingRosterDelete();
			assertFalse(model.isPendingRosterDelete(joined));
			model.markPendingRosterDelete(50, 51);
			assertEquals(Arrays.asList(50, 51), model.getPendingRosterDeleteRows());
		});
	}

	@Test
	public void coversFormattingLinkingLockingAndEditOutcomesWithStubbedSessionBoundary() throws Exception
	{
		ManagerSession sessions = mock(ManagerSession.class);
		PluginConfig config = mock(PluginConfig.class);
		List<String> messages = new ArrayList<>();
		AtomicInteger mutations = new AtomicInteger();

		SplitEvent joined = event("session", "Bob", 0L, SplitEvent.TYPE_JOINED, Instant.EPOCH);
		SplitEvent left = event("session", "Bob", 0L, SplitEvent.TYPE_LEFT, Instant.EPOCH.plusSeconds(1));
		SplitEvent secondJoin = event("session", "Bob", 0L, SplitEvent.TYPE_JOINED, null);
		SplitEvent duplicateJoin = event("session", "Bob", 0L, SplitEvent.TYPE_JOINED, null);
		SplitEvent largeLoot = event(null, "Alice", 2_000_000_000L, null, null);
		SplitEvent nullLoot = event("missing", "Alice", null, SplitEvent.TYPE_LOOT, null);
		SplitEvent finalJoin = event("session", "Cara", 0L, SplitEvent.TYPE_JOINED, null);
		List<SplitEvent> events = Arrays.asList(
			joined, left, secondJoin, duplicateJoin, largeLoot, nullLoot, finalJoin);

		when(sessions.getAllEvents()).thenReturn(events);
		when(sessions.getAllSessionsNewestFirst()).thenReturn(Collections.emptyList());
		when(sessions.getSettlementConfigSnapshotFor(isNull())).thenReturn(
			new SettlementConfigSnapshot(false, "15m", 2.0d, "5m"));
		when(sessions.getGeTaxSettingsFor(any())).thenReturn(SplitCalculator.GeTaxSettings.disabled());
		when(sessions.prepareHistoryMutation()).thenReturn(true);

		SwingUtilities.invokeAndWait(() -> {
			HistoryTableModel model = new HistoryTableModel(
				sessions, config, null, null, messages::add, mutations::incrementAndGet);

			assertEquals(1, model.findLinkedRosterEventRow(0));
			assertEquals(2, model.findLinkedRosterEventRow(1));
			assertEquals(-1, model.findLinkedRosterEventRow(2));
			assertFalse(model.hasAssignedSplitsInRosterPeriod(0));
			assertFalse(model.hasAssignedSplitsInRosterPeriod(6));
			assertEquals("", model.getValueAt(2, 1));
			assertEquals("", model.getValueAt(0, HistoryTableModel.LOOT_COLUMN));
			assertEquals("JOINED", model.getValueAt(0, HistoryTableModel.TYPE_COLUMN));
			assertTrue(model.getValueAt(4, HistoryTableModel.LOOT_COLUMN).toString().endsWith("B"));
			assertEquals("", model.getValueAt(4, HistoryTableModel.TAX_COLUMN));
			assertTrue(model.getValueAt(4, HistoryTableModel.AFTER_TAX_COLUMN).toString().endsWith("B"));
			assertEquals("", model.getValueAt(5, HistoryTableModel.LOOT_COLUMN));
			assertEquals("", model.getValueAt(5, HistoryTableModel.AFTER_TAX_COLUMN));

			when(sessions.isCurrentHistoryEditLocked()).thenReturn(true);
			assertFalse(model.isCellEditable(4, HistoryTableModel.LOOT_COLUMN));
			when(sessions.isCurrentHistoryEditLocked()).thenReturn(false);
			assertTrue(model.isCellEditable(4, HistoryTableModel.DELETE_COLUMN));
			assertFalse(model.isCellEditable(-1, HistoryTableModel.PLAYER_COLUMN));

			when(sessions.prepareHistoryMutation()).thenReturn(false);
			model.setValueAt("Alice", 4, HistoryTableModel.PLAYER_COLUMN);
			assertEquals(0, mutations.get());

			when(sessions.prepareHistoryMutation()).thenReturn(true);
			when(sessions.updateHistoryLootEventAt(anyInt(), any(), isNull())).thenReturn(false);
			when(sessions.getLastMoveEventFailureMessage()).thenReturn(null);
			model.setValueAt("Alice", 4, HistoryTableModel.PLAYER_COLUMN);
			assertEquals("Cannot edit that history row.", messages.get(messages.size() - 1));

			when(sessions.getLastMoveEventFailureMessage()).thenReturn("Player is outside this roster period.");
			model.setValueAt("Alice", 4, HistoryTableModel.PLAYER_COLUMN);
			assertEquals("Player is outside this roster period.", messages.get(messages.size() - 1));

			when(sessions.updateHistoryLootEventAt(anyInt(), any(), isNull())).thenReturn(true);
			model.setValueAt("Alice", 4, HistoryTableModel.PLAYER_COLUMN);
			assertEquals(1, mutations.get());

			when(sessions.updateHistoryLootEventAt(anyInt(), isNull(), any())).thenReturn(false);
			model.setValueAt("2b", 4, HistoryTableModel.LOOT_COLUMN);
			assertEquals("Player is outside this roster period.", messages.get(messages.size() - 1));
			model.setValueAt(null, 4, HistoryTableModel.LOOT_COLUMN);
			assertEquals("Invalid loot amount.", messages.get(messages.size() - 1));
			model.setValueAt("ignored", 4, HistoryTableModel.TYPE_COLUMN);
			assertEquals(1, mutations.get());
		});
	}

	private static SplitEvent event(String sessionId, String player, Long amount, String type, Instant at)
	{
		SplitEvent event = new SplitEvent(sessionId, player, amount, at);
		event.setType(type);
		return event;
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
