/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui;

import java.awt.Component;

/**
 * Boundary for blocking desktop interactions that are unavailable in headless tests.
 */
public interface UiInteractionGateway
{
	void showMessage(Component parent, String message);

	boolean confirm(Component parent, String title, String message);

	String prompt(Component parent, String title, String message);

	void writeClipboardText(String text);
}
