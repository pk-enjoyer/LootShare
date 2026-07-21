/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.ui;

import java.awt.Component;

/**
 * Boundary for blocking desktop interactions that are unavailable in headless tests.
 */
public interface UiInteractionGateway
{
	void showMessage(Component parent, String message);

	boolean confirm(Component parent, String title, String message);

	HistoryExportChoice chooseHistoryExport(Component parent);

	void writeClipboardText(String text);

	String readClipboardText();
}
