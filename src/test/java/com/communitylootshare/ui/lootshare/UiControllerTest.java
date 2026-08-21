/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.integration.LootshareController;
import com.communitylootshare.ui.PopoutWindow;
import com.communitylootshare.ui.PopoutWindowFactory;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import java.awt.Dimension;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.party.PartyConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UiControllerTest
{
	private Client client;
	private ClientThread clientThread;
	private PartyService partyService;
	private ItemManager itemManager;
	private PartyConfig partyConfig;
	private LootshareController lootshareController;
	private UiController controller;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		partyService = mock(PartyService.class);
		itemManager = mock(ItemManager.class);
		partyConfig = mock(PartyConfig.class);
		lootshareController = mock(LootshareController.class);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		controller = new UiController(
			client, clientThread, partyService, itemManager, lootshareController, null, partyConfig);
	}

	@Test
	public void buildsLocalFirstMemberSnapshotsAndShowsOnlyAcceptedDrops()
	{
		PartyMember local = member(1L, "<unknown>", true);
		PartyMember remote = member(2L, "<unknown>", false);
		when(partyService.isInParty()).thenReturn(true);
		when(partyService.getPartyPassphrase()).thenReturn("party-pass");
		when(partyService.getLocalMember()).thenReturn(local);
		when(partyService.getMembers()).thenReturn(Arrays.asList(remote, local));
		when(lootshareController.isReady()).thenReturn(true);
		when(lootshareController.getActiveHostMemberId()).thenReturn(2L);
		when(lootshareController.getMemberApprovalStatus(anyLong()))
			.thenReturn(MemberApprovalStatus.PENDING);
		when(lootshareController.getMemberApprovalStatus(2L))
			.thenReturn(MemberApprovalStatus.APPROVED);
		LootshareSettings hostSettings = new LootshareSettings(250_000L, LootValueBasis.HIGH_ALCHEMY,
			true, false, true, false, true, false);
		when(lootshareController.getActiveHostSettings()).thenReturn(Optional.of(hostSettings));

		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Alice");
		when(client.getLocalPlayer()).thenReturn(player);
		ItemComposition item = mock(ItemComposition.class);
		when(item.getName()).thenReturn("Abyssal whip");
		when(itemManager.getItemComposition(100)).thenReturn(item);

		LootProposal pending = LootProposal.pending(77L, 2L,
			event("pending", "Bob", new SharedLootItem(101, 100, 2L, 100L)));
		LootProposal accepted = LootProposal.pending(77L, 2L,
			event("accepted", "Bob", new SharedLootItem(102, 100, 3L, 50L)))
			.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(2),
				Collections.singletonList(new LootshareParticipant(2L, "Bob")));
		LootProposal rejected = LootProposal.pending(77L, 2L,
			event("rejected", "Bob", new SharedLootItem(200, 200, 1L, 999L)))
			.decide(LootProposalStatus.REJECTED, Instant.ofEpochSecond(2), Collections.emptyList());
		when(lootshareController.getActivePartyProposals()).thenReturn(Arrays.asList(rejected, accepted, pending));

		PanelState state = controller.buildState();

		assertTrue(state.isReady());
		assertTrue(state.isInParty());
		assertEquals("party-pass", state.getPartyPassphrase());
		assertEquals(2, state.getMembers().size());
		assertEquals("Alice", state.getMembers().get(0).getDisplayName());
		assertTrue(state.getMembers().get(0).isLocal());
		assertFalse(state.getMembers().get(0).isHost());

		MemberLoot bob = state.getMembers().get(1);
		assertEquals("Bob", bob.getDisplayName());
		assertTrue(bob.isHost());
		assertFalse(bob.isLoggedIn());
		assertEquals(1, bob.getProposalCount());
		assertEquals(0, bob.getPendingProposalCount());
		assertEquals(150L, bob.getTotalValue());
		assertEquals(1, bob.getItems().size());
		assertEquals(100, bob.getItems().get(0).getItemId());
		assertEquals("Abyssal whip", bob.getItems().get(0).getName());
		assertEquals(3L, bob.getItems().get(0).getQuantity());
		assertEquals(150L, bob.getItems().get(0).getTotalValue());
		assertTrue(state.getHostedSettings().isAvailable());
		assertEquals(2L, state.getHostedSettings().getHostMemberId());
		assertEquals("Bob", state.getHostedSettings().getHostDisplayName());
		assertFalse(state.getHostedSettings().isLocalHost());
		assertEquals(hostSettings, state.getHostedSettings().getSettings());
	}

	@Test
	public void validatesAndNormalizesPartyActionsOnClientThread() throws Exception
	{
		Panel panel = mock(Panel.class);
		when(partyService.isInParty()).thenReturn(false);
		when(partyService.generatePassphrase()).thenReturn("generated-passphrase");
		controller.start(panel);
		SwingUtilities.invokeAndWait(() -> { });

		controller.createParty();
		verify(partyService).changeParty("generated-passphrase");
		assertTrue(controller.joinParty("  ABC-123  "));
		verify(partyService).changeParty("abc-123");
		assertFalse(controller.joinParty("bad passphrase!"));
		verify(partyService, never()).changeParty("bad passphrase!");

		when(partyConfig.previousPartyId()).thenReturn("  PREVIOUS-123  ");
		controller.joinPreviousParty();
		verify(partyService).changeParty("previous-123");
		controller.onPartyChanged(new PartyChanged("NEW-PREVIOUS-456", 77L));
		verify(partyConfig).setPreviousPartyId("new-previous-456");

		when(partyService.isInParty()).thenReturn(true);
		controller.transferHost(2L);
		verify(lootshareController).transferHost(2L);
		controller.setMemberApproved(2L, true);
		verify(lootshareController).setMemberApproved(2L, true);
		controller.leaveParty();
		verify(partyService).changeParty(null);
		controller.stop();
		verify(lootshareController).setStateChangeListener(null);
	}

	@Test
	public void returnsEmptyMembersOutsideAParty()
	{
		when(lootshareController.isReady()).thenReturn(true);
		when(partyService.isInParty()).thenReturn(false);

		PanelState state = controller.buildState();

		assertTrue(state.isReady());
		assertFalse(state.isInParty());
		assertTrue(state.getMembers().isEmpty());
		assertFalse(state.isPreviousPartyAvailable());
	}

	@Test
	public void reusesAndDisposesTheSettlementDashboardWindow() throws Exception
	{
		RecordingWindowFactory windows = new RecordingWindowFactory();
		UiController popoutController = new UiController(
			client, clientThread, partyService, itemManager, lootshareController, null, partyConfig, windows);
		Panel target = mock(Panel.class);
		when(lootshareController.isReady()).thenReturn(true);
		when(partyService.isInParty()).thenReturn(false);
		popoutController.start(target);
		SwingUtilities.invokeAndWait(() -> { });

		SwingUtilities.invokeAndWait(() -> {
			popoutController.openSettlementDashboard();
			popoutController.openSettlementDashboard();
		});
		assertEquals(1, windows.openCount);
		assertEquals(1, windows.window.focusCount);
		assertTrue(((SettlementDashboard) windows.content).isRefreshTimerRunning());

		popoutController.stop();
		SwingUtilities.invokeAndWait(() -> { });
		assertTrue(windows.window.disposed);
		assertFalse(((SettlementDashboard) windows.content).isRefreshTimerRunning());
	}

	@Test
	public void exposesAValidPreviousPartyOutsideAParty()
	{
		when(lootshareController.isReady()).thenReturn(true);
		when(partyService.isInParty()).thenReturn(false);
		when(partyConfig.previousPartyId()).thenReturn("previous-party");

		PanelState state = controller.buildState();

		assertTrue(state.isPreviousPartyAvailable());
	}

	@Test
	public void ignoresMissingOrInvalidPreviousPartyPassphrases()
	{
		controller.start(mock(Panel.class));
		when(partyService.isInParty()).thenReturn(false);

		when(partyConfig.previousPartyId()).thenReturn(null, "", "bad passphrase!");
		controller.joinPreviousParty();
		controller.joinPreviousParty();
		controller.joinPreviousParty();

		verify(partyService, never()).changeParty(any());
		controller.stop();
	}

	private static PartyMember member(long memberId, String name, boolean loggedIn)
	{
		PartyMember member = new PartyMember(memberId);
		member.setDisplayName(name);
		member.setLoggedIn(loggedIn);
		return member;
	}

	private static SharedLootEvent event(String proposalId, String recipient, SharedLootItem item)
	{
		return new SharedLootEvent(proposalId, recipient, "Boss", Instant.EPOCH,
			Collections.singletonList(item));
	}

	private static final class RecordingWindowFactory implements PopoutWindowFactory
	{
		private final RecordingWindow window = new RecordingWindow();
		private int openCount;
		private JComponent content;

		@Override
		public PopoutWindow open(String title, JComponent content, Dimension minimumSize, Dimension size,
		                         Runnable onClosed)
		{
			openCount++;
			this.content = content;
			return window;
		}
	}

	private static final class RecordingWindow implements PopoutWindow
	{
		private int focusCount;
		private boolean disposed;

		@Override
		public boolean isDisplayable()
		{
			return !disposed;
		}

		@Override
		public void focus()
		{
			focusCount++;
		}

		@Override
		public void dispose()
		{
			disposed = true;
		}
	}
}
