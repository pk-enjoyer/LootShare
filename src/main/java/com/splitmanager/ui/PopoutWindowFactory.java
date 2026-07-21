/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.ui;

import java.awt.Dimension;
import javax.swing.JComponent;

public interface PopoutWindowFactory
{
	PopoutWindow open(String title, JComponent content, Dimension minimumSize, Dimension size, Runnable onClosed);
}
