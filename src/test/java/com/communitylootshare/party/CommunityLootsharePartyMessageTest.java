/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.party;

import com.google.gson.Gson;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class CommunityLootsharePartyMessageTest
{
	private final Gson gson = new Gson();

	@Test
	public void proposalMessageRoundTripsImmutablePriceSnapshot()
	{
		LootProposal proposal = pending("p1");
		CommunityLootshareProposalMessage message = new CommunityLootshareProposalMessage(proposal);
		message.setMemberId(7L);

		assertEquals(CommunityLootshareProposalMessage.PROTOCOL_VERSION, message.getProtocolVersion());
		assertEquals("p1", message.getProposalId());
		assertEquals(1, message.getItems().size());
		CommunityLootshareProposalMessage.ItemPayload item = message.getItems().get(0);
		assertEquals(100, item.getItemId());
		assertEquals(101, item.getPricingId());
		assertEquals(2L, item.getQuantity());
		assertEquals(50L, item.getUnitPrice());

		LootProposal decoded = message.decode(99L).get();
		assertEquals(99L, decoded.getPartyId());
		assertEquals(7L, decoded.getOwnerMemberId());
		assertEquals(proposal.getEvent(), decoded.getEvent());
		try
		{
			message.getItems().clear();
			fail("Expected immutable payload view");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	@Test
	public void acceptedAndRejectedDecisionMessagesRoundTrip()
	{
		LootProposal accepted = pending("accepted").decide(LootProposalStatus.ACCEPTED,
			Instant.ofEpochSecond(2), Arrays.asList(
				new LootshareParticipant(7L, "Alice"), new LootshareParticipant(8L, "Bob")));
		CommunityLootshareDecisionMessage acceptedMessage = new CommunityLootshareDecisionMessage(accepted);
		acceptedMessage.setMemberId(7L);
		assertEquals(CommunityLootshareDecisionMessage.PROTOCOL_VERSION, acceptedMessage.getProtocolVersion());
		assertEquals("accepted", acceptedMessage.getProposalId());
		assertEquals("ACCEPTED", acceptedMessage.getDecision());
		assertEquals(2, acceptedMessage.getParticipants().size());
		assertEquals(7L, acceptedMessage.getParticipants().get(0).getMemberId());
		assertEquals("Alice", acceptedMessage.getParticipants().get(0).getDisplayName());

		CommunityLootshareDecisionMessage.DecodedDecision decoded = acceptedMessage.decode().get();
		assertEquals("accepted", decoded.getProposalId());
		assertEquals(LootProposalStatus.ACCEPTED, decoded.getStatus());
		assertEquals(Instant.ofEpochSecond(2), decoded.getDecidedAt());
		assertEquals(2, decoded.getParticipants().size());

		LootProposal rejected = pending("rejected").decide(LootProposalStatus.REJECTED,
			Instant.ofEpochSecond(3), Collections.emptyList());
		CommunityLootshareDecisionMessage rejectedMessage = new CommunityLootshareDecisionMessage(rejected);
		rejectedMessage.setMemberId(7L);
		assertEquals(LootProposalStatus.REJECTED, rejectedMessage.decode().get().getStatus());
		assertTrue(rejectedMessage.getParticipants().isEmpty());
		try
		{
			acceptedMessage.getParticipants().clear();
			fail("Expected immutable participant payload view");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	@Test
	public void proposalDecoderRejectsUnsupportedOrMalformedPayloads()
	{
		assertFalse(proposal("{\"protocolVersion\":2,\"items\":[]}", 1L).decode(1L).isPresent());
		assertFalse(proposal("{\"protocolVersion\":1,\"items\":null}", 1L).decode(1L).isPresent());
		assertFalse(proposal("{\"protocolVersion\":1,\"items\":[]}", 1L).decode(1L).isPresent());
		assertFalse(proposal(validProposalJson(), 0L).decode(1L).isPresent());
		assertFalse(proposal(validProposalJson(), 1L).decode(0L).isPresent());
		String tooManyItems = String.join(",", Collections.nCopies(SharedLootEvent.MAX_ITEMS + 1,
			"{\"itemId\":1,\"pricingId\":1,\"quantity\":1,\"unitPrice\":1}"));
		assertFalse(proposal("{\"protocolVersion\":1,\"items\":[" + tooManyItems + "]}", 1L)
			.decode(1L).isPresent());
		assertFalse(proposal("{\"protocolVersion\":1,\"proposalId\":\"p\",\"recipient\":\"Alice\","
			+ "\"sourceLabel\":\"Boss\",\"capturedAtEpochMilli\":0,\"items\":[null]}", 1L)
			.decode(1L).isPresent());
		assertFalse(proposal("{\"protocolVersion\":1,\"proposalId\":\"p\",\"recipient\":\"Alice\","
			+ "\"sourceLabel\":\"Boss\",\"capturedAtEpochMilli\":0,"
			+ "\"items\":[{\"itemId\":-1,\"pricingId\":1,\"quantity\":1,\"unitPrice\":1}]}", 1L)
			.decode(1L).isPresent());
		expectIllegal(() -> new CommunityLootshareProposalMessage(null));
	}

	@Test
	public void decisionDecoderRejectsUnsupportedOrMalformedPayloads()
	{
		assertFalse(decision("{\"protocolVersion\":2}", 1L).decode().isPresent());
		assertFalse(decision(validDecisionJson(), 0L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"\",\"decision\":\"REJECTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"PENDING\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"REJECTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[{\"memberId\":1,\"displayName\":\"Alice\"}]}", 1L)
			.decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[null]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":null}", 1L).decode().isPresent());
		String tooManyParticipants = String.join(",", Collections.nCopies(LootProposal.MAX_PARTICIPANTS + 1,
			"{\"memberId\":1,\"displayName\":\"Alice\"}"));
		assertFalse(decision("{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[" + tooManyParticipants + "]}", 1L)
			.decode().isPresent());
		expectIllegal(() -> new CommunityLootshareDecisionMessage(null));
		expectIllegal(() -> new CommunityLootshareDecisionMessage(pending("pending")));
	}

	private CommunityLootshareProposalMessage proposal(String json, long memberId)
	{
		CommunityLootshareProposalMessage message = gson.fromJson(json, CommunityLootshareProposalMessage.class);
		message.setMemberId(memberId);
		return message;
	}

	private CommunityLootshareDecisionMessage decision(String json, long memberId)
	{
		CommunityLootshareDecisionMessage message = gson.fromJson(json, CommunityLootshareDecisionMessage.class);
		message.setMemberId(memberId);
		return message;
	}

	private static String validProposalJson()
	{
		return "{\"protocolVersion\":1,\"proposalId\":\"p\",\"recipient\":\"Alice\","
			+ "\"sourceLabel\":\"Boss\",\"capturedAtEpochMilli\":0,"
			+ "\"items\":[{\"itemId\":1,\"pricingId\":1,\"quantity\":1,\"unitPrice\":1}]}";
	}

	private static String validDecisionJson()
	{
		return "{\"protocolVersion\":1,\"proposalId\":\"p\",\"decision\":\"REJECTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}";
	}

	private static LootProposal pending(String id)
	{
		SharedLootEvent event = new SharedLootEvent(id, "Alice", "Boss", Instant.ofEpochSecond(1),
			Collections.singletonList(new SharedLootItem(100, 101, 2, 50)));
		return LootProposal.pending(99L, 7L, event);
	}

	private static void expectIllegal(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected.
		}
	}
}
