/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.ui.lootshare.PanelState.BalanceRow;
import com.communitylootshare.ui.lootshare.PanelState.SettlementState;
import com.communitylootshare.ui.lootshare.PanelState.TransferRow;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Shared settlement balances/payments renderer for the sidebar and popout.
 */
public final class SettlementView extends JPanel
{
	private static final Color POSITIVE_COLOR = new Color(110, 190, 110);
	private static final Color NEGATIVE_COLOR = new Color(220, 110, 110);

	private final JPanel content = new JPanel();

	public SettlementView()
	{
		setLayout(new BorderLayout());
		setOpaque(false);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(7, 7, 7, 7));
		add(content, BorderLayout.NORTH);
		render(SettlementState.empty());
	}

	private static JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setBorder(new EmptyBorder(0, 0, 3, 0));
		return label;
	}

	private static JPanel balanceHeader()
	{
		return balanceGrid("Member", "Loot", "Share", "Net", ColorScheme.LIGHT_GRAY_COLOR, null);
	}

	private static JPanel balanceRow(BalanceRow balance)
	{
		Color netColor = balance.getNet() > 0L
			? POSITIVE_COLOR
			: balance.getNet() < 0L ? NEGATIVE_COLOR : ColorScheme.LIGHT_GRAY_COLOR;
		return balanceGrid(balance.getDisplayName(), compact(balance.getLoot()), compact(balance.getShare()),
			signedCompact(balance.getNet()), netColor,
			"Loot: " + exact(balance.getLoot()) + " | Share: " + exact(balance.getShare())
				+ " | Net: " + signedExact(balance.getNet()));
	}

	private static JPanel balanceGrid(String member, String loot, String share, String net,
	                                  Color netColor, String tooltip)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridy = 0;
		constraints.insets = new Insets(2, 1, 2, 1);
		constraints.fill = GridBagConstraints.HORIZONTAL;
		addCell(row, member, 0, 0.46, SwingConstants.LEFT, Color.WHITE, tooltip, constraints);
		addCell(row, loot, 1, 0.18, SwingConstants.RIGHT, ColorScheme.LIGHT_GRAY_COLOR, tooltip, constraints);
		addCell(row, share, 2, 0.18, SwingConstants.RIGHT, ColorScheme.LIGHT_GRAY_COLOR, tooltip, constraints);
		addCell(row, net, 3, 0.18, SwingConstants.RIGHT, netColor, tooltip, constraints);
		return row;
	}

	private static void addCell(JPanel row, String text, int column, double weight, int alignment,
	                            Color color, String tooltip, GridBagConstraints constraints)
	{
		constraints.gridx = column;
		constraints.weightx = weight;
		JLabel label = new JLabel(text, alignment);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.putClientProperty("html.disable", Boolean.TRUE);
		label.setToolTipText(tooltip);
		row.add(label, constraints);
	}

	private static JPanel transferRow(TransferRow transfer)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, 1, 2, 1));
		JLabel route = new JLabel(transfer.getFrom() + "  →  " + transfer.getTo());
		route.setFont(FontManager.getRunescapeSmallFont());
		route.setForeground(Color.WHITE);
		route.putClientProperty("html.disable", Boolean.TRUE);
		JLabel amount = new JLabel(compact(transfer.getAmount()) + " gp", SwingConstants.RIGHT);
		amount.setFont(FontManager.getRunescapeSmallFont());
		amount.setForeground(NEGATIVE_COLOR);
		String tooltip = transfer.getFrom() + " pays " + transfer.getTo() + " " + exact(transfer.getAmount());
		route.setToolTipText(tooltip);
		amount.setToolTipText(tooltip);
		row.add(route, BorderLayout.CENTER);
		row.add(amount, BorderLayout.EAST);
		return row;
	}

	private static JLabel messageLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(new EmptyBorder(3, 1, 3, 1));
		return label;
	}

	private static String compact(long amount)
	{
		return QuantityFormatter.quantityToStackSize(amount);
	}

	private static String signedCompact(long amount)
	{
		return amount > 0L ? "+" + compact(amount) : compact(amount);
	}

	private static String exact(long amount)
	{
		return String.format(Locale.US, "%,d gp", amount);
	}

	private static String signedExact(long amount)
	{
		return String.format(Locale.US, "%s%,d gp", amount > 0L ? "+" : "", amount);
	}

	public void render(SettlementState state)
	{
		SettlementState safeState = state == null ? SettlementState.empty() : state;
		content.removeAll();
		content.add(sectionLabel("Balances"));
		if (safeState.getBalances().isEmpty())
		{
			content.add(messageLabel("No accepted loot yet."));
		}
		else
		{
			content.add(balanceHeader());
			for (BalanceRow balance : safeState.getBalances())
			{
				content.add(balanceRow(balance));
			}
		}

		content.add(Box.createVerticalStrut(8));
		content.add(sectionLabel("Payments"));
		if (safeState.getBalances().isEmpty())
		{
			content.add(messageLabel("Payments appear after shared loot."));
		}
		else if (safeState.getTransfers().isEmpty())
		{
			JLabel settled = messageLabel("Everyone is settled.");
			settled.setForeground(POSITIVE_COLOR);
			content.add(settled);
		}
		else
		{
			for (TransferRow transfer : safeState.getTransfers())
			{
				content.add(transferRow(transfer));
			}
		}

		content.add(Box.createVerticalStrut(7));
		JLabel total = new JLabel("Shared loot: " + compact(safeState.getTotalLoot()) + " gp");
		total.setFont(FontManager.getRunescapeSmallFont());
		total.setForeground(Color.WHITE);
		total.setToolTipText(exact(safeState.getTotalLoot()));
		content.add(total);
		revalidate();
		repaint();
	}
}
