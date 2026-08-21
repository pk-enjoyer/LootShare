/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare;

import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.ui.lootshare.UiController;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class LootsharePluginEventTest
{
	@Mock
	private LootshareController controller;

	@Mock
	private UiController uiController;

	@Mock
	private GameStateChanged event;

	@Mock
	private GameTick gameTick;

	@InjectMocks
	private LootsharePlugin plugin;

	@Test
	public void forwardsLoginStateToTheController()
	{
		plugin.onGameStateChanged(event);

		verify(controller).onGameStateChanged(event);
		verify(uiController, never()).refresh();
	}

	@Test
	public void forwardsGameTicksForDeferredIdentityAnnouncement()
	{
		plugin.onGameTick(gameTick);

		verify(controller).onGameTick();
	}
}
