/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.controllers;

/**
 * Coordinates actions that affect more than one panel instance.
 */
public interface PanelCoordinator
{
	void refreshAllViews();

	void showPopout(boolean editMode);
}
