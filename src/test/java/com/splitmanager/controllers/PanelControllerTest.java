/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.controllers;

import com.splitmanager.models.PendingValue;
import com.splitmanager.models.Session;
import com.splitmanager.models.SettlementConfigSnapshot;
import com.splitmanager.testing.RecordingUiInteractions;
import com.splitmanager.testing.UiScenarioFixture;
import com.splitmanager.ui.HistoryExportChoice;
import com.splitmanager.views.PanelView;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import org.junit.Test;

public class PanelControllerTest
{
	@Test
	public void drivesSessionStateAndUsesStubbedDesktopInteractions() throws Exception
	{
		Harness harness = createHarness(UiScenarioFixture.empty());
		UiScenarioFixture fixture = harness.fixture;
		PanelController controller = harness.controller;
		PanelView view = harness.view;
		SwingUtilities.invokeAndWait(() -> {
			assertTrue(view.getBtnStart().isEnabled());
			assertFalse(view.getBtnStop().isEnabled());

			controller.startSession();
			assertTrue(fixture.getSessions().hasActiveSession());
			assertTrue(view.getBtnStop().isEnabled());
			assertNotNull(harness.interactions.getLastMessage());

			controller.addPlayerToSession("Alice");
			assertTrue(fixture.getSessions().currentSessionHasPlayer("Alice"));
			view.getCurrentSessionPlayerDropdown().setSelectedItem("Alice");
			view.getLootAmountField().setValue(2_000_000L);
			controller.addLootFromInputs();
			assertFalse(fixture.getSessions().getAllEvents().isEmpty());

			harness.interactions.setConfirmResult(false);
			controller.stopSession();
			assertTrue(fixture.getSessions().hasActiveSession());

			harness.interactions.setConfirmResult(true);
			controller.stopSession();
			assertFalse(fixture.getSessions().hasActiveSession());

			harness.interactions.setExportChoice(HistoryExportChoice.ALL);
			controller.exportHistory(null);
			assertNotNull(harness.interactions.getClipboardText());
			assertTrue(harness.interactions.getClipboardText().startsWith("["));
			assertTrue(harness.interactions.getClipboardText().contains("Alice"));
			assertTrue(harness.coordinator.refreshCount > 0);
		});
	}

	@Test
	public void managesKnownPlayersAndAltRelationships() throws Exception
	{
		Harness harness = createHarness(UiScenarioFixture.empty());

		SwingUtilities.invokeAndWait(() -> {
			harness.controller.addKnownPlayer("  Dora  ");
			assertTrue(harness.fixture.getPlayers().isKnownPlayer("Dora"));
			assertEquals("", harness.view.getNewPlayerField().getText());

			harness.controller.addKnownPlayer("Dora");
			assertEquals("Player already in list exists.", harness.interactions.getLastMessage());
			harness.controller.addKnownPlayer("   ");
			assertEquals("Enter a name.", harness.interactions.getLastMessage());

			harness.controller.removeKnownPlayer(null);
			assertEquals("Select a Player to remove.", harness.interactions.getLastMessage());
			harness.interactions.setConfirmResult(false);
			harness.controller.removeKnownPlayer("Dora");
			assertTrue(harness.fixture.getPlayers().isKnownPlayer("Dora"));
			harness.interactions.setConfirmResult(true);
			harness.controller.removeKnownPlayer("Dora");
			assertFalse(harness.fixture.getPlayers().isKnownPlayer("Dora"));

			harness.controller.addAltToMain(null, "Cara");
			assertEquals("Select a player and an alt to add.", harness.interactions.getLastMessage());
			harness.controller.addAltToMain("Alice", "Alice");
			assertEquals("Cannot link: main/alt relation is invalid.", harness.interactions.getLastMessage());
			harness.controller.addAltToMain("Alice", "Cara");
			assertTrue(harness.fixture.getPlayers().isAlt("Cara"));
			assertEquals("Alice", harness.fixture.getPlayers().getMainName("Cara"));

			harness.controller.removeSelectedAlt(null, null);
			assertEquals("Select a player in Known list.", harness.interactions.getLastMessage());
			harness.controller.removeSelectedAlt("Alice", null);
			assertEquals("Select an alt in the list to remove.", harness.interactions.getLastMessage());
			harness.controller.removeSelectedAlt("Alice", "Bob");
			assertEquals("Bob is not linked as an alt.", harness.interactions.getLastMessage());
			harness.controller.removeSelectedAlt("Bob", "Cara");
			assertTrue(harness.interactions.getLastMessage().contains("not Bob"));

			harness.interactions.setConfirmResult(false);
			harness.controller.removeSelectedAlt("Alice", "Cara");
			assertTrue(harness.fixture.getPlayers().isAlt("Cara"));
			harness.interactions.setConfirmResult(true);
			harness.controller.removeSelectedAlt("Alice", "Cara");
			assertFalse(harness.fixture.getPlayers().isAlt("Cara"));
			harness.controller.refreshAltList(null);
			harness.controller.onKnownPlayerSelectionChanged("Alice");
		});

		Harness activeHarness = createHarness(UiScenarioFixture.active());
		SwingUtilities.invokeAndWait(() -> {
			activeHarness.controller.addAltToMain("Alice", "Bob");
			assertEquals("Cannot link: this alt already appears in the current session history.",
				activeHarness.interactions.getLastMessage());
			activeHarness.controller.removeSelectedAlt("Alice", "Cara");
			assertEquals("Cannot add/remove alts while session is active. Stop session first.",
				activeHarness.interactions.getLastMessage());
		});
	}

