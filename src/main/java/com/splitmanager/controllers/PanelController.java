/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.controllers;

import com.splitmanager.ManagerKnownPlayers;
import com.splitmanager.ManagerSession;
import com.splitmanager.PluginConfig;
import com.splitmanager.models.Metrics;
import com.splitmanager.models.PendingValue;
import com.splitmanager.models.Session;
import com.splitmanager.models.SettlementConfigSnapshot;
import com.splitmanager.views.components.PanelTour;
import com.splitmanager.models.WaitlistTable;
import com.splitmanager.ui.HistoryExportChoice;
import com.splitmanager.ui.UiInteractionGateway;
import com.splitmanager.utils.Formats;
import com.splitmanager.utils.MarkdownFormatter;
import com.splitmanager.views.PanelView;
import java.awt.Color;
import java.text.ParseException;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import lombok.extern.slf4j.Slf4j;

/**
 * MVC Controller: non-UI logic + event handling. The View calls into this via PanelActions.
 * Keeps string/markdown/transfer computations here and pushes UI refreshes through the View.
 */
@Slf4j
public class PanelController implements PanelActions
{
	private final ManagerSession sessionManager;
	private final PluginConfig config;
	private final ManagerKnownPlayers playerManager;
	private final PanelCoordinator coordinator;
	private final UiInteractionGateway interactions;
	private PanelView view;

	public PanelController(ManagerSession sessionManager, PluginConfig config, ManagerKnownPlayers playerManager,
	                       PanelCoordinator coordinator, UiInteractionGateway interactions)
	{
		this.sessionManager = sessionManager;
		this.playerManager = playerManager;
		this.config = config;
		this.coordinator = coordinator;
		this.interactions = interactions;
	}

	public void setView(PanelView view)
	{
		this.view = view;
		sessionManager.setHistoryEditWarningHandler(this::confirmHistoryEdit);
	}

	private boolean confirmHistoryEdit()
	{
		return interactions.confirm(view,
			"Edit history?",
			"You are editing saved history. Changes will only overwrite history when you press Save in the History context box. Apply only refreshes the view with the entered context.");
	}

	@Override
	public void startSession()
	{
		if (sessionManager.isHistoryLoaded())
		{
			sessionManager.unloadHistory();
		}
		if (sessionManager.hasActiveSession())
		{
			showMessage("Active session exists.");
			return;
		}
		sessionManager.startSession().ifPresent(s -> showMessage("Session started."));
		coordinator.refreshAllViews();
		refreshAllView();
	}

	@Override
	public void stopSession()
	{
		if (sessionManager.isHistoryLoaded())
		{
			sessionManager.unloadHistory();
			showMessage("History closed.");
			coordinator.refreshAllViews();
			refreshAllView();
			return;
		}
		if (!interactions.confirm(view, "Confirm stop", "Are you sure you want to stop the session?"))
		{
			return;
		}
		if (sessionManager.stopSession())
		{
			coordinator.refreshAllViews();
		}
		else
		{
			showMessage("Failed to stop session.");
		}
		refreshAllView();
	}

	@Override
	public void addPlayerToSession(String player)
	{
		if (player == null)
		{
			showMessage("Select a player in dropdown.");
			return;
		}
		if (sessionManager.addPlayerToActive(player))
		{
			coordinator.refreshAllViews();
		}
		else
		{
			showMessage("Failed to add player, player might already be in session.");
		}
		refreshAllView();
	}

