/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.ui.lootshare;

import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;

public class Panel extends PluginPanel
{
	private final JLabel content = new JLabel("Create or join a RuneLite Party to begin sharing loot.");

	public Panel()
	{
		JPanel panel = new JPanel();
		panel.add(content);
		add(panel);
	}

	public void render(String text)
	{
		content.setText(text);
	}
}
