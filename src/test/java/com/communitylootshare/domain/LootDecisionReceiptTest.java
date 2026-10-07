/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class LootDecisionReceiptTest
{
	@Test public void signatureBindsValueOwnerPartyTimeStatusAndEntireFrozenRoster() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		LootProposal original = accepted(77L, 1L, 1000L);
		LootDecisionReceipt receipt = LootDecisionReceipt.sign(1L, key, original);
		assertTrue(receipt.verifies(original.validatedCopy()));
		assertTrue(receipt.validatedCopy().verifies(original));
		assertEquals(receipt, receipt.validatedCopy());
		assertFalse(receipt.verifies(accepted(78L, 1L, 1000L)));
		assertFalse(receipt.verifies(accepted(77L, 2L, 1000L)));
		assertFalse(receipt.verifies(accepted(77L, 1L, 1001L)));
		LootProposal pending = LootProposal.pending(77L, 1L, original.getEvent());
		assertFalse(receipt.verifies(pending.decide(LootProposalStatus.REJECTED, Instant.ofEpochSecond(1), Collections.emptyList())));
		assertFalse(receipt.verifies(pending.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(2), original.getParticipants())));
		assertFalse(receipt.verifies(pending.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1), Collections.singletonList(new LootshareParticipant(1L, "Alice")))));
		assertFalse(receipt.verifies(pending));
		LootDecisionReceipt wrongKey = LootDecisionReceipt.sign(2L, LootDecisionReceipt.generateKey(), original);
		assertNotEquals(receipt.getPublicKey(), wrongKey.getPublicKey());
		assertTrue(original.withDecisionReceipt(receipt).validatedCopy().getDecisionReceipt().verifies(original));
	}

	@Test public void validatesReceiptBoundsAndRejectsMalformedKeysOrSignatures() throws Exception
	{
		String key = LootDecisionReceipt.publicKey(LootDecisionReceipt.generateKey());
		assertTrue(LootDecisionReceipt.isValidPublicKey(key));
		assertFalse(LootDecisionReceipt.isValidPublicKey(null));
		assertFalse(LootDecisionReceipt.isValidPublicKey(""));
		assertFalse(LootDecisionReceipt.isValidPublicKey(String.join("", Collections.nCopies(257, "x"))));
		assertFalse(LootDecisionReceipt.isValidPublicKey("not base64"));
		assertFalse(LootDecisionReceipt.isValidPublicKey("AAAA"));
		assertFalse(new LootDecisionReceipt(1L, key, "AAAA").verifies(accepted(77L, 1L, 100L)));
		assertFalse(new LootDecisionReceipt(1L, "AAAA", "AAAA").verifies(accepted(77L, 1L, 100L)));
		assertFalse(new LootDecisionReceipt(2L, key, "AAAA").verifies(null));
		expectInvalid(() -> new LootDecisionReceipt(0L, key, "AAAA"));
		expectInvalid(() -> new LootDecisionReceipt(1L, null, "AAAA"));
		expectInvalid(() -> new LootDecisionReceipt(1L, "bad!", "AAAA"));
		expectInvalid(() -> new LootDecisionReceipt(1L, key, null));
		expectInvalid(() -> new LootDecisionReceipt(1L, key, "bad!"));
		LootDecisionReceipt receipt = LootDecisionReceipt.sign(1L, LootDecisionReceipt.generateKey(), accepted(77L, 1L, 100L));
		expectInvalid(() -> LootProposal.pending(77L, 1L, accepted(77L, 1L, 100L).getEvent()).withDecisionReceipt(receipt));
	}

	@Test public void profileKeyBindingsAllowNewPartyMemberIdsWithoutRebindingOldAuthority() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		String encoded = LootDecisionReceipt.publicKey(key);
		LootshareSession session = new LootshareSession("s", 77L, Instant.EPOCH);
		assertTrue(session.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(encoded, 1L), 1L)));
		assertTrue(session.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(encoded, 2L), 2L)));
		assertEquals(2, session.getDecisionKeys().size());
		LootDecisionReceipt receipt = LootDecisionReceipt.sign(1L, key, accepted(77L, 1L, 1000L));
		assertTrue(receipt.isTrustedBy(session.getDecisionKeys()));
		assertFalse(receipt.isTrustedBy(Collections.singletonMap(LootDecisionReceipt.keyId(encoded, 2L), 2L)));
		assertFalse(LootDecisionReceipt.isValidKeyId(null, 1L));
		assertFalse(LootDecisionReceipt.isValidKeyId(encoded + "#3", 2L));
		assertFalse(LootDecisionReceipt.isValidKeyId("bad!#1", 1L));
		expectInvalid(() -> session.trustDecisionKeys(Collections.singletonMap(encoded + "#1", 2L)));
	}

	private static LootProposal accepted(long partyId, long owner, long amount)
	{
		return LootProposal.pending(partyId, owner, new SharedLootEvent("p", "Alice", "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, amount))))
			.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1), Arrays.asList(
				new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
	}

	private static void expectInvalid(Runnable action)
	{
		try { action.run(); fail("Expected invalid input to be rejected"); }
		catch (IllegalArgumentException expected) { }
	}
}
