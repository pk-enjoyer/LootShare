/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui;

import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
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
	public String prompt(Component parent, String title, String message)
	{
		return (String) JOptionPane.showInputDialog(
			parent,
			message,
			title,
			JOptionPane.PLAIN_MESSAGE,
			null,
			null,
			"");
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

}
