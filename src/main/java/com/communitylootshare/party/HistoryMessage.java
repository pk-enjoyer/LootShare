/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import com.communitylootshare.domain.HostHistoryEvent;
import com.communitylootshare.domain.HostPeriod;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.party.messages.PartyMemberMessage;

/** Bounded independent chunks; receiver authenticates the reassembled immutable event. */
public final class HistoryMessage extends PartyMemberMessage
{
	public static final int PROTOCOL_VERSION = 1;
	public static final int CHUNK_CHARS = 4096;
	public static final int MAX_CHUNKS = 256;
	private int protocolVersion = PROTOCOL_VERSION;
	private long partyId;
	private long targetMemberId;
	private String eventId;
	private int index;
	private int count;
	private String data;
	private boolean live;

	public HistoryMessage() { }
	public static List<HistoryMessage> chunks(HostHistoryEvent event, long target, boolean live, Gson gson)
	{
		String json = gson.toJson(event.validatedCopy());
		int count = (json.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
		if (count <= 0 || count > MAX_CHUNKS || target < 0) { throw new IllegalArgumentException("History event too large"); }
		List<HistoryMessage> messages = new ArrayList<>();
		for (int index = 0; index < count; index++)
		{
			HistoryMessage message = new HistoryMessage();
			message.partyId = event.getPartyId(); message.targetMemberId = target; message.live = live;
			message.eventId = event.getEventId(); message.index = index; message.count = count;
			message.data = json.substring(index * CHUNK_CHARS, Math.min(json.length(), (index + 1) * CHUNK_CHARS));
			messages.add(message);
		}
		return messages;
	}
	public boolean isValid(long activeParty)
	{
		try { HostPeriod.validateId(eventId); }
		catch (RuntimeException e) { return false; }
		return protocolVersion == PROTOCOL_VERSION && getMemberId() > 0 && partyId == activeParty && targetMemberId >= 0
			&& count > 0 && count <= MAX_CHUNKS && index >= 0 && index < count && data != null
			&& !data.isEmpty() && data.length() <= CHUNK_CHARS;
	}
	public long getTargetMemberId() { return targetMemberId; }
	public String getEventId() { return eventId; }
	public int getIndex() { return index; }
	public int getCount() { return count; }
	public String getData() { return data; }
	public boolean isLive() { return live; }
}