	@Override
	public void addKnownPlayer(String name)
	{
		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty())
		{
			showMessage("Enter a name.");
			return;
		}
		if (!playerManager.addKnownPlayer(clean))
		{
			showMessage("Player already in list exists.");
			return;
		}
		playerManager.saveToConfig();
		coordinator.refreshAllViews();
		view.getKnownPlayersDropdown().setSelectedItem(clean);
		view.getNewPlayerField().setText("");
		view.getNewPlayerField().requestFocusInWindow();
		refreshAllView();
	}

	@Override
	public void removeKnownPlayer(String name)
	{
		if (name == null)
		{
			showMessage("Select a Player to remove.");
			return;
		}
		if (!interactions.confirm(view, "Confirm removal",
			"Remove '" + name + "'? This will also unlink any alt relationships."))
		{
			return;
		}

		if (!playerManager.removeKnownPlayer(name))
		{
			showMessage("Not found.");
			return;
		}
		playerManager.saveToConfig();
		coordinator.refreshAllViews();
		refreshAllView();
	}

	@Override
	public void addLoot(String player, long amount)
	{
		if (player == null)
		{
			showMessage("Select a player.");
			return;
		}
		if (sessionManager.addLoot(player, amount))
		{
			view.getLootAmountField().setText("");
			coordinator.refreshAllViews();
		}
		else
		{
			showMessage("Failed to add loot (is player in session?).");
		}
		refreshAllView();
	}

	@Override
	public void addLootFromInputs()
	{
		String player = (String) view.getCurrentSessionPlayerDropdown().getSelectedItem();

		Object rawValue = view.getLootAmountField().getValue();
		if (rawValue == null)
		{
			showMessage("Please enter a valid amount.");
			return;
		}

		String val = rawValue.toString();
		long amt;
		try
		{
			if (rawValue instanceof Number)
			{
				amt = ((Number) rawValue).longValue();
			}
			else
			{
				amt = Formats.OsrsAmountFormatter.stringAmountToLongAmount(val, config);
			}
			log.debug("Adding loot for {} with amount {}", player, amt);
			addLoot(player, amt);
		}
		catch (Exception ex)
		{
			log.warn("Invalid loot amount {}", val, ex);
			showMessage("Invalid amount.");
		}
	}

	@Override
	public void addAltToMain(String main, String alt)
	{
		if (main == null || alt == null)
		{
			showMessage("Select a player and an alt to add.");
			return;
		}
		if (sessionManager.hasActiveSession() && sessionManager.currentThreadContainsPlayerName(alt))
		{
			showMessage("Cannot link: this alt already appears in the current session history.");
			return;
		}
		if (!playerManager.canLinkAltToMain(alt, main))
		{
			showMessage("Cannot link: main/alt relation is invalid.");
			return;
		}
		if (playerManager.trySetAltMain(alt, main))
		{
			showMessage(String.format("Linked %s → %s", alt, main));
			coordinator.refreshAllViews();
			refreshAllView();
		}
		else
		{
			showMessage("Failed to link alt.");
		}
	}

	@Override
	public void removeSelectedAlt(String selectedMain, String selectedEntry)
	{
		if (sessionManager.hasActiveSession())
		{
			showMessage("Cannot add/remove alts while session is active. Stop session first.");
			return;
		}
		if (selectedMain == null || selectedMain.isBlank())
		{
			showMessage("Select a player in Known list.");
			return;
		}
		if (selectedEntry == null || selectedEntry.isBlank())
		{
			showMessage("Select an alt in the list to remove.");
			return;
		}

		String alt = playerManager.parseAltFromEntry(selectedEntry);
		String main = playerManager.getMainName(alt);

		if (!playerManager.isAlt(alt))
		{
			showMessage(alt + " is not linked as an alt.");
			return;
		}

		if (main == null || !main.equalsIgnoreCase(selectedMain))
		{
			showMessage(String.format("%s is linked to %s, not %s.", alt, main, selectedMain));
			return;
		}

		if (!interactions.confirm(view, "Confirm unlink", "Unlink '" + alt + "' from '" + main + "'?"))
		{
			return;
		}

		if (playerManager.unlinkAlt(alt))
		{
			showMessage("Unlinked alt.");
			coordinator.refreshAllViews();
			playerManager.saveToConfig();
			refreshAllView();
		}
		else
		{
			showMessage("Failed to unlink alt.");
		}
	}

	@Override
	public void applySelectedPendingValue(int idx)
	{
		if (idx < 0)
		{
			showMessage("Select a detected value first.");
			return;
		}
		if (!sessionManager.hasActiveSession() && !sessionManager.isHistoryLoaded())
		{
			showMessage("Start a session first.");
			return;
		}
		WaitlistTable m = view.getWaitlistTableModel();
		PendingValue pv = m.getRow(idx);
		if (pv == null)
		{
			return;
		}
		String target = pv.getSuggestedPlayer();
		if (target == null || target.isBlank())
		{
			showMessage("Choose a Suggested Player in the table first.");
			return;
		}
		if (sessionManager.applyPendingValueToPlayer(pv.getId(), target))
		{
			coordinator.refreshAllViews();
		}
		else
		{
			showMessage("Failed to add value. Is the player in the session?");
		}
		refreshAllView();
	}

	@Override
	public void deleteSelectedPendingValue(int idx)
	{
		if (idx < 0)
		{
			showMessage("Select a detected value first.");
			return;
		}
		WaitlistTable m = view.getWaitlistTableModel();
		PendingValue pv = m.getRow(idx);
		if (pv == null)
		{
			return;
		}
		if (sessionManager.removePendingValueById(pv.getId()))
		{
			coordinator.refreshAllViews();
		}
		refreshAllView();
	}

	@Override
	public void loadHistory(String sessionId)
	{
		if (sessionId == null || sessionId.isBlank())
		{
			showMessage("Select a session from history.");
			return;
		}
		if (sessionManager.hasActiveSession())
		{
			showMessage("Stop the current session first.");
			return;
		}
		if (sessionManager.loadHistory(sessionId).isPresent())
		{
			showMessage("History loaded.");
			coordinator.refreshAllViews();
		}
		else
		{
			showMessage("Failed to load history.");
		}
		refreshAllView();
	}

	@Override
	public void unloadHistory()
	{
		if (!sessionManager.isHistoryLoaded())
		{
			return;
		}
		sessionManager.unloadHistory();
		showMessage("History closed.");
		coordinator.refreshAllViews();
		refreshAllView();
	}

	@Override
	public void exportHistory(String selectedSessionId)
	{
		if (sessionManager.getHistorySessionsNewestFirst().isEmpty())
		{
			showMessage("No history to export.");
			return;
		}

		HistoryExportChoice choice = interactions.chooseHistoryExport(view);

		if (choice == HistoryExportChoice.ALL)
		{
			copyToClipboard(sessionManager.exportHistorySessionsJson());
			showMessage("All history JSON copied.");
			return;
		}

		if (choice == HistoryExportChoice.SELECTED)
		{
			if (selectedSessionId == null || selectedSessionId.isBlank())
			{
				showMessage("Select a session from history.");
				return;
			}
			String payload = sessionManager.exportSessionThreadJson(selectedSessionId);
			if (payload.isEmpty())
			{
				showMessage("Failed to export selected history.");
				return;
			}
			copyToClipboard(payload);
			showMessage("Selected history JSON copied.");
		}
	}

	@Override
	public void importHistoryFromClipboard()
	{
		String clipboard = readClipboardText();
		if (clipboard == null || clipboard.trim().isEmpty())
		{
			showMessage("Clipboard does not contain history JSON.");
			return;
		}

		int importedThreads = sessionManager.importHistorySessionsJson(clipboard);
		if (importedThreads <= 0)
		{
			showMessage("No valid history found in clipboard.");
			return;
		}

		coordinator.refreshAllViews();
		refreshAllView();
		showMessage(importedThreads == 1
			? "Imported 1 history thread from clipboard."
			: "Imported " + importedThreads + " history threads from clipboard.");
	}

	@Override
	public void saveHistorySettlementContext(SettlementConfigSnapshot snapshot)
	{
		if (!sessionManager.isHistoryLoaded())
		{
			showMessage("Load history first.");
			return;
		}
		if (!isValidSettlementContext(snapshot))
		{
			return;
		}
		Session current = getMetricsSession();
		if (sessionManager.updateSettlementConfigSnapshotFor(current, snapshot)
			&& sessionManager.saveHistoryChanges())
		{
			showMessage("History context saved.");
			coordinator.refreshAllViews();
			refreshAllView();
		}
		else
		{
			showMessage("Failed to save history context.");
		}
	}

	@Override
	public void applyHistorySettlementContext(SettlementConfigSnapshot snapshot)
	{
		if (!sessionManager.isHistoryLoaded())
		{
			showMessage("Load history first.");
			return;
		}
		if (!isValidSettlementContext(snapshot))
		{
			return;
		}
		applyHistorySettlementContextToView(snapshot);
		showMessage("History context applied.");
	}

	@Override
	public void cancelHistorySettlementContextEdit()
	{
		if (sessionManager.discardHistoryChanges())
		{
			showMessage("History edits discarded.");
			coordinator.refreshAllViews();
			refreshAllView();
			return;
		}
		refreshHistorySettlementContext();
	}

	@Override
	public void onKnownPlayerSelectionChanged(String selected)
	{
		refreshAlts();
	}

	@Override
	public void refreshAllView()
	{
		refreshKnownPlayers();
		recomputeMetrics();
		refreshSessionData();
		refreshWaitlist();
		refreshHistory();
		refreshButtonStates();
	}

	@Override
	public void refreshSharedViews()
	{
		coordinator.refreshAllViews();
	}

	@Override
	public void recomputeMetrics()
	{
		Session current = getMetricsSession();
		if (current != null)
		{
			view.refreshMetrics();
			refreshRecentSplits(current);
		}
		else
		{
			view.getRecentSplitsModel().clear();
		}
	}

	@Override
	public void recomputeMetricsForSession(String sessionId)
	{
		if (sessionId == null)
		{
			recomputeMetrics();
			return;
		}
		// Find that session (either current or one from history)
		Session target = sessionManager.getAllSessionsNewestFirst().stream()
			.filter(s -> sessionId.equals(s.getId()))
			.findFirst().orElse(null);
		if (target != null)
		{
			((Metrics) view.getMetricsTable().getModel()).setData(
				sessionManager.computeMetricsFor(target, true)
			);
			view.refreshMetrics();
		}
		// Keep the recent splits list up-to-date (it shows all events)
		refreshRecentSplits(target);
	}

	@Override
	public void copyMetricsJson()
	{
		String payload = MarkdownFormatter.buildMetricsJson(sessionManager);
		copyToClipboard(payload);
	}

	@Override
	public void copyMetricsMarkdown()
	{
		String payload = MarkdownFormatter.buildMetricsMarkdown(
			sessionManager.computeMetricsFor(getMetricsSession(), true), config);
		copyToClipboard(payload);
	}

	@Override
	public void togglePopout(boolean editMode)
	{
		coordinator.showPopout(editMode);
	}

	// Tutorial control implementations to keep view passive
	@Override
	public void tourStart()
	{
		view.startTour();
	}

	@Override
	public void tourPrev()
	{
		view.prevTourStep();
	}

	@Override
	public void tourNext()
	{
		view.nextTourStep();
	}

	@Override
	public void tourEnd(boolean updateInfo)
	{
		try
		{
			if (updateInfo)
			{
				config.tourUpdateInfoSeenVersion(PanelTour.UPDATE_INFO_VERSION);
			}
			else
			{
				config.enableTour(false);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Failed to persist tour state", e);
		}
		view.endTour();
	}

	private boolean isValidSettlementContext(SettlementConfigSnapshot snapshot)
	{
		if (snapshot == null)
		{
			showMessage("Missing history context.");
			return false;
		}
		if (Double.isNaN(snapshot.getGeTaxPercent())
			|| Double.isInfinite(snapshot.getGeTaxPercent())
			|| snapshot.getGeTaxPercent() < 0.0d)
		{
			showMessage("GE tax percent must be zero or higher.");
			return false;
		}
		try
		{
			Formats.OsrsAmountFormatter.stringAmountToLongAmount(snapshot.getGeTaxMinimumValue(), null);
			Formats.OsrsAmountFormatter.stringAmountToLongAmount(snapshot.getGeTaxMaxPerLoot(), null);
			return true;
		}
		catch (ParseException e)
		{
			showMessage("Use valid GE amounts, like 8.75m or 5m.");
			return false;
		}
	}

	private Session getMetricsSession()
	{
		if (sessionManager.isHistoryLoaded())
		{
			return sessionManager.getCurrentEditableSession().orElse(sessionManager.getCurrentSession().orElse(null));
		}
		return sessionManager.getCurrentSession().orElse(null);
	}

	private void copyToClipboard(String payload)
	{
		interactions.writeClipboardText(payload);
	}

	private String readClipboardText()
	{
		return interactions.readClipboardText();
	}

	private void showMessage(String message)
	{
		interactions.showMessage(view, message);
	}

	/**
	 * Refreshes the list of known players and updates corresponding UI components.
	 * <p>
	 * This method retrieves the list of known players from the session manager and updates
	 * the dropdown menu for known players in the user interface, as well as the label showing
	 * the count of known players. It ensures synchronization between the backend data and
	 * the visual presentation of known players.
	 */
	private void refreshKnownPlayers()
	{
		String[] players = sessionManager.getKnownPlayers().toArray(new String[0]);
		setModelPreservingSelection(view.getKnownPlayersDropdown(), players);
		view.getKnownListLabel().setText("Known (" + players.length + "):");

		refreshAlts();
	}

	private void setModelPreservingSelection(JComboBox<String> comboBox, String[] values)
	{
		Object selected = comboBox.getSelectedItem();
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(values);
		comboBox.setModel(model);
		if (selected == null)
		{
			return;
		}
		String selectedValue = selected.toString();
		for (String value : values)
		{
			if (selectedValue.equals(value))
			{
				comboBox.setSelectedItem(selectedValue);
				return;
			}
		}
	}

	/**
	 * Refreshes and updates user interface components related to the current session data.
	 */
	private void refreshSessionData()
	{
		Session currentSession = sessionManager.isHistoryLoaded()
			? sessionManager.getCurrentEditableSession().orElse(null)
			: sessionManager.getCurrentSession().orElse(null);

		if (currentSession != null && (currentSession.isActive() || sessionManager.isHistoryLoaded()))
		{
			String[] sessionPlayers = currentSession.getPlayers().toArray(new String[0]);
			String[] notPlayers = sessionManager.getNonActivePlayers().toArray(new String[0]);

			view.getCurrentSessionPlayerDropdown().setEnabled(true);
			setModelPreservingSelection(view.getCurrentSessionPlayerDropdown(), sessionPlayers);
			setModelPreservingSelection(view.getNotInCurrentSessionPlayerDropdown(), notPlayers);
		}
		else
		{
			setModelPreservingSelection(view.getCurrentSessionPlayerDropdown(), new String[0]);
			setModelPreservingSelection(view.getNotInCurrentSessionPlayerDropdown(), new String[0]);
			view.getCurrentSessionPlayerDropdown().setEnabled(false);
		}

		if (sessionManager.isHistoryLoaded())
		{
			view.getHistoryLabel().setText("HISTORY LOADED");
			view.getHistoryLabel().setOpaque(true);
			view.getHistoryLabel().setBackground(new Color(132, 84, 0));
			view.getHistoryLabel().setForeground(Color.WHITE);
			view.getHistoryLabel().setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));
			view.getHistoryLabel().setToolTipText("Start closes history and starts a new session. Stop closes history.");
		}
		else
		{
			view.getHistoryLabel().setText("History: OFF");
			view.getHistoryLabel().setOpaque(false);
			view.getHistoryLabel().setBackground(null);
			view.getHistoryLabel().setForeground(null);
			view.getHistoryLabel().setBorder(null);
			view.getHistoryLabel().setToolTipText(null);
		}
		refreshHistorySettlementContext();

		Session current = getMetricsSession();
		if (current != null)
		{
			refreshRecentSplits(current);
		}
		else
		{
			view.getRecentSplitsModel().clear();
		}
	}

	private void refreshRecentSplits(Session contextSession)
	{
		view.getRecentSplitsModel().setSettlementConfigSnapshot(sessionManager.getSettlementConfigSnapshotFor(contextSession));
		view.getRecentSplitsModel().setFromEvents(sessionManager.getAllEvents());
	}

	private void applyHistorySettlementContextToView(SettlementConfigSnapshot snapshot)
	{
		Session current = getMetricsSession();
		if (current == null)
		{
			return;
		}
		((Metrics) view.getMetricsTable().getModel()).setData(
			sessionManager.computeMetricsFor(current, true, snapshot)
		);
		view.refreshMetricsContent();
		view.getRecentSplitsModel().setGeTaxSettings(sessionManager.getGeTaxSettingsFor(snapshot));
		view.getRecentSplitsModel().setFromEvents(sessionManager.getAllEvents());
	}

	private void refreshHistorySettlementContext()
	{
		Session current = getMetricsSession();
		boolean visible = sessionManager.isHistoryLoaded() && current != null;
		view.setHistoryContextVisible(visible);
		if (visible)
		{
			view.setHistoryContextSnapshot(sessionManager.getSettlementConfigSnapshotFor(current));
		}
	}

	/**
	 * Refreshes the data and UI components related to the waitlist table.
	 * <p>
	 * This method fetches the latest pending values from the session manager and updates
	 * the waitlist table model with this data. It also configures the cell editor for the
	 * third column of the waitlist table, populating it with a dropdown of known main players
	 * retrieved from the session manager.
	 * <p>
	 * Ensures that the waitlist table reflects the most up-to-date state of the application.
	 */
	private void refreshWaitlist()
	{
		view.getWaitlistTableModel().setData(sessionManager.getPendingValues());
		view.getWaitlistTable()
			.getColumnModel()
			.getColumn(2)
			.setCellEditor(new DefaultCellEditor(new JComboBox<>(
				playerManager.getKnownMains().toArray(new String[0]
				)
			)));
	}

	private void refreshHistory()
	{
		String selectedSessionId = sessionManager.isHistoryLoaded()
			? sessionManager.getCurrentSession().map(Session::getId).orElse(null)
			: view.getSelectedHistorySessionId();
		view.setHistorySessions(sessionManager.getHistorySessionsNewestFirst(), selectedSessionId);
		view.setArchivedHistoryWarningVisible(sessionManager.hasArchivedSessionFiles());
	}

	/**
	 * Updates the enabled or disabled state of various buttons and fields in the user interface.
	 * The button states are set based on the current session status, player selections, and
	 * whether a completed history session is currently loaded.
	 */
	private void refreshButtonStates()
	{
		boolean historyMode = sessionManager.isHistoryLoaded();
		boolean hasActiveSession = sessionManager.hasActiveSession();
		boolean hasLoadedHistory = historyMode && sessionManager.getCurrentEditableSession().isPresent();
		boolean historyEditLocked = historyMode && sessionManager.isCurrentHistoryEditLocked();
		boolean canEditLoadedHistory = hasLoadedHistory && !historyEditLocked;
		boolean canMutateSession = hasActiveSession || canEditLoadedHistory;

		view.getBtnStart().setEnabled(historyMode || !hasActiveSession);
		view.getBtnStop().setEnabled(historyMode || hasActiveSession);
		view.getBtnAddToSession().setEnabled(canMutateSession);
		view.getNotInCurrentSessionPlayerDropdown().setEnabled(canMutateSession);
		view.getBtnRemoveFromSession().setEnabled(canMutateSession);

		boolean hasSessionPlayers = view.getCurrentSessionPlayerDropdown().getItemCount() > 0;

		view.getBtnAddLoot().setEnabled(canMutateSession && hasSessionPlayers);
		view.getLootAmountField().setEnabled(canMutateSession && hasSessionPlayers);

		int waitlistRows = view.getWaitlistTableModel().getRowCount();

		view.getBtnWaitlistAdd().setEnabled(canMutateSession && waitlistRows > 0);
		view.getBtnWaitlistDelete().setEnabled(waitlistRows > 0);

		boolean hasHistory = view.getHistorySessionDropdown().getItemCount() > 0;
		view.getHistorySessionDropdown().setEnabled(!hasActiveSession && hasHistory);
		view.getBtnViewHistory().setEnabled(!hasActiveSession && hasHistory);
		view.getBtnUnloadHistory().setEnabled(historyMode);
		view.getBtnExportHistory().setEnabled(hasHistory);
		view.getBtnImportHistory().setEnabled(true);
		view.getRecentSplitsTable().setEnabled(!historyEditLocked);
	}

	/**
	 * Refreshes the dropdown lists and UI components related to alternate accounts.
	 * This method ensures that the UI reflects the currently selected main player
	 * and dynamically updates the list of eligible alternate accounts that can be linked to the selected player.
	 */
	private void refreshAlts()
	{
		String[] players = sessionManager.getKnownPlayers().toArray(new String[0]);

		String selectedMain = (String) view.getKnownPlayersDropdown().getSelectedItem();
		if (selectedMain == null && players.length > 0)
		{
			selectedMain = players[0];
			view.getKnownPlayersDropdown().setSelectedIndex(0);
		}

		refreshAltList(selectedMain);

		List<String> eligiblePlayers = new java.util.ArrayList<>();

		if (selectedMain != null)
		{
			for (String p : sessionManager.getKnownPlayers())
			{
				if (playerManager.canLinkAltToMain(p, selectedMain))
				{
					eligiblePlayers.add(p);
				}
			}
		}

		setModelPreservingSelection(view.getAddAltDropdown(), eligiblePlayers.toArray(new String[0]));
	}

	/**
	 * Refreshes the list of alternate accounts associated with the given main player.
	 * Updates the alt label and list in the view with relevant information about the player's alt accounts.
	 * If the player is identified as an alt, displays the corresponding main account name.
	 *
	 * @param mainPlayer the name of the main player whose alternate accounts are to be refreshed;
	 *                   if null, the method exits without performing any action.
	 */
	public void refreshAltList(String mainPlayer)
	{
		if (mainPlayer == null)
		{
			return;
		}

		String altsText = !mainPlayer.isBlank()
			? (mainPlayer + " known alts:")
			: "Known alts:";
		view.getAltsLabel().setText(altsText);

		DefaultListModel<String> altsModel = (DefaultListModel<String>) view.getAltsList().getModel();
		altsModel.clear();

		if (playerManager.isAlt(mainPlayer))
		{
			String mainName = playerManager.getMainName(mainPlayer);
			if (mainName != null && !mainName.equalsIgnoreCase(mainPlayer))
			{
				altsModel.addElement(mainPlayer + " is an alt of " + mainName);
			}
		}

		for (String alt : playerManager.getAltsOf(mainPlayer))
		{
			altsModel.addElement(alt);
		}
	}
}
