/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

import com.communitylootshare.debug.DebugSession.LootPreset;

/**
 * User actions emitted by the Community Lootshare sidebar.
 */
public interface PanelActions
{
	void createParty();

	boolean joinParty(String passphrase);

	void joinPreviousParty();

	void leaveParty();

	default void transferHost(long memberId)
	{
	}

	default void setMemberApproved(long memberId, boolean approved)
	{
	}

	default void openSettlementDashboard()
	{
	}

	default void addManualGp(long memberId, long amount)
	{
	}

	default void startDebugSimulation()
	{
	}

	default void stopDebugSimulation()
	{
	}

	default void resetDebugSimulation()
	{
	}

	default boolean addDebugPlayer(String displayName)
	{
		return false;
	}

	default boolean removeDebugPlayer(long memberId)
	{
		return false;
	}

	default boolean addDebugLoot(long ownerMemberId, long totalValue)
	{
		return false;
	}

	default boolean addDebugLoot(long ownerMemberId, LootPreset lootPreset, long totalValue)
	{
		return addDebugLoot(ownerMemberId, totalValue);
	}
}
