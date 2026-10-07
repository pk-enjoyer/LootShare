/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.*;
import com.communitylootshare.integration.LootshareController;
import java.time.Instant;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import org.junit.Test;
import javax.swing.SwingUtilities;
import com.communitylootshare.testing.RecordingUiInteractions;
import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class UiControllerManualGpTest
{
	@Test public void rendersManualContributionWithTheCoinImageAndCapturedAmount() throws Exception
	{
		ItemManager items = mock(ItemManager.class);
		PanelState.MemberLoot member = new PanelState.MemberLoot(1L, "Alice", true, true, true, null,
			2, 0, 2000L, Collections.singletonList(new PanelState.LootItem(0, "Manual GP", 2L, 2000L)));
		SwingUtilities.invokeAndWait(() -> {
			Panel panel = new Panel(mock(PanelActions.class), new RecordingUiInteractions(), items);
			panel.render(new PanelState(true, true, "party", Collections.singletonList(member)));
		});
		verify(items).getImage(ItemID.COINS, 2000, true);
		verify(items, never()).getImage(eq(0), anyInt(), anyBoolean());
	}
	@Test public void manualGpMustNotBePresentedAsGameItemZero()
	{
		PartyService party = mock(PartyService.class);
		ItemManager items = mock(ItemManager.class);
		LootshareController lootshare = mock(LootshareController.class);
		PartyMember member = new PartyMember(1L);
		member.setDisplayName("Alice");
		when(party.isInParty()).thenReturn(true);
		when(party.getMembers()).thenReturn(Collections.singletonList(member));
		when(party.getLocalMember()).thenReturn(member);
		when(lootshare.getActiveHostSettings()).thenReturn(Optional.empty());
		when(lootshare.getActiveSession()).thenReturn(Optional.empty());
		ItemComposition remains = mock(ItemComposition.class);
		when(remains.getName()).thenReturn("Dwarf remains");
		when(items.getItemComposition(0)).thenReturn(remains);
		LootProposal manual = LootProposal.pending(77L, 1L, new SharedLootEvent("manual-gp-test",
			"Alice", "Manual GP", Instant.EPOCH, Collections.singletonList(new SharedLootItem(0, 0, 1L, 1000L))))
			.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1),
				Collections.singletonList(new LootshareParticipant(1L, "Alice")));
		when(lootshare.getActivePartyProposals()).thenReturn(Collections.singletonList(manual));
		UiController ui = new UiController(mock(Client.class), mock(ClientThread.class), party, items, lootshare);
		PanelState state = ui.buildState();
		assertEquals("Manual GP", state.getMembers().get(0).getItems().get(0).getName());
	}
}
