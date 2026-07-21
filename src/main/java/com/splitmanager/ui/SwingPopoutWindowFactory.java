/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.WindowConstants;

@Singleton
public class SwingPopoutWindowFactory implements PopoutWindowFactory
{
	@Override
	public PopoutWindow open(String title, JComponent content, Dimension minimumSize, Dimension size, Runnable onClosed)
	{
		JFrame frame = new JFrame(title);
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.getContentPane().add(content, BorderLayout.CENTER);
		frame.setMinimumSize(minimumSize);
		frame.setSize(size);
		frame.setLocationRelativeTo(null);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent event)
			{
				onClosed.run();
			}
		});
		frame.setVisible(true);
		return new SwingPopoutWindow(frame);
	}

	private static final class SwingPopoutWindow implements PopoutWindow
	{
		private final JFrame frame;

		private SwingPopoutWindow(JFrame frame)
		{
			this.frame = frame;
		}

		@Override
		public boolean isDisplayable()
		{
			return frame.isDisplayable();
		}

		@Override
		public void focus()
		{
			frame.toFront();
			frame.requestFocus();
		}

		@Override
		public void dispose()
		{
			frame.dispose();
		}
	}
}
