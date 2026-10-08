/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.util.Base64;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class HistoryCommitment
{
	private final long revision;
	private final String hash;
	public HistoryCommitment(long revision, String hash)
	{
		if (revision <= 0 || hash == null || hash.length() != 44)
		{
			throw new IllegalArgumentException("Invalid history commitment");
		}
		byte[] decoded = Base64.getDecoder().decode(hash);
		if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(hash))
		{
			throw new IllegalArgumentException("Invalid history digest");
		}
		this.revision = revision; this.hash = hash;
	}
	public HistoryCommitment validatedCopy() { return new HistoryCommitment(revision, hash); }
	public long getRevision() { return revision; }
	public String getHash() { return hash; }
}
