/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager;

import com.splitmanager.controllers.PanelController;
import com.splitmanager.controllers.PanelCoordinator;
import com.splitmanager.ui.PopoutWindow;
import com.splitmanager.ui.PopoutWindowFactory;
import com.splitmanager.ui.SwingPopoutWindowFactory;
import com.splitmanager.ui.SwingUiInteractionGateway;
import com.splitmanager.ui.UiInteractionGateway;
import com.splitmanager.views.PanelView;
import com.splitmanager.views.PopoutView;
import com.splitmanager.views.RuneLitePanelHost;
import java.awt.Dimension;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;


/**
 * Composition root: builds Model, View, Controller and wires them together.
 * No UI logic or event handling lives here anymore.
 */
@Singleton
public class ManagerPanel implements PanelCoordinator
{

	private final ManagerSession manager;
	private final PluginConfig config;
	private final ManagerKnownPlayers playerManager;
	private final UiInteractionGateway interactions;
	private final PopoutWindowFactory popoutWindowFactory;
	private PopoutWindow popoutWindow;
	private PanelController controller;
	private PanelController popoutController;
	private PopoutView popoutView;
	@Getter
	@Setter
	private PanelView view;
	@Getter
	private RuneLitePanelHost runeLitePanel;

	/**
	 * Construct a new plugin panel and bootstrap its MVC components.
	 *
	 * @param sessionManager session/state sessionManager for split tracking
	 * @param config         plugin configuration
	 */
	@Inject
	public ManagerPanel(ManagerSession sessionManager, PluginConfig config, ManagerKnownPlayers playerManager,
	                    SwingUiInteractionGateway interactions, SwingPopoutWindowFactory popoutWindowFactory)
	{
		this(sessionManager, config, playerManager, (UiInteractionGateway) interactions,
			(PopoutWindowFactory) popoutWindowFactory);
	}

	ManagerPanel(ManagerSession sessionManager, PluginConfig config, ManagerKnownPlayers playerManager,
	             UiInteractionGateway interactions, PopoutWindowFactory popoutWindowFactory)
	{
		this.manager = sessionManager;
		this.config = config;
		this.playerManager = playerManager;
		this.interactions = interactions;
		this.popoutWindowFactory = popoutWindowFactory;
	}

	/**
	 * Refresh all view sections via the controller.
	 */
	public void refreshAllView()
	{
		refreshAllViews();
	}

	@Override
	public void refreshAllViews()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshAllViews);
			return;
		}
		if (controller != null)
		{
			controller.refreshAllView();
		}
		if (popoutController != null)
		{
			popoutController.refreshAllView();
		}
	}

	/**
	 * Initialize and wire the view and controller, and perform an initial sync.
	 */
	private void startPanel()
	{
		controller = new PanelController(manager, config, playerManager, this, interactions);
		view = new PanelView(manager, config, playerManager, controller, interactions);
		controller.setView(view);
		runeLitePanel = new RuneLitePanelHost(view);

		controller.refreshAllView();
	}

	public void togglePopOutWindow(boolean startInEditMode)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> togglePopOutWindow(startInEditMode));
			return;
		}
		if (popoutWindow != null && popoutWindow.isDisplayable())
		{
			popoutWindow.focus();
			if (popoutView != null)
			{
				popoutView.setEditMode(startInEditMode);
			}
			return;
		}

		popoutController = new PanelController(manager, config, playerManager, this, interactions);
		popoutView = new PopoutView(manager, config, playerManager, popoutController, interactions);
		if (startInEditMode)
		{
			popoutView.setEditMode(true);
		}
		popoutController.setView(popoutView.getSidebarView());

		popoutController.refreshAllView();

		popoutWindow = popoutWindowFactory.open(
			"Auto Split Manager",
			popoutView,
			new Dimension(1000, 650),
			new Dimension(1100, 720),
			this::clearPopoutReferences);
	}

	private void clearPopoutReferences()
	{
		popoutWindow = null;
		popoutController = null;
		popoutView = null;
	}

	@Override
	public void showPopout(boolean editMode)
	{
		togglePopOutWindow(editMode);
	}


	/**
	 * Recreate the panel components from scratch.
	 */
	public void restart()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::restart);
			return;
		}
		disposePopout();
		if (view != null)
		{
			view.removeAll();
		}
		startPanel();
	}

	public void shutDown()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::shutDown);
			return;
		}
		disposePopout();
		if (view != null)
		{
			view.removeAll();
		}
		controller = null;
		view = null;
		runeLitePanel = null;
	}

	private void disposePopout()
	{
		if (popoutWindow == null)
		{
			return;
		}
		PopoutWindow window = popoutWindow;
		clearPopoutReferences();
		window.dispose();
	}

	public void init()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::init);
			return;
		}
		startPanel();
	}
}
