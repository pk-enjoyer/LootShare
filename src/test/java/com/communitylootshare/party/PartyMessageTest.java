/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.party;

import com.google.gson.Gson;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class PartyMessageTest
{
	private final Gson gson = new Gson();

	@Test
	public void memberIdentityMessageAcceptsOnlyBoundedResolvedNames()
	{
		MemberIdentityMessage message = new MemberIdentityMessage("Alice");
		message.setMemberId(7L);
		assertEquals(MemberIdentityMessage.PROTOCOL_VERSION, message.getProtocolVersion());
		assertEquals("Alice", message.decode().get());

		MemberIdentityMessage unknown = gson.fromJson("{\"protocolVersion\":1,\"displayName\":\"<unknown>\"}",
			MemberIdentityMessage.class);
		unknown.setMemberId(7L);
		assertFalse(unknown.decode().isPresent());
		MemberIdentityMessage blank = gson.fromJson("{\"protocolVersion\":1,\"displayName\":\"  \"}",
			MemberIdentityMessage.class);
		blank.setMemberId(7L);
		assertFalse(blank.decode().isPresent());
		MemberIdentityMessage unsupported = gson.fromJson("{\"protocolVersion\":2,\"displayName\":\"Alice\"}",
			MemberIdentityMessage.class);
		unsupported.setMemberId(7L);
		assertFalse(unsupported.decode().isPresent());
		expectIllegal(() -> new MemberIdentityMessage("<unknown>"));
	}

	@Test
	public void proposalMessageRoundTripsImmutablePriceSnapshot()
	{
		LootProposal proposal = pending("p1");
		ProposalMessage message = new ProposalMessage(proposal);
		message.setMemberId(7L);

		assertEquals(ProposalMessage.PROTOCOL_VERSION, message.getProtocolVersion());
		assertEquals("p1", message.getProposalId());
		assertEquals(1, message.getItems().size());
		ProposalMessage.ItemPayload item = message.getItems().get(0);
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
		DecisionMessage acceptedMessage = new DecisionMessage(accepted);
		acceptedMessage.setMemberId(7L);
		assertEquals(DecisionMessage.PROTOCOL_VERSION, acceptedMessage.getProtocolVersion());
		assertEquals("accepted", acceptedMessage.getProposalId());
		assertEquals("ACCEPTED", acceptedMessage.getDecision());
		assertEquals(2, acceptedMessage.getParticipants().size());
		assertEquals(7L, acceptedMessage.getParticipants().get(0).getMemberId());
		assertEquals("Alice", acceptedMessage.getParticipants().get(0).getDisplayName());

		DecisionMessage.DecodedDecision decoded = acceptedMessage.decode().get();
		assertEquals("accepted", decoded.getProposalId());
		assertEquals(LootProposalStatus.ACCEPTED, decoded.getStatus());
		assertEquals(Instant.ofEpochSecond(2), decoded.getDecidedAt());
		assertEquals(2, decoded.getParticipants().size());

		LootProposal rejected = pending("rejected").decide(LootProposalStatus.REJECTED,
			Instant.ofEpochSecond(3), Collections.emptyList());
		DecisionMessage rejectedMessage = new DecisionMessage(rejected);
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
	public void hostMessageRoundTripsInitialSettingsAndTransfers()
	{
		LootshareSettings settings = new LootshareSettings(100_000L, LootValueBasis.HIGH_ALCHEMY,
			true, false, true, false, true, true);
		HostMessage initial = new HostMessage(7L, settings, 1L);
		initial.setMemberId(7L);
		assertEquals(HostMessage.PROTOCOL_VERSION, initial.getProtocolVersion());
		assertEquals(7L, initial.getHostMemberId());
		assertEquals(100_000L, initial.getMinimumSharedLootValue());
		assertEquals("HIGH_ALCHEMY", initial.getLootValueBasis());
		assertEquals(1L, initial.getRevision());
		HostMessage.DecodedHostState initialState = initial.decode().get();
		assertEquals(7L, initialState.getHostMemberId());
		assertEquals(100_000L, initialState.getMinimumSharedLootValue());
		assertEquals(settings, initialState.getSettings());
		assertFalse(initialState.getSettings().isAllowMemberManualGp());
		assertEquals(1L, initialState.getRevision());
		assertEquals(MemberApprovalStatus.APPROVED, initialState.getApprovalStatuses().get(7L));

		Map<Long, MemberApprovalStatus> approvals = new LinkedHashMap<>();
		approvals.put(7L, MemberApprovalStatus.APPROVED);
		approvals.put(8L, MemberApprovalStatus.EXCLUDED);
		approvals.put(9L, MemberApprovalStatus.PENDING);
		HostMessage withApprovals =
			new HostMessage(7L, settings, 2L, approvals);
		withApprovals.setMemberId(7L);
		assertEquals(MemberApprovalStatus.EXCLUDED,
			withApprovals.decode().get().getApprovalStatuses().get(8L));
		assertFalse(withApprovals.decode().get().getApprovalStatuses().containsKey(9L));

		HostMessage transfer = new HostMessage(8L, 100_000L, 2L);
		transfer.setMemberId(7L);
		assertEquals(8L, transfer.decode().get().getHostMemberId());
	}

	@Test
	public void hostDecoderRejectsUnsupportedOrMalformedPayloads()
	{
		assertFalse(host(validHostJson().replace("\"protocolVersion\":3", "\"protocolVersion\":2"), 1L)
			.decode().isPresent());
		assertFalse(host(validHostJson(), 0L).decode().isPresent());
		assertFalse(host(validHostJson().replace("\"hostMemberId\":1", "\"hostMemberId\":0"), 1L)
			.decode().isPresent());
		assertFalse(host(validHostJson().replace("\"minimumSharedLootValue\":100000",
			"\"minimumSharedLootValue\":-1"), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace("\"minimumSharedLootValue\":100000",
			"\"minimumSharedLootValue\":2147483648"), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace("\"revision\":1", "\"revision\":0"), 1L)
			.decode().isPresent());
		assertFalse(host(validHostJson().replace("GRAND_EXCHANGE", "NOT_A_PRICE_BASIS"), 1L)
			.decode().isPresent());
		assertFalse(host(validHostJson().replace("\"lootValueBasis\":\"GRAND_EXCHANGE\"",
			"\"lootValueBasis\":null"), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace("\"captureUnknownLoot\":true,", ""), 1L)
			.decode().isPresent());
		assertFalse(host(validHostJson().replace("\"captureNpcLoot\":true",
			"\"captureNpcLoot\":null"), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace(
			"\"memberApprovals\":[{\"memberId\":1,\"status\":\"APPROVED\"}],", ""), 1L)
			.decode().isPresent());
		assertFalse(host(validHostJson().replace(
			"\"memberApprovals\":[{\"memberId\":1,\"status\":\"APPROVED\"}]",
			"\"memberApprovals\":null"), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace("\"status\":\"APPROVED\"",
			"\"status\":\"EXCLUDED\""), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace("\"status\":\"APPROVED\"",
			"\"status\":\"NOT_A_STATUS\""), 1L).decode().isPresent());
		assertFalse(host(validHostJson().replace(
			"{\"memberId\":1,\"status\":\"APPROVED\"}",
			"{\"memberId\":1,\"status\":\"APPROVED\"},{\"memberId\":1,\"status\":\"APPROVED\"}"),
			1L).decode().isPresent());
		expectIllegal(() -> new HostMessage(0L, 0L, 1L));
		expectIllegal(() -> new HostMessage(1L, (LootshareSettings) null, 1L));
		expectIllegal(() -> new HostMessage(1L, LootshareSettings.defaults(), 1L,
			Collections.singletonMap(1L, MemberApprovalStatus.EXCLUDED)));
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
		expectIllegal(() -> new ProposalMessage(null));
	}

	@Test
	public void decisionDecoderRejectsUnsupportedOrMalformedPayloads()
	{
		assertFalse(decision(validDecisionJson().replace("\"protocolVersion\":2",
			"\"protocolVersion\":1"), 1L).decode().isPresent());
		assertFalse(decision(validDecisionJson(), 0L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"\",\"decision\":\"REJECTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"PENDING\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"REJECTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[{\"memberId\":1,\"displayName\":\"Alice\"}]}", 1L)
			.decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[null]}", 1L).decode().isPresent());
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":null}", 1L).decode().isPresent());
		String tooManyParticipants = String.join(",", Collections.nCopies(LootProposal.MAX_PARTICIPANTS + 1,
			"{\"memberId\":1,\"displayName\":\"Alice\"}"));
		assertFalse(decision("{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"ACCEPTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[" + tooManyParticipants + "]}", 1L)
			.decode().isPresent());
		expectIllegal(() -> new DecisionMessage(null));
		expectIllegal(() -> new DecisionMessage(pending("pending")));
	}

	private ProposalMessage proposal(String json, long memberId)
	{
		ProposalMessage message = gson.fromJson(json, ProposalMessage.class);
		message.setMemberId(memberId);
		return message;
	}

	private DecisionMessage decision(String json, long memberId)
	{
		DecisionMessage message = gson.fromJson(json, DecisionMessage.class);
		message.setMemberId(memberId);
		return message;
	}

	private HostMessage host(String json, long memberId)
	{
		HostMessage message = gson.fromJson(json, HostMessage.class);
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
		return "{\"protocolVersion\":2,\"proposalId\":\"p\",\"decision\":\"REJECTED\","
			+ "\"decidedAtEpochMilli\":0,\"participants\":[]}";
	}

	private static String validHostJson()
	{
		return "{\"protocolVersion\":3,\"hostMemberId\":1,"
			+ "\"minimumSharedLootValue\":100000,\"lootValueBasis\":\"GRAND_EXCHANGE\","
			+ "\"captureNpcLoot\":true,\"captureEventLoot\":true,\"capturePlayerLoot\":true,"
			+ "\"capturePickpocketLoot\":true,\"captureUnknownLoot\":true,"
			+ "\"includeLoggedOutMembers\":false,"
			+ "\"memberApprovals\":[{\"memberId\":1,\"status\":\"APPROVED\"}],\"revision\":1}";
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
