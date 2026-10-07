/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.party;

import com.communitylootshare.domain.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryMessageTest
{
	private final Gson gson = new Gson();

	@Test public void roundTripsSignedFinalizedHistoryWithItsFrozenRoster() throws Exception
	{
		LootProposal original = finalized();
		KeyPair key = LootDecisionReceipt.generateKey();
		LootProposal signed = original.withDecisionReceipt(LootDecisionReceipt.sign(1L, key, original));
		RecoveryMessage sent = new RecoveryMessage(2L, signed);
		RecoveryMessage received = gson.fromJson(gson.toJson(sent), RecoveryMessage.class);
		received.setMemberId(3L);
		LootProposal restored = received.decode(77L).get();
		assertEquals(original.getParticipants(), restored.getParticipants());
		assertEquals(original.getEvent().getTotal(), restored.getEvent().getTotal());
		assertTrue(restored.getDecisionReceipt().verifies(restored));
		assertFalse(received.isComplete());
		RecoveryMessage complete = RecoveryMessage.completed(2L);
		complete.setMemberId(3L);
		assertTrue(complete.isComplete());
		assertFalse(complete.decode(77L).isPresent());
		assertEquals(2L, complete.getTargetHostMemberId());
	}

	@Test public void rejectsMalformedMismatchedAndNonFinalPayloads()
	{
		JsonObject valid = gson.toJsonTree(new RecoveryMessage(2L, finalized())).getAsJsonObject();
		String[] invalid = {
			"{}",
			valid.toString().replace("\"protocolVersion\":1", "\"protocolVersion\":99"),
			valid.toString().replace("\"targetHostMemberId\":2", "\"targetHostMemberId\":0"),
			valid.toString().replace("\"decision\":\"ACCEPTED\"", "\"decision\":\"PENDING\""),
			valid.toString().replace("\"decidedAtEpochMilli\":2000", "\"decidedAtEpochMilli\":0")
		};
		for (String json : invalid)
		{
			RecoveryMessage decoded = gson.fromJson(json, RecoveryMessage.class);
			decoded.setMemberId(3L);
			assertFalse(decoded.decode(77L).isPresent());
		}
		valid.getAsJsonObject("decision").addProperty("proposalId", "other");
		RecoveryMessage mismatch = gson.fromJson(valid, RecoveryMessage.class);
		mismatch.setMemberId(3L);
		assertFalse(mismatch.decode(77L).isPresent());
		try { new RecoveryMessage(0L, finalized()); fail("Invalid host"); }
		catch (IllegalArgumentException expected) { }
		try { RecoveryMessage.completed(0L); fail("Invalid host"); }
		catch (IllegalArgumentException expected) { }
	}

	@Test public void hostSnapshotsAuthenticateDecisionKeysAndStillDecodeLegacyPolicies() throws Exception
	{
		String publicKey = LootDecisionReceipt.publicKey(LootDecisionReceipt.generateKey());
		HostMessage host = new HostMessage(1L, LootshareSettings.defaults(100L), 1L,
			Collections.singletonMap(1L, MemberApprovalStatus.APPROVED), Collections.singletonMap(publicKey, 1L));
		host.setMemberId(1L);
		assertEquals(Long.valueOf(1L), host.decode().get().getDecisionKeys().get(publicKey));
		JsonObject json = gson.toJsonTree(host).getAsJsonObject();
		json.addProperty("protocolVersion", 4);
		HostMessage legacy = gson.fromJson(json, HostMessage.class);
		legacy.setMemberId(1L);
		assertTrue(legacy.decode().get().getDecisionKeys().isEmpty());
		json.addProperty("protocolVersion", HostMessage.PROTOCOL_VERSION);
		json.getAsJsonObject("decisionKeys").addProperty(publicKey, -1L);
		HostMessage invalid = gson.fromJson(json, HostMessage.class);
		invalid.setMemberId(1L);
		assertFalse(invalid.decode().isPresent());
	}

	@Test public void hostSnapshotsCarryExactHistoricalCommitmentsAndRejectMalformedOnes() throws Exception
	{
		LootProposal original = finalized();
		String commitment = LootDecisionReceipt.decisionId(original);
		HostMessage sent = new HostMessage(2L, LootshareSettings.defaults(100L), 2L,
			Collections.singletonMap(2L, MemberApprovalStatus.APPROVED), Collections.emptyMap(),
			Collections.singleton(commitment));
		HostMessage received = gson.fromJson(gson.toJson(sent), HostMessage.class);
		received.setMemberId(1L);
		assertEquals(Collections.singleton(commitment), received.decode().get().getDecisionAuthorizations());
		for (String invalid : java.util.Arrays.asList("bad!", null))
		{
			JsonObject json = gson.toJsonTree(sent).getAsJsonObject();
			com.google.gson.JsonArray authorizations = new com.google.gson.JsonArray();
			authorizations.add(invalid);
			json.add("decisionAuthorizations", authorizations);
			HostMessage malformed = gson.fromJson(json, HostMessage.class);
			malformed.setMemberId(1L);
			assertFalse(malformed.decode().isPresent());
		}
		JsonObject legacyJson = gson.toJsonTree(sent).getAsJsonObject();
		legacyJson.addProperty("protocolVersion", 5);
		HostMessage legacy = gson.fromJson(legacyJson, HostMessage.class);
		legacy.setMemberId(1L);
		assertTrue(legacy.decode().get().getDecisionAuthorizations().isEmpty());
		LootProposal altered = LootProposal.pending(77L, 1L, original.getEvent())
			.decide(LootProposalStatus.ACCEPTED, original.getDecidedAt(), java.util.Arrays.asList(
				new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
		assertNotEquals(commitment, LootDecisionReceipt.decisionId(altered));
		KeyPair key = LootDecisionReceipt.generateKey();
		assertEquals(commitment, LootDecisionReceipt.decisionId(original.withDecisionReceipt(LootDecisionReceipt.sign(1L, key, original))));
	}

	private static LootProposal finalized()
	{
		return LootProposal.pending(77L, 1L, new SharedLootEvent("p", "Alice", "Boss", Instant.ofEpochSecond(1),
			Collections.singletonList(new SharedLootItem(100, 100, 1L, 1000L))))
			.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(2),
				Collections.singletonList(new LootshareParticipant(1L, "Alice")));
	}
}