	@Test
	public void appliesAndDeletesDetectedValues() throws Exception
	{
		Harness harness = createHarness(UiScenarioFixture.active());

		SwingUtilities.invokeAndWait(() -> {
			harness.controller.addPlayerToSession(null);
			harness.controller.addPlayerToSession("Alice");
			harness.controller.addLoot(null, 10L);
			harness.controller.addLoot("Nobody", 10L);
			harness.view.getLootAmountField().setValue(null);
			harness.controller.addLootFromInputs();

			harness.controller.applySelectedPendingValue(-1);
			harness.controller.deleteSelectedPendingValue(-1);
			assertEquals(1, harness.view.getWaitlistTableModel().getRowCount());
			harness.view.getWaitlistTableModel().setValueAt("", 0, 2);
			harness.controller.applySelectedPendingValue(0);
			assertEquals("Choose a Suggested Player in the table first.", harness.interactions.getLastMessage());
			harness.view.getWaitlistTableModel().setValueAt("Alice", 0, 2);
			harness.controller.applySelectedPendingValue(0);
			assertTrue(harness.fixture.getSessions().getPendingValues().isEmpty());

			harness.fixture.getSessions().addPendingValue(PendingValue.of(
				PendingValue.Type.ADD, "Clan", "!add 1m", 1_000_000L, "Alice"));
			harness.controller.refreshAllView();
			assertEquals(1, harness.view.getWaitlistTableModel().getRowCount());
			harness.controller.deleteSelectedPendingValue(0);
			assertTrue(harness.fixture.getSessions().getPendingValues().isEmpty());
		});

		Harness noSession = createHarness(UiScenarioFixture.empty());
		noSession.fixture.getSessions().addPendingValue(PendingValue.of(
			PendingValue.Type.ADD, "Clan", "!add 1m", 1_000_000L, "Alice"));
		SwingUtilities.invokeAndWait(() -> {
			noSession.controller.refreshAllView();
			noSession.controller.applySelectedPendingValue(0);
			assertEquals("Start a session first.", noSession.interactions.getLastMessage());
		});
	}

