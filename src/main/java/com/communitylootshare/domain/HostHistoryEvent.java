/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/** Signed immutable audit event. A commitment authenticates the entire event, not its former host alone. */
public final class HostHistoryEvent
{
	public enum Kind { PERIOD_CLOSED, SETTINGS_APPLIED }
	public enum Reason { TRANSFER, DEPARTURE, LOCAL_LEAVE, SETTINGS }
	private final String eventId;
	private final long partyId;
	private final long revision;
	private final HostPeriod period;
	private final Instant occurredAt;
	private final Kind kind;
	private final Reason reason;
	private final LootshareSettings oldSettings;
	private final LootshareSettings newSettings;
	private final SettlementSummary before;
	private final SettlementSummary after;
	private final boolean complete;
	private final LootDecisionReceipt receipt;

	public HostHistoryEvent(String eventId, long partyId, long revision, HostPeriod period, Instant occurredAt,
	                        Kind kind, Reason reason, LootshareSettings oldSettings, LootshareSettings newSettings,
	                        SettlementSummary before, SettlementSummary after, boolean complete, LootDecisionReceipt receipt)
	{
		HostPeriod.validateId(eventId);
		if (partyId <= 0 || revision <= 0 || period == null || occurredAt == null || kind == null || reason == null
			|| oldSettings == null || newSettings == null || before == null || after == null
			|| occurredAt.isBefore(period.getStartedAt())
			|| (kind == Kind.PERIOD_CLOSED && (period.getEndedAt() == null || !occurredAt.equals(period.getEndedAt())
				|| reason == Reason.SETTINGS || !oldSettings.equals(newSettings) || !before.equals(after)))
			|| (kind == Kind.SETTINGS_APPLIED && (reason != Reason.SETTINGS || oldSettings.equals(newSettings)
				|| period.getEndedAt() != null)))
		{
			throw new IllegalArgumentException("Invalid history event");
		}
		this.eventId = eventId;
		this.partyId = partyId;
		this.revision = revision;
		this.period = period.validatedCopy();
		this.occurredAt = Instant.ofEpochMilli(occurredAt.toEpochMilli());
		this.kind = kind;
		this.reason = reason;
		this.oldSettings = oldSettings.validatedCopy();
		this.newSettings = newSettings.validatedCopy();
		this.before = before.validatedCopy();
		this.after = after.validatedCopy();
		this.complete = complete;
		this.receipt = receipt == null ? null : receipt.validatedCopy();
	}
	public HostHistoryEvent validatedCopy()
	{
		return new HostHistoryEvent(eventId, partyId, revision, period, occurredAt, kind, reason,
			oldSettings, newSettings, before, after, complete, receipt);
	}
	public HostHistoryEvent sign(long memberId, KeyPair key) throws GeneralSecurityException
	{
		return new HostHistoryEvent(eventId, partyId, revision, period, occurredAt, kind, reason,
			oldSettings, newSettings, before, after, complete, LootDecisionReceipt.signBytes(memberId, key, payload()));
	}
	public boolean verifies() { return receipt != null && receipt.verifiesBytes(payload()); }
	public String commitment()
	{
		try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload())); }
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	private byte[] payload()
	{
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeUTF("community-lootshare-history-v1");
			out.writeUTF(eventId); out.writeLong(partyId); out.writeLong(revision);
			out.writeUTF(period.getPeriodId()); out.writeLong(period.getHostMemberId()); out.writeUTF(period.getHostDisplayName());
			out.writeUTF(period.getPredecessorId() == null ? "" : period.getPredecessorId());
			out.writeLong(period.getStartedAt().toEpochMilli());
			out.writeBoolean(period.getEndedAt() != null);
			if (period.getEndedAt() != null) { out.writeLong(period.getEndedAt().toEpochMilli()); }
			out.writeLong(occurredAt.toEpochMilli()); out.writeUTF(kind.name()); out.writeUTF(reason.name());
			writeSettings(out, oldSettings); writeSettings(out, newSettings);
			writeSummary(out, before); writeSummary(out, after); out.writeBoolean(complete);
			out.flush(); return bytes.toByteArray();
		}
		catch (IOException e) { throw new IllegalStateException(e); }
	}
	private static void writeSettings(DataOutputStream out, LootshareSettings s) throws IOException
	{
		out.writeLong(s.getMinimumSharedLootValue()); out.writeUTF(s.getLootValueBasis().name());
		out.writeBoolean(s.isCaptureNpcLoot()); out.writeBoolean(s.isCaptureEventLoot()); out.writeBoolean(s.isCapturePlayerLoot());
		out.writeBoolean(s.isCapturePickpocketLoot()); out.writeBoolean(s.isCaptureUnknownLoot());
		out.writeBoolean(s.isIncludeLoggedOutMembers()); out.writeBoolean(s.isAllowMemberManualGp());
	}
	private static void writeSummary(DataOutputStream out, SettlementSummary s) throws IOException
	{
		out.writeLong(s.getTotal()); out.writeInt(s.getBalances().size());
		for (LootshareCalculation.Balance b : s.getBalances())
		{
			out.writeLong(b.getMemberId()); out.writeUTF(b.getDisplayName());
			out.writeLong(b.getReceivedValue()); out.writeLong(b.getEntitledValue());
		}
		out.writeInt(s.getTransfers().size());
		for (LootshareCalculation.Transfer t : s.getTransfers())
		{
			out.writeLong(t.getFromMemberId()); out.writeUTF(t.getFromDisplayName());
			out.writeLong(t.getToMemberId()); out.writeUTF(t.getToDisplayName()); out.writeLong(t.getAmount());
		}
		out.writeInt(s.getProposalIds().size());
		for (String id : s.getProposalIds()) { out.writeUTF(id); }
	}
	public String getEventId() { return eventId; }
	public long getPartyId() { return partyId; }
	public long getRevision() { return revision; }
	public HostPeriod getPeriod() { return period; }
	public Instant getOccurredAt() { return occurredAt; }
	public Kind getKind() { return kind; }
	public Reason getReason() { return reason; }
	public LootshareSettings getOldSettings() { return oldSettings; }
	public LootshareSettings getNewSettings() { return newSettings; }
	public SettlementSummary getBefore() { return before; }
	public SettlementSummary getAfter() { return after; }
	public boolean isComplete() { return complete; }
	public LootDecisionReceipt getReceipt() { return receipt; }
}
