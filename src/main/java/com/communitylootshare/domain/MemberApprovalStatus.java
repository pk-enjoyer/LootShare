/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.domain;

/**
 * Host-controlled eligibility for future Community Lootshare captures.
 */
public enum MemberApprovalStatus
{
	PENDING("Pending"),
	APPROVED("Approved"),
	EXCLUDED("Excluded");

	private final String displayName;

	MemberApprovalStatus(String displayName)
	{
		this.displayName = displayName;
	}

	public String getDisplayName()
	{
		return displayName;
	}
}
