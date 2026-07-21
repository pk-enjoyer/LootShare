/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.testing;

import com.splitmanager.controllers.PanelActions;
import com.splitmanager.models.SettlementConfigSnapshot;

public class RecordingPanelActions implements PanelActions
{
	private String lastAction;
	private String firstValue;
	private String secondValue;
	private int row = -1;
	private boolean flag;

	private void record(String action)
	{
		lastAction = action;
	}

	@Override public void startSession() { record("startSession"); }
	@Override public void stopSession() { record("stopSession"); }
	@Override public void addPlayerToSession(String player) { firstValue = player; record("addPlayerToSession"); }
	@Override public void addKnownPlayer(String name) { firstValue = name; record("addKnownPlayer"); }
	@Override public void removeKnownPlayer(String name) { firstValue = name; record("removeKnownPlayer"); }
	@Override public void addLoot(String player, long amount) { firstValue = player; secondValue = String.valueOf(amount); record("addLoot"); }
	@Override public void addLootFromInputs() { record("addLootFromInputs"); }
	@Override public void addAltToMain(String main, String alt) { firstValue = main; secondValue = alt; record("addAltToMain"); }
	@Override public void removeSelectedAlt(String main, String selectedEntry) { firstValue = main; secondValue = selectedEntry; record("removeSelectedAlt"); }
	@Override public void applySelectedPendingValue(int tableRowIndex) { row = tableRowIndex; record("applySelectedPendingValue"); }
	@Override public void deleteSelectedPendingValue(int tableRowIndex) { row = tableRowIndex; record("deleteSelectedPendingValue"); }
	@Override public void loadHistory(String sessionId) { firstValue = sessionId; record("loadHistory"); }
	@Override public void unloadHistory() { record("unloadHistory"); }
	@Override public void exportHistory(String selectedSessionId) { firstValue = selectedSessionId; record("exportHistory"); }
	@Override public void importHistoryFromClipboard() { record("importHistoryFromClipboard"); }
	@Override public void saveHistorySettlementContext(SettlementConfigSnapshot snapshot) { record("saveHistorySettlementContext"); }
	@Override public void applyHistorySettlementContext(SettlementConfigSnapshot snapshot) { record("applyHistorySettlementContext"); }
	@Override public void cancelHistorySettlementContextEdit() { record("cancelHistorySettlementContextEdit"); }
	@Override public void onKnownPlayerSelectionChanged(String selected) { firstValue = selected; record("onKnownPlayerSelectionChanged"); }
	@Override public void refreshAllView() { record("refreshAllView"); }
	@Override public void refreshSharedViews() { record("refreshSharedViews"); }
	@Override public void recomputeMetrics() { record("recomputeMetrics"); }
	@Override public void recomputeMetricsForSession(String sessionId) { firstValue = sessionId; record("recomputeMetricsForSession"); }
	@Override public void copyMetricsJson() { record("copyMetricsJson"); }
	@Override public void copyMetricsMarkdown() { record("copyMetricsMarkdown"); }
	@Override public void togglePopout(boolean editMode) { flag = editMode; record("togglePopout"); }
	@Override public void tourStart() { record("tourStart"); }
	@Override public void tourPrev() { record("tourPrev"); }
	@Override public void tourNext() { record("tourNext"); }
	@Override public void tourEnd(boolean updateInfo) { flag = updateInfo; record("tourEnd"); }

	public String getLastAction() { return lastAction; }
	public String getFirstValue() { return firstValue; }
	public String getSecondValue() { return secondValue; }
	public int getRow() { return row; }
	public boolean getFlag() { return flag; }
}
