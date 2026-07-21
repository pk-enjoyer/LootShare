/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views;

import java.awt.BorderLayout;
import java.util.Objects;
import net.runelite.client.ui.PluginPanel;

/**
 * The only RuneLite-specific container required by the otherwise plain Swing sidebar.
 */
public class RuneLitePanelHost extends PluginPanel
{
	private final PanelView content;

	public RuneLitePanelHost(PanelView content)
	{
		this.content = Objects.requireNonNull(content, "content");
		setLayout(new BorderLayout());
		add(content, BorderLayout.NORTH);
	}

	public PanelView getContent()
	{
		return content;
	}
}
