/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.experiment;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.data.PartyDataService;
import net.runelite.client.party.data.PartyDataType;
import net.runelite.client.party.data.events.PartyDataChange;
import net.runelite.client.party.data.events.PartyDataEvent;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs the actual upstream service with mocked game and transport boundaries. */
public class PartyDataServiceCompatibilityTest
{
	private Client client;
	private ClientThread clientThread;
	private PartyService party;
	private ItemContainer inventory;
	private EventBus eventBus;
	private PartyDataService service;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		party = mock(PartyService.class);
		inventory = mock(ItemContainer.class);
		eventBus = new EventBus();
		PartyMember local = mock(PartyMember.class);
		when(local.getMemberId()).thenReturn(11L);
		when(party.isInParty()).thenReturn(true);
		when(party.getLocalMember()).thenReturn(local);
		when(party.getMembers()).thenReturn(Collections.singletonList(local));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, 50), null});
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		service = Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(Client.class).toInstance(client);
				bind(ClientThread.class).toInstance(clientThread);
				bind(PartyService.class).toInstance(party);
				bind(EventBus.class).toInstance(eventBus);
				bind(WSClient.class).toInstance(mock(WSClient.class));
			}
		}).getInstance(PartyDataService.class);
	}

	@Test
	public void sharesOneInventoryStreamUntilLastSubscriberUnregisters()
	{
		Plugin first = subscriber("first");
		Plugin second = subscriber("second");
		service.register(first, PartyDataType.INVENTORY);
		service.register(second, PartyDataType.INVENTORY);
		verify(clientThread, times(1)).invoke(any(Runnable.class));
		service.onGameTick(new GameTick());
		ArgumentCaptor<PartyDataChange> sent = ArgumentCaptor.forClass(PartyDataChange.class);
		verify(party).send(sent.capture());
		assertArrayEquals(new int[]{ItemID.COINS, 50, -1, 0}, sent.getValue().getInventory());
		assertEquals(11L, sent.getValue().getMemberId());

		service.unregisterAll(first);
		clearInvocations(party);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, 75)});
		service.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory));
		service.onGameTick(new GameTick());
		verify(party).send(sent.capture());
		assertArrayEquals(new int[]{ItemID.COINS, 75}, sent.getValue().getInventory());

		service.unregisterAll(second);
		clearInvocations(party);
		service.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory));
		service.onGameTick(new GameTick());
		verify(party, never()).send(any());
	}

	@Test
	public void decodesRemoteInventoryOnTheEventBusWithTransportMemberIdentity()
	{
		InventoryEvents received = new InventoryEvents();
		eventBus.register(received);
		PartyDataChange incoming = new PartyDataChange();
		incoming.setMemberId(22L);
		incoming.setInventory(new int[]{ItemID.COINS, 80, -1, 0});
		eventBus.post(incoming);
		assertEquals(1, received.events.size());
		PartyDataEvent event = received.events.get(0);
		assertEquals(PartyDataType.INVENTORY, event.getType());
		assertEquals(22L, event.getMemberId());
		Item[] items = (Item[]) event.getData();
		assertEquals(ItemID.COINS, items[0].getId());
		assertEquals(80, items[0].getQuantity());
		assertNull(items[1]);
	}

	@Test
	public void userSyncReseedsOnlyRequestedInventory()
	{
		service.register(subscriber("inventory"), PartyDataType.INVENTORY);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, 100)});
		service.onUserSync(new UserSync());
		service.onGameTick(new GameTick());
		ArgumentCaptor<PartyDataChange> sent = ArgumentCaptor.forClass(PartyDataChange.class);
		verify(party).send(sent.capture());
		assertArrayEquals(new int[]{ItemID.COINS, 100}, sent.getValue().getInventory());
		assertNull(sent.getValue().getEquipment());
		assertNull(sent.getValue().getStats());
		assertNull(sent.getValue().getAvailablePrayers());
	}

	private static Plugin subscriber(String name)
	{
		Plugin plugin = mock(Plugin.class);
		when(plugin.getName()).thenReturn(name);
		return plugin;
	}

	public static class InventoryEvents
	{
		private final List<PartyDataEvent> events = new ArrayList<>();

		@Subscribe
		public void onPartyDataEvent(PartyDataEvent event)
		{
			events.add(event);
		}
	}
}