	@Test
	public void loadsClosesAndExportsHistory() throws Exception
	{
		UiScenarioFixture fixture = UiScenarioFixture.loadedHistory(true);
		fixture.getSessions().unloadHistory();
		Harness harness = createHarness(fixture);
		String historyId = fixture.getSessions().getHistorySessionsNewestFirst().get(0).getId();

		SwingUtilities.invokeAndWait(() -> {
			harness.controller.loadHistory(null);
			assertEquals("Select a session from history.", harness.interactions.getLastMessage());
			harness.controller.loadHistory("missing");
			assertEquals("Failed to load history.", harness.interactions.getLastMessage());
			harness.controller.loadHistory(historyId);
			assertTrue(fixture.getSessions().isHistoryLoaded());
			assertEquals("HISTORY LOADED", harness.view.getHistoryLabel().getText());

			harness.controller.stopSession();
			assertFalse(fixture.getSessions().isHistoryLoaded());
			harness.controller.unloadHistory();
			harness.controller.loadHistory(historyId);
			harness.controller.unloadHistory();
			assertFalse(fixture.getSessions().isHistoryLoaded());

			harness.interactions.setExportChoice(HistoryExportChoice.CANCEL);
			harness.interactions.setClipboardText(null);
			harness.controller.exportHistory(historyId);
			assertNull(harness.interactions.getClipboardText());
			harness.interactions.setExportChoice(HistoryExportChoice.SELECTED);
			harness.controller.exportHistory(null);
			assertEquals("Select a session from history.", harness.interactions.getLastMessage());
			harness.controller.exportHistory("missing");
			assertEquals("Failed to export selected history.", harness.interactions.getLastMessage());
			harness.controller.exportHistory(historyId);
			assertTrue(harness.interactions.getClipboardText().contains(historyId));

			harness.controller.startSession();
			harness.controller.loadHistory(historyId);
			assertEquals("Stop the current session first.", harness.interactions.getLastMessage());
			harness.interactions.setConfirmResult(true);
			harness.controller.stopSession();
		});

		Harness empty = createHarness(UiScenarioFixture.empty());
		SwingUtilities.invokeAndWait(() -> {
			empty.controller.exportHistory(null);
			assertEquals("No history to export.", empty.interactions.getLastMessage());
		});
	}

	@Test
	public void importsHistoryThroughTheClipboardSeam() throws Exception
	{
		UiScenarioFixture source = UiScenarioFixture.loadedHistory(true);
		String payload = source.getSessions().exportHistorySessionsJson();
		Harness target = createHarness(UiScenarioFixture.empty());

		SwingUtilities.invokeAndWait(() -> {
			target.interactions.setClipboardText("   ");
			target.controller.importHistoryFromClipboard();
			assertEquals("Clipboard does not contain history JSON.", target.interactions.getLastMessage());
			target.interactions.setClipboardText("not json");
			target.controller.importHistoryFromClipboard();
			assertEquals("No valid history found in clipboard.", target.interactions.getLastMessage());
			target.interactions.setClipboardText(payload);
			target.controller.importHistoryFromClipboard();
			assertFalse(target.fixture.getSessions().getHistorySessionsNewestFirst().isEmpty());
			assertEquals("Imported 1 history thread from clipboard.", target.interactions.getLastMessage());
		});
	}

	@Test
	public void appliesSavesAndDiscardsHistoricalSettlementContext() throws Exception
	{
		Harness harness = createHarness(UiScenarioFixture.loadedHistory(true));
		SettlementConfigSnapshot valid = new SettlementConfigSnapshot(true, "15m", 2.0d, "5m");

		SwingUtilities.invokeAndWait(() -> {
			harness.controller.applyHistorySettlementContext(null);
			assertEquals("Missing history context.", harness.interactions.getLastMessage());
			harness.controller.applyHistorySettlementContext(
				new SettlementConfigSnapshot(true, "15m", Double.NaN, "5m"));
			assertEquals("GE tax percent must be zero or higher.", harness.interactions.getLastMessage());
			harness.controller.applyHistorySettlementContext(
				new SettlementConfigSnapshot(true, "bad", 2.0d, "5m"));
			assertEquals("Use valid GE amounts, like 8.75m or 5m.", harness.interactions.getLastMessage());

			harness.controller.applyHistorySettlementContext(valid);
			assertEquals("History context applied.", harness.interactions.getLastMessage());
			harness.interactions.setConfirmResult(true);
			harness.controller.saveHistorySettlementContext(valid);
			assertEquals("History context saved.", harness.interactions.getLastMessage());
			harness.controller.cancelHistorySettlementContextEdit();
			assertEquals("History edits discarded.", harness.interactions.getLastMessage());
		});

		Harness empty = createHarness(UiScenarioFixture.empty());
		SwingUtilities.invokeAndWait(() -> {
			empty.controller.applyHistorySettlementContext(valid);
			assertEquals("Load history first.", empty.interactions.getLastMessage());
			empty.controller.saveHistorySettlementContext(valid);
			assertEquals("Load history first.", empty.interactions.getLastMessage());
			empty.controller.cancelHistorySettlementContextEdit();
		});
	}

