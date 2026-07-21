/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * SPDX-License-Identifier: BSD-2-Clause
 *
 * Adapted from RuneLite's config section UI in:
 * runelite-client/src/main/java/net/runelite/client/plugins/config/ConfigPanel.java
 * https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/config/ConfigPanel.java
 *
 * This local wrapper preserves the old DropdownRip constructor shape used by
 * external plugins while reusing RuneLite's expand/collapse panel styling.
 */

package com.splitmanager.views.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.annotation.Nonnull;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/**
 * Drop-in replacement for the old DropdownRip used across RuneLite plugins.
 * Constructor signature matches the legacy usage: new DropdownRip(title, content).
 * The header is clickable and toggles the visibility of the content area.
 */
public class DropdownRip extends JPanel
{
	private static final int BORDER_OFFSET = 6;
	private static final int PANEL_WIDTH = 225;
	private static final Dimension HEADER_CONTROL_SIZE = new Dimension(24, 24);

	private final Header header;
	private final JPanel contentHolder;
	private boolean expanded;

	public DropdownRip(String title, JComponent content, boolean expanded)
	{
		this(title, content, expanded, null, null);
	}

	public DropdownRip(String title, JComponent content, boolean expanded, String tooltip)
	{
		this(title, content, expanded, tooltip, null);
	}

	public DropdownRip(String title, JComponent content, boolean expanded, String tooltip, JComponent extraComponent)
	{
		super(new BorderLayout());
		this.expanded = expanded;

		header = new Header(title, tooltip, extraComponent);
		header.setBorder(new EmptyBorder(3, 0, 0, 0));
		contentHolder = new JPanel(new BorderLayout());
		contentHolder.setOpaque(false);
		contentHolder.setBorder(new EmptyBorder(3, 0, 3, 0));
		contentHolder.add(content);

		add(header, BorderLayout.NORTH);
		add(contentHolder, BorderLayout.CENTER);

		updateExpanded();
	}

	public void setExpanded(boolean expanded)
	{
		if (this.expanded != expanded)
		{
			this.expanded = expanded;
			updateExpanded();
		}
	}


	public void toggle()
	{
		setExpanded(!expanded);
	}


	private void updateExpanded()
	{
		contentHolder.setVisible(expanded);
		header.setExpanded(expanded);
		revalidate();
		repaint();
	}

	private final class Header extends JPanel
	{
		private final JButton sectionToggle;

		private Header(String title, String tooltip, JComponent extraComponent)
		{
			super(new BorderLayout());

			final JPanel section = new JPanel();
			section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
			section.setMinimumSize(new Dimension(PANEL_WIDTH, 0));

			final JPanel sectionHeader = getJPanel();
			section.add(sectionHeader, BorderLayout.NORTH);

			sectionToggle = new JButton(expanded ? "\u25be" : "\u25b8");
			sectionToggle.setPreferredSize(new Dimension(18, 0));
			sectionToggle.setBorder(new EmptyBorder(0, 0, 0, 0));
			sectionToggle.setToolTipText(expanded ? "Retract" : "Expand");
			PanelTheme.styleIconButton(sectionToggle);
			sectionHeader.add(sectionToggle, BorderLayout.WEST);

			JLabel sectionName = new JLabel(title);
			sectionName.setForeground(PanelTheme.BRAND_ORANGE);
			sectionName.setFont(sectionName.getFont().deriveFont(Font.BOLD));
			sectionHeader.add(sectionName, BorderLayout.CENTER);

			JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			rightPanel.setOpaque(false);

			if (extraComponent != null)
			{
				styleHeaderControl(extraComponent);
				rightPanel.add(extraComponent);
			}

			JLabel info = new JLabel("\uD83D\uDEC8"); // info symbol
			info.setToolTipText(tooltip);
			info.setVisible(tooltip != null && !tooltip.isEmpty());
			info.setHorizontalAlignment(SwingConstants.CENTER);
			info.setVerticalAlignment(SwingConstants.CENTER);
			styleHeaderControl(info);
			rightPanel.add(info);

			sectionHeader.add(rightPanel, BorderLayout.EAST);

			final JPanel sectionContents = new JPanel();
			sectionContents.setLayout(new BoxLayout(sectionContents, BoxLayout.Y_AXIS));
			sectionContents.setMinimumSize(new Dimension(PANEL_WIDTH, 0));
			if (expanded)
			{
				sectionContents.setBorder(new CompoundBorder(
					new MatteBorder(0, 0, 1, 0, PanelTheme.MEDIUM_GRAY),
					new EmptyBorder(BORDER_OFFSET, 0, BORDER_OFFSET, 0)));
			}
			sectionContents.setVisible(expanded);
			section.add(sectionContents);

			setOpaque(true);
			setBackground(PanelTheme.DARK_GRAY);
			setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, PanelTheme.BORDER));
			setPreferredSize(new Dimension(10, 28));

			add(section);

			MouseAdapter click = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					toggle();
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					sectionName.setForeground(PanelTheme.BRAND_ORANGE_TRANSPARENT);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					sectionName.setForeground(PanelTheme.BRAND_ORANGE);
				}
			};

			addMouseListener(click);
			sectionName.addMouseListener(click);
			sectionHeader.addMouseListener(click);
			sectionToggle.addActionListener(a -> toggle());
		}

		@Nonnull
		private JPanel getJPanel()
		{
			final JPanel sectionHeader = new JPanel();
			sectionHeader.setLayout(new BorderLayout());
			sectionHeader.setMinimumSize(new Dimension(PANEL_WIDTH, 0));
			// For whatever reason, the header extends out by a single pixel when closed. Adding a single pixel of
			// border on the right only affects the width when closed, fixing the issue.
			if (!expanded)
			{
				sectionHeader.setBorder(new CompoundBorder(
					new MatteBorder(0, 0, 1, 0, PanelTheme.MEDIUM_GRAY),
					new EmptyBorder(0, 0, 3, 1)));
			}
			return sectionHeader;
		}

		private void styleHeaderControl(JComponent component)
		{
			component.setPreferredSize(HEADER_CONTROL_SIZE);
			component.setMinimumSize(HEADER_CONTROL_SIZE);
			component.setMaximumSize(HEADER_CONTROL_SIZE);
		}

		private void setExpanded(boolean ex)
		{
			sectionToggle.setText(ex ? "\u25be" : "\u25b8");
			sectionToggle.setToolTipText(ex ? "Retract" : "Expand");
			sectionToggle.repaint();
		}
	}
}
