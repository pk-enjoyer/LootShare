/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.debug.DebugSession.LootPreset;
import com.communitylootshare.ui.lootshare.PanelState.DebugState;
import com.communitylootshare.ui.lootshare.PanelState.MemberLoot;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class DebugPanelTest
{
	private RecordingActions actions;
	private DebugPanel panel;

	@Before
	public void setUp() throws Exception
	{
		actions = new RecordingActions();
		AtomicReference<DebugPanel> created = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> created.set(new DebugPanel(actions)));
		panel = created.get();
	}

	@Test
	public void isHiddenOutsideDeveloperModeAndCanStartASimulation() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			panel.render(state(DebugState.unavailable(), Collections.emptyList()));
			assertFalse(panel.isVisible());

			panel.render(state(new DebugState(true, false, 0L), Collections.emptyList()));
			assertTrue(panel.isVisible());
			assertFalse(panel.getSimulationControls().isVisible());
			assertEquals("Start simulation", panel.getSimulationButton().getText());
			panel.getSimulationButton().doClick();
		});
		assertEquals(1, actions.startCount);
	}

	@Test
	public void routesFakePlayerLootResetAndExitActions() throws Exception
	{
		MemberLoot owner = member(10L, "Debug owner", true);
		MemberLoot alice = member(11L, "Alice", false);
		EnumMap<LootPreset, Long> prices = new EnumMap<>(LootPreset.class);
		prices.put(LootPreset.MASORI_BODY, 43_636_445L);
		SwingUtilities.invokeAndWait(() -> {
			panel.render(state(new DebugState(true, true, 10L, prices), Arrays.asList(owner, alice)));
			assertTrue(panel.getSimulationControls().isVisible());
			assertEquals("Return to live Party", panel.getSimulationButton().getText());
			assertEquals(1, panel.getRemovablePlayers().getItemCount());
			assertEquals(2, panel.getLootOwners().getItemCount());
			assertEquals(LootPreset.values().length, panel.getLootPresets().getItemCount());
			assertTrue(panel.getLootValueControl().isVisible());
			assertFalse(panel.getItemPriceControl().isVisible());

			panel.getPlayerNameField().setText("Bob");
			panel.getAddPlayerButton().doClick();
			assertTrue(panel.getFeedback().getText().contains("added"));

			panel.getRemovePlayerButton().doClick();
			panel.getLootPresets().setSelectedItem(LootPreset.MASORI_BODY);
			assertFalse(panel.getLootValueControl().isVisible());
			assertTrue(panel.getItemPriceControl().isVisible());
			assertEquals("43,636,445 gp", panel.getItemPriceValue().getText());
			panel.getLootOwners().setSelectedIndex(1);
			panel.getLootValue().setValue(750_000L);
			panel.getAddLootButton().doClick();
			assertTrue(panel.getFeedback().getText().contains("Masori body"));
			panel.getResetButton().doClick();
			panel.getSimulationButton().doClick();
		});

		assertEquals("Bob", actions.addedName);
		assertEquals(11L, actions.removedMemberId);
		assertEquals(11L, actions.lootOwnerMemberId);
		assertEquals(LootPreset.MASORI_BODY, actions.lootPreset);
		assertEquals(43_636_445L, actions.lootValue);
		assertEquals(1, actions.resetCount);
		assertEquals(1, actions.stopCount);
	}

	@Test
	public void showsValidationFeedbackWhenAnActionIsRejected() throws Exception
	{
		actions.actionResult = false;
		SwingUtilities.invokeAndWait(() -> {
			panel.render(state(new DebugState(true, true, 10L),
				Collections.singletonList(member(10L, "Debug owner", true))));
			panel.getPlayerNameField().setText("Debug owner");
			panel.getAddPlayerButton().doClick();
			assertTrue(panel.getFeedback().getText().contains("unique"));
			assertFalse(panel.getRemovePlayerButton().isEnabled());
		});
	}

	@Test
	public void disablesItemLootWhenRuneLiteHasNoPrice() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			panel.render(state(new DebugState(true, true, 10L),
				Collections.singletonList(member(10L, "Debug owner", true))));
			panel.getLootPresets().setSelectedItem(LootPreset.LIGHTBEARER);

			assertEquals("Price unavailable", panel.getItemPriceValue().getText());
			assertFalse(panel.getAddLootButton().isEnabled());

			panel.getLootPresets().setSelectedItem(LootPreset.COINS);
			assertTrue(panel.getLootValueControl().isVisible());
			assertTrue(panel.getAddLootButton().isEnabled());
		});
	}

	private static PanelState state(DebugState debug, java.util.List<MemberLoot> members)
	{
		return new PanelState(true, debug.isSimulationActive(), null, members, debug);
	}

	private static MemberLoot member(long memberId, String name, boolean local)
	{
		return new MemberLoot(memberId, name, local, true, null,
			0, 0, 0L, Collections.emptyList());
	}

	private static final class RecordingActions implements PanelActions
	{
		private int startCount;
		private int stopCount;
		private int resetCount;
		private String addedName;
		private long removedMemberId;
		private long lootOwnerMemberId;
		private LootPreset lootPreset;
		private long lootValue;
		private boolean actionResult = true;

		@Override
		public void createParty()
		{
		}

		@Override
		public boolean joinParty(String passphrase)
		{
			return false;
		}

		@Override
		public void joinPreviousParty()
		{
		}

		@Override
		public void leaveParty()
		{
		}

		@Override
		public void startDebugSimulation()
		{
			startCount++;
		}

		@Override
		public void stopDebugSimulation()
		{
			stopCount++;
		}

		@Override
		public void resetDebugSimulation()
		{
			resetCount++;
		}

		@Override
		public boolean addDebugPlayer(String displayName)
		{
			addedName = displayName;
			return actionResult;
		}

		@Override
		public boolean removeDebugPlayer(long memberId)
		{
			removedMemberId = memberId;
			return actionResult;
		}

		@Override
		public boolean addDebugLoot(long ownerMemberId, LootPreset selectedLootPreset, long totalValue)
		{
			lootOwnerMemberId = ownerMemberId;
			lootPreset = selectedLootPreset;
			lootValue = totalValue;
			return actionResult;
		}
	}
}
