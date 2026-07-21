/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views.components;

import java.awt.Color;
import javax.swing.AbstractButton;

/**
 * Local Swing theme values matching RuneLite's dark panel palette.
 */
public final class PanelTheme
{
	public static final Color BRAND_ORANGE = new Color(220, 138, 0);
	public static final Color BRAND_ORANGE_TRANSPARENT = new Color(220, 138, 0, 120);
	public static final Color DARKER_GRAY = new Color(30, 30, 30);
	public static final Color DARK_GRAY = new Color(40, 40, 40);
	public static final Color MEDIUM_GRAY = new Color(77, 77, 77);
	public static final Color LIGHT_GRAY = new Color(165, 165, 165);
	public static final Color BORDER = new Color(23, 23, 23);

	private PanelTheme()
	{
	}

	public static void styleIconButton(AbstractButton button)
	{
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
	}
}
