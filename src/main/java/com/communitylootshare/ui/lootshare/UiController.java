/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.ui.lootshare;
import com.communitylootshare.integration.LootshareController;
import javax.inject.Inject;
import javax.inject.Singleton;
@Singleton public class UiController
{
	private final LootshareController controller;
	private Panel panel;
	@Inject public UiController(LootshareController controller) { this.controller = controller; }
	public void start(Panel panel) { this.panel = panel; refresh(); }
	public void stop() { panel = null; }
	public void refresh() { if (panel != null) panel.render(controller.getSummary()); }
}
