/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.ui.lootshare;

/**
 * User actions emitted by the Community Lootshare sidebar.
 */
public interface PanelActions
{
	void createParty();

	boolean joinParty(String passphrase);

	void joinPreviousParty();

	void leaveParty();

	default void setMemberApproved(long memberId, boolean approved)
	{
	}

}
