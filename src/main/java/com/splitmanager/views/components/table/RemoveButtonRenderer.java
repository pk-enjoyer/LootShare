/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.views.components.table;

import com.splitmanager.views.components.PanelTheme;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

public class RemoveButtonRenderer extends JButton implements TableCellRenderer
{

	public RemoveButtonRenderer()
	{
		setOpaque(true);
		setBorder(BorderFactory.createLineBorder(PanelTheme.DARK_GRAY));
		setRolloverEnabled(true);
	}

	@Override
	public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
	{
		if (value instanceof java.awt.Component)
		{
			return (java.awt.Component) value;
		}
		return this;
	}
}
