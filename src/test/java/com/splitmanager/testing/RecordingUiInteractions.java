/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.testing;

import com.splitmanager.ui.HistoryExportChoice;
import com.splitmanager.ui.UiInteractionGateway;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordingUiInteractions implements UiInteractionGateway
{
	private final List<String> messages = new ArrayList<>();
	private boolean confirmResult = true;
	private HistoryExportChoice exportChoice = HistoryExportChoice.CANCEL;
	private String clipboardText;

	@Override
	public void showMessage(Component parent, String message)
	{
		messages.add(message);
	}

	@Override
	public boolean confirm(Component parent, String title, String message)
	{
		return confirmResult;
	}

	@Override
	public HistoryExportChoice chooseHistoryExport(Component parent)
	{
		return exportChoice;
	}

	@Override
	public void writeClipboardText(String text)
	{
		clipboardText = text;
	}

	@Override
	public String readClipboardText()
	{
		return clipboardText;
	}

	public void setConfirmResult(boolean confirmResult)
	{
		this.confirmResult = confirmResult;
	}

	public void setExportChoice(HistoryExportChoice exportChoice)
	{
		this.exportChoice = exportChoice;
	}

	public void setClipboardText(String clipboardText)
	{
		this.clipboardText = clipboardText;
	}

	public String getClipboardText()
	{
		return clipboardText;
	}

	public List<String> getMessages()
	{
		return Collections.unmodifiableList(messages);
	}

	public String getLastMessage()
	{
		return messages.isEmpty() ? null : messages.get(messages.size() - 1);
	}
}
