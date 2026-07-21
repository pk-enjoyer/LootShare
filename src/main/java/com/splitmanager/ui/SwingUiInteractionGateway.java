/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.ui;

import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class SwingUiInteractionGateway implements UiInteractionGateway
{
	@Override
	public void showMessage(Component parent, String message)
	{
		JOptionPane.showMessageDialog(parent, message);
	}

	@Override
	public boolean confirm(Component parent, String title, String message)
	{
		return JOptionPane.showConfirmDialog(
			parent,
			message,
			title,
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
	}

	@Override
	public HistoryExportChoice chooseHistoryExport(Component parent)
	{
		Object[] options = {"All history", "Selected session", "Cancel"};
		int choice = JOptionPane.showOptionDialog(
			parent,
			"Export all history or the currently selected session?",
			"Export",
			JOptionPane.DEFAULT_OPTION,
			JOptionPane.QUESTION_MESSAGE,
			null,
			options,
			options[0]);
		if (choice == 0)
		{
			return HistoryExportChoice.ALL;
		}
		if (choice == 1)
		{
			return HistoryExportChoice.SELECTED;
		}
		return HistoryExportChoice.CANCEL;
	}

	@Override
	public void writeClipboardText(String text)
	{
		try
		{
			StringSelection selection = new StringSelection(text == null ? "" : text);
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
		}
		catch (IllegalStateException | HeadlessException e)
		{
			log.warn("Failed to write clipboard text", e);
		}
	}

	@Override
	public String readClipboardText()
	{
		try
		{
			Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			return data == null ? null : data.toString();
		}
		catch (UnsupportedFlavorException | IOException | IllegalStateException | HeadlessException e)
		{
			log.warn("Failed to read clipboard text", e);
			return null;
		}
	}
}
