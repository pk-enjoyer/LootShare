/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.testing.RecordingUiInteractions;
import com.communitylootshare.ui.lootshare.PanelState.HostedSettings;
import com.communitylootshare.ui.lootshare.PanelState.LootItem;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import com.communitylootshare.ui.lootshare.PanelState.BalanceRow;
import com.communitylootshare.ui.lootshare.PanelState.SettlementState;
import com.communitylootshare.ui.lootshare.PanelState.TransferRow;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PanelTest
{
	private RecordingActions actions;
	private RecordingUiInteractions interactions;
	private Panel panel;

	@Before
	public void setUp() throws Exception
	{
		actions = new RecordingActions();
		interactions = new RecordingUiInteractions();
		ItemManager itemManager = mock(ItemManager.class);
		AsyncBufferedImage itemImage = new AsyncBufferedImage(mock(ClientThread.class), 36, 32,
			BufferedImage.TYPE_INT_ARGB);
		when(itemManager.getImage(anyInt(), anyInt(), anyBoolean())).thenReturn(itemImage);

		AtomicReference<Panel> created = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> created.set(new Panel(actions, interactions, itemManager)));
		panel = created.get();
	}

	@Test
	public void rendersPartyControlsAndRoutesCreateJoinLeaveAndCopy() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(true, false, null, Collections.emptyList()));
			assertEquals("Create party", panel.getPrimaryButton().getText());
			assertTrue(panel.getSecondaryButton().isVisible());
			assertTrue(panel.getPreviousPartyButton().isVisible());
			assertFalse(panel.getPreviousPartyButton().isEnabled());
			assertFalse(panel.getCopyButton().isVisible());
			panel.getPrimaryButton().doClick();
		});
		assertEquals(1, actions.createCount);

		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(
				true, false, null, Collections.emptyList(), true));
			assertTrue(panel.getPreviousPartyButton().isEnabled());
			panel.getPreviousPartyButton().doClick();
		});
		assertEquals(1, actions.joinPreviousCount);

		interactions.setPromptResult("bad passphrase!");
		actions.joinResult = false;
		SwingUtilities.invokeAndWait(() -> panel.getSecondaryButton().doClick());
		assertEquals("bad passphrase!", actions.lastPassphrase);
		assertNotNull(interactions.getLastMessage());

		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(true, true, "four-word-party-pass",
				Collections.emptyList()));
			assertEquals("Leave", panel.getPrimaryButton().getText());
			assertFalse(panel.getSecondaryButton().isVisible());
			assertFalse(panel.getPreviousPartyButton().isVisible());
			assertTrue(panel.getCopyButton().isVisible());
			assertEquals(new Insets(3, 8, 3, 8), panel.getPrimaryButton().getMargin());
			assertEquals(panel.getPrimaryButton().getMargin(), panel.getCopyButton().getMargin());
			panel.getCopyButton().doClick();
		});
		assertEquals("four-word-party-pass", interactions.getClipboardText());

		interactions.setConfirmResult(false);
		SwingUtilities.invokeAndWait(() -> panel.getPrimaryButton().doClick());
		assertEquals(0, actions.leaveCount);
		interactions.setConfirmResult(true);
		SwingUtilities.invokeAndWait(() -> panel.getPrimaryButton().doClick());
		assertEquals(1, actions.leaveCount);
	}

	@Test
	public void rendersCollapsibleMemberLootGridAndPreservesExpansion() throws Exception
	{
		MemberLoot member = new MemberLoot(1L, "Alice", true, true, null,
			2, 1, 1_250_000L,
			Arrays.asList(
				new LootItem(4151, "Abyssal whip", 1L, 1_200_000L),
				new LootItem(995, "Coins", 50_000L, 50_000L)));
		PanelState state = new PanelState(
			true, true, "party-pass", Collections.singletonList(member));

		SwingUtilities.invokeAndWait(() -> {
			panel.render(state);
			Panel.MemberCard card = panel.getMemberCard(1L);
			assertNotNull(card);
			assertFalse(card.isExpanded());
			assertEquals(2, card.getLootItemCount());
			panel.setExpanded(1L, true);
			assertTrue(card.isExpanded());

			panel.render(state);
			assertTrue(panel.getMemberCard(1L).isExpanded());
		});
	}

	@Test
	public void rendersTheHostsEffectivePolicyInACollapsibleSection() throws Exception
	{
		LootshareSettings settings = new LootshareSettings(250_000L, LootValueBasis.HIGH_ALCHEMY,
			true, false, true, false, true, true);
		HostedSettings hostedSettings = HostedSettings.available(1L, "Alice", true, settings);
		PanelState state = new PanelState(true, true, "party-pass",
			Collections.emptyList(), false, hostedSettings);

		SwingUtilities.invokeAndWait(() -> {
			panel.render(state);
			assertTrue(panel.getHostedSettingsSection().isVisible());
			assertFalse(panel.getHostedSettingsBody().isVisible());
			assertTrue(panel.getHostedSettingsToggle().getText().contains("Alice (you)"));
			panel.getHostedSettingsToggle().doClick();
			assertTrue(panel.getHostedSettingsBody().isVisible());
			assertEquals(6, panel.getHostedSettingsBody().getComponentCount());
			assertTrue(containsText(panel.getHostedSettingsBody(), "250,000 gp"));
			assertFalse(containsText(panel.getHostedSettingsBody(), "High alchemy"));
			assertTrue(containsText(panel.getHostedSettingsBody(), "Excluded"));

			panel.render(new PanelState(true, false, null, Collections.emptyList()));
			assertFalse(panel.getHostedSettingsSection().isVisible());
		});
	}

	@Test
	public void togglesMemberLootWhenClickingAHeaderChild() throws Exception
	{
		MemberLoot member = new MemberLoot(1L, "Alice", true, true, null,
			1, 0, 1_200_000L,
			Collections.singletonList(new LootItem(4151, "Abyssal whip", 1L, 1_200_000L)));

		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(
				true, true, "party-pass", Collections.singletonList(member)));
			Panel.MemberCard card = panel.getMemberCard(1L);
			assertFalse(card.isExpanded());

			Component child = findNestedChild(card.getHeader());
			child.dispatchEvent(new MouseEvent(child, MouseEvent.MOUSE_CLICKED,
				System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1));
			assertTrue(card.isExpanded());
		});
	}

	@Test
	public void doesNotExposeManualHostTransferControls() throws Exception
	{
		MemberLoot host = new MemberLoot(1L, "Alice", true, true, true, null,
			0, 0, 0L, Collections.emptyList());
		MemberLoot target = new MemberLoot(2L, "Bob", false, false, false, null,
			0, 0, 0L, Collections.emptyList());

		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(
				true, true, "party-pass", Arrays.asList(host, target)));
			assertNull(panel.getMemberCard(1L).getHeader().getComponentPopupMenu());
			assertNull(panel.getMemberCard(2L).getHeader().getComponentPopupMenu());
		});
	}

	@Test
	public void localHostCanToggleMemberEligibilityWithoutExpandingTheLootCard() throws Exception
	{
		MemberLoot host = new MemberLoot(1L, "Alice", true, true, true, null,
			0, 0, 0L, Collections.emptyList(), MemberApprovalStatus.APPROVED);
		MemberLoot pending = new MemberLoot(2L, "Bob", false, false, true, null,
			0, 0, 0L, Collections.emptyList(), MemberApprovalStatus.PENDING);

		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(
				true, true, "party-pass", Arrays.asList(host, pending)));
			Panel.MemberCard card = panel.getMemberCard(2L);
			assertEquals("Pending", card.getApprovalStatusLabel().getText());
			assertEquals("Approve", card.getApprovalActionButton().getText());
			card.getApprovalActionButton().doClick();
			assertFalse(card.isExpanded());
		});
		assertEquals(2L, actions.approvalMemberId);
		assertTrue(actions.approved);

		MemberLoot approved = new MemberLoot(2L, "Bob", false, false, true, null,
			0, 0, 0L, Collections.emptyList(), MemberApprovalStatus.APPROVED);
		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(
				true, true, "party-pass", Arrays.asList(host, approved)));
			assertEquals("Exclude", panel.getMemberCard(2L).getApprovalActionButton().getText());
			panel.getMemberCard(2L).getApprovalActionButton().doClick();
		});
		assertFalse(actions.approved);
	}

	@Test
	public void showsLoadingAndEmptyMemberStates() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			panel.render(new PanelState(false, false, null, Collections.emptyList()));
			assertFalse(panel.getPrimaryButton().isEnabled());
			assertEquals("Loading profile...", panel.getConnectionStatus().getText());

			panel.render(new PanelState(true, true, null, Collections.emptyList()));
			assertTrue(panel.getPrimaryButton().isEnabled());
			assertEquals("Connected to RuneLite Party.", panel.getConnectionStatus().getText());
		});
	}

	private static Component findNestedChild(Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if (!(child instanceof Container) || ((Container) child).getComponentCount() == 0)
			{
				return child;
			}
			Component nested = findNestedChild((Container) child);
			if (nested != null)
			{
				return nested;
			}
		}
		throw new AssertionError("Member header has no nested child");
	}

	private static boolean containsText(Container parent, String expected)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof javax.swing.JLabel
				&& expected.equals(((javax.swing.JLabel) child).getText()))
			{
				return true;
			}
			if (child instanceof Container && containsText((Container) child, expected))
			{
				return true;
			}
		}
		return false;
	}

	private static final class RecordingActions implements PanelActions
	{
		private int createCount;
		private int joinPreviousCount;
		private int leaveCount;
		private String lastPassphrase;
		private boolean joinResult = true;
		private long approvalMemberId;
		private boolean approved;

		@Override
		public void createParty()
		{
			createCount++;
		}

		@Override
		public boolean joinParty(String passphrase)
		{
			lastPassphrase = passphrase;
			return joinResult;
		}

		@Override
		public void joinPreviousParty()
		{
			joinPreviousCount++;
		}

		@Override
		public void leaveParty()
		{
			leaveCount++;
		}

		@Override
		public void setMemberApproved(long memberId, boolean approved)
		{
			approvalMemberId = memberId;
			this.approved = approved;
		}

	}
}