	@Test
	public void refreshesMetricsCopiesOutputAndDelegatesWindowAndTourActions() throws Exception
	{
		Harness harness = createHarness(UiScenarioFixture.active());
		Session session = harness.fixture.getSessions().getCurrentSession().orElseThrow();

		SwingUtilities.invokeAndWait(() -> {
			harness.controller.refreshSharedViews();
			harness.controller.recomputeMetrics();
			harness.controller.recomputeMetricsForSession(null);
			harness.controller.recomputeMetricsForSession("missing");
			harness.controller.recomputeMetricsForSession(session.getId());

			harness.controller.copyMetricsJson();
			assertNotNull(harness.interactions.getClipboardText());
			harness.controller.copyMetricsMarkdown();
			assertTrue(harness.interactions.getClipboardText().contains("Alice"));

			harness.controller.togglePopout(true);
			assertEquals(1, harness.coordinator.popoutCount);
			assertTrue(harness.coordinator.lastEditMode);

			harness.controller.tourStart();
			harness.controller.tourNext();
			harness.controller.tourPrev();
			harness.controller.tourEnd(true);
			verify(harness.fixture.getConfig()).tourUpdateInfoSeenVersion("3.1.0");
			harness.controller.tourStart();
			harness.controller.tourEnd(false);
			verify(harness.fixture.getConfig()).enableTour(false);
		});
	}

	private static Harness createHarness(UiScenarioFixture fixture) throws Exception
	{
		RecordingUiInteractions interactions = new RecordingUiInteractions();
		RecordingCoordinator coordinator = new RecordingCoordinator();
		AtomicReference<PanelController> controllerReference = new AtomicReference<>();
		AtomicReference<PanelView> viewReference = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() -> {
			PanelController controller = new PanelController(
				fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), coordinator, interactions);
			PanelView view = new PanelView(
				fixture.getSessions(), fixture.getConfig(), fixture.getPlayers(), controller, interactions);
			controller.setView(view);
			controller.refreshAllView();
			controllerReference.set(controller);
			viewReference.set(view);
		});

		return new Harness(fixture, interactions, coordinator, controllerReference.get(), viewReference.get());
	}

	private static final class Harness
	{
		private final UiScenarioFixture fixture;
		private final RecordingUiInteractions interactions;
		private final RecordingCoordinator coordinator;
		private final PanelController controller;
		private final PanelView view;

		private Harness(UiScenarioFixture fixture, RecordingUiInteractions interactions,
		                RecordingCoordinator coordinator, PanelController controller, PanelView view)
		{
			this.fixture = fixture;
			this.interactions = interactions;
			this.coordinator = coordinator;
			this.controller = controller;
			this.view = view;
		}
	}

	private static final class RecordingCoordinator implements PanelCoordinator
	{
		private int refreshCount;
		private int popoutCount;
		private boolean lastEditMode;

		@Override
		public void refreshAllViews()
		{
			refreshCount++;
		}

		@Override
		public void showPopout(boolean editMode)
		{
			popoutCount++;
			lastEditMode = editMode;
		}
	}
}
