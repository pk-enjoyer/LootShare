/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.debug.CommunityLootshareDebugSession;
import com.communitylootshare.debug.CommunityLootshareDebugSession.LootPreset;
import com.communitylootshare.integration.CommunityLootshareController;
import com.communitylootshare.sessions.CommunityLootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyService;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommunityLootshareUiControllerDebugTest
{
	private PartyService partyService;
	private ItemManager itemManager;
	private CommunityLootshareDebugSession debugSession;
	private CommunityLootshareUiController controller;

	@Before
	public void setUp()
	{
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		partyService = mock(PartyService.class);
		itemManager = mock(ItemManager.class);
		CommunityLootshareController lootshareController = mock(CommunityLootshareController.class);
		debugSession = new CommunityLootshareDebugSession(true, new LootshareCalculator());
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		ItemComposition coins = mock(ItemComposition.class);
		when(coins.getName()).thenReturn("Coins");
		when(itemManager.getItemComposition(ItemID.COINS)).thenReturn(coins);
		ItemComposition fang = mock(ItemComposition.class);
		when(fang.getName()).thenReturn("Osmumten's fang");
		when(itemManager.getItemComposition(ItemID.OSMUMTENS_FANG)).thenReturn(fang);
		when(itemManager.getItemPrice(ItemID.OSMUMTENS_FANG)).thenReturn(18_402_523);
		when(itemManager.getItemPrice(ItemID.LIGHTBEARER)).thenReturn(2_500_000);
		when(itemManager.getItemPrice(ItemID.MASORI_BODY)).thenReturn(43_636_445);
		when(itemManager.getItemPrice(ItemID.TUMEKENS_SHADOW_UNCHARGED)).thenReturn(880_012_003);
		controller = new CommunityLootshareUiController(client, clientThread, partyService,
			itemManager, lootshareController, debugSession);
		controller.start(mock(CommunityLootsharePanel.class));
	}

	@Test
	public void rendersASelectedToaRewardThroughTheNormalItemSnapshotPath()
	{
		controller.startDebugSimulation();
		long ownerMemberId = debugSession.snapshot().getOwnerMemberId();

		assertTrue(controller.addDebugLoot(
			ownerMemberId, LootPreset.OSMUMTENS_FANG, 1L));

		CommunityLootsharePanelState state = controller.buildState();
		CommunityLootsharePanelState.LootItem item = state.getMembers().get(0).getItems().get(0);
		assertEquals(ItemID.OSMUMTENS_FANG, item.getItemId());
		assertEquals("Osmumten's fang", item.getName());
		assertEquals(1L, item.getQuantity());
		assertEquals(18_402_523L, item.getTotalValue());
		assertEquals(18_402_523L, state.getDebug().getLootPresetPrice(LootPreset.OSMUMTENS_FANG));
		assertEquals("Tombs of Amascut (debug)",
			debugSession.snapshot().getProposals().get(0).getEvent().getSourceLabel());
	}

	@Test
	public void routesDebugActionsIntoTheSameMemberLootSnapshot() throws Exception
	{
		controller.startDebugSimulation();
		assertTrue(controller.addDebugPlayer("Alice"));
		long aliceMemberId = debugSession.snapshot().getParticipants().get(1).getMemberId();
		assertEquals(MutationResult.APPLIED, debugSession.setMemberApproved(aliceMemberId, true));
		assertTrue(controller.addDebugLoot(aliceMemberId, 250_000L));

		CommunityLootsharePanelState state = controller.buildState();
		assertTrue(state.isReady());
		assertTrue(state.isInParty());
		assertTrue(state.getDebug().isAvailable());
		assertTrue(state.getDebug().isSimulationActive());
		assertEquals(2, state.getMembers().size());
		assertTrue(state.getMembers().get(0).isLocal());
		assertEquals("Debug owner", state.getMembers().get(0).getDisplayName());
		assertEquals("Alice", state.getMembers().get(1).getDisplayName());
		assertEquals(0, state.getMembers().get(1).getPendingProposalCount());
		assertEquals(250_000L, state.getMembers().get(1).getTotalValue());
		assertEquals("Coins", state.getMembers().get(1).getItems().get(0).getName());
		verify(itemManager, never()).getItemPrice(ItemID.COINS);

		controller.leaveParty();
		assertFalse(debugSession.isActive());
		verify(partyService, never()).changeParty(null);
		SwingUtilities.invokeAndWait(() -> { });
	}

	@Test
	public void rejectsAnItemPresetWhenRuneLiteHasNoPrice()
	{
		when(itemManager.getItemPrice(ItemID.MASORI_BODY)).thenReturn(0);
		controller.startDebugSimulation();
		long ownerMemberId = debugSession.snapshot().getOwnerMemberId();

		assertFalse(controller.addDebugLoot(ownerMemberId, LootPreset.MASORI_BODY, 99_000_000L));
		assertTrue(debugSession.snapshot().getProposals().isEmpty());
	}

	@Test
	public void resetsAndClearsDebugStateWhenTheUiStops()
	{
		controller.startDebugSimulation();
		assertTrue(controller.addDebugPlayer("Alice"));
		controller.resetDebugSimulation();
		assertEquals(1, debugSession.snapshot().getParticipants().size());

		controller.stop();
		assertFalse(debugSession.isActive());
		assertFalse(controller.addDebugPlayer("Bob"));
	}
}
