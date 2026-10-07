/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.sessions;

import com.communitylootshare.domain.*;
import com.communitylootshare.sessions.LootshareEngine.MutationResult;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LootshareEngineRecoveryTest
{
	@Test public void importsFrozenAcceptedAndRejectedRecordsWithoutRecalculatingThem() throws Exception
	{
		LootshareEngine engine = engine();
		LootProposal accepted = finalized("accepted", LootProposalStatus.ACCEPTED);
		LootProposal rejected = finalized("rejected", LootProposalStatus.REJECTED);
		assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, accepted).getResult());
		assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, rejected).getResult());
		assertEquals(MutationResult.DUPLICATE, engine.importFinalizedProposal(1L, accepted).getResult());
		assertEquals(MutationResult.DUPLICATE, engine.importFinalizedProposal(1L, rejected).getResult());
		assertEquals(1001L, new LootshareCalculator().calculate(engine.getActiveSession().get()).getTotalAcceptedValue());
		assertEquals(1, engine.getActiveSession().get().getRejectedProposals().size());
		assertEquals(501L, new LootshareCalculator().calculate(engine.getActiveSession().get()).getBalances().get(0).getEntitledValue());
		assertEquals(500L, new LootshareCalculator().calculate(engine.getActiveSession().get()).getTransfers().get(0).getAmount());

		KeyPair key = LootDecisionReceipt.generateKey();
		assertTrue(engine.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.publicKey(key), 1L)));
		LootProposal signed = accepted.withDecisionReceipt(LootDecisionReceipt.sign(1L, key, accepted));
		assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, signed).getResult());
		assertTrue(engine.getProposal("accepted").get().getDecisionReceipt().verifies(accepted));
		assertTrue(engine.getActiveSession().get().getAcceptedProposals().get(0).getDecisionReceipt().verifies(accepted));
		LootshareEngine restored = new LootshareEngine();
		restored.restore(engine.snapshot());
		assertTrue(restored.getProposal("accepted").get().getDecisionReceipt().verifies(accepted));
	}

	@Test public void rejectsUnauthorizedInvalidAndConflictingImportsWithoutChangingBalances()
	{
		LootshareEngine engine = engine();
		LootProposal original = finalized("p", LootProposalStatus.ACCEPTED);
		assertEquals(MutationResult.NOT_HOST, engine.importFinalizedProposal(2L, original).getResult());
		assertEquals(MutationResult.INVALID, engine.importFinalizedProposal(1L, null).getResult());
		assertEquals(MutationResult.INVALID, engine.importFinalizedProposal(1L, LootProposal.pending(77L, 1L, original.getEvent())).getResult());
		assertEquals(MutationResult.INVALID, engine.importFinalizedProposal(1L, LootProposal.pending(78L, 1L, original.getEvent()).decide(LootProposalStatus.ACCEPTED,
			original.getDecidedAt(), original.getParticipants())).getResult());
		assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, original).getResult());
		LootProposal conflict = LootProposal.pending(77L, 1L, original.getEvent()).decide(LootProposalStatus.ACCEPTED,
			original.getDecidedAt(), Collections.singletonList(new LootshareParticipant(1L, "Alice")));
		assertEquals(MutationResult.CONFLICT, engine.importFinalizedProposal(1L, conflict).getResult());
		assertEquals(MutationResult.CONFLICT, engine.importFinalizedProposal(1L, finalized("p", LootProposalStatus.REJECTED)).getResult());
		assertEquals(original.getParticipants(), engine.getProposal("p").get().getParticipants());
		assertEquals(1001L, new LootshareCalculator().calculate(engine.getActiveSession().get()).getTotalAcceptedValue());
		LootshareSession session = engine.getActiveSession().get();
		try { session.replaceFinalizedProposal(conflict); fail("Cannot rewrite frozen roster"); }
		catch (IllegalArgumentException expected) { }
		assertEquals(MutationResult.NO_ACTIVE_SESSION, new LootshareEngine().importFinalizedProposal(1L, original).getResult());
	}

	@Test public void finalizedArchiveRecoveryWorksWhenThePendingTransportIndexIsFull()
	{
		LootshareEngine engine = engine();
		for (int index = 0; index < LootshareEngine.MAX_PROPOSALS; index++)
			assertEquals(MutationResult.APPLIED, engine.addProposal(LootProposal.pending(77L, 1L, event("pending-" + index))));
		LootProposal archived = finalized("archive", LootProposalStatus.ACCEPTED);
		assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, archived).getResult());
		assertEquals(LootshareEngine.MAX_PROPOSALS, engine.snapshot().getProposals().size());
		assertEquals(LootProposalStatus.ACCEPTED, engine.getProposal("archive").get().getStatus());
	}

	@Test public void decisionKeysAreBoundedAndCannotBeReboundToAnotherHost() throws Exception
	{
		LootshareEngine engine = engine();
		String key = LootDecisionReceipt.publicKey(LootDecisionReceipt.generateKey());
		assertFalse(new LootshareEngine().canTrustDecisionKeys(Collections.singletonMap(key, 1L)));
		assertFalse(new LootshareEngine().trustDecisionKeys(Collections.singletonMap(key, 1L)));
		assertTrue(new LootshareEngine().getActiveDecisionKeys().isEmpty());
		assertTrue(engine.canTrustDecisionKeys(Collections.singletonMap(key, 1L)));
		assertTrue(engine.trustDecisionKeys(Collections.singletonMap(key, 1L)));
		assertFalse(engine.trustDecisionKeys(Collections.singletonMap(key, 1L)));
		assertFalse(engine.canTrustDecisionKeys(Collections.singletonMap(key, 2L)));
		assertFalse(engine.canTrustDecisionKeys(Collections.singletonMap("bad", 1L)));
		Map<String, Long> keys = new LinkedHashMap<>();
		for (int index = 0; index < LootshareSession.MAX_DECISION_KEYS; index++)
			keys.put(LootDecisionReceipt.publicKey(LootDecisionReceipt.generateKey()), 1L);
		LootshareSession session = new LootshareSession("keys", 77L, Instant.EPOCH);
		session.trustDecisionKeys(keys);
		try { session.trustDecisionKeys(Collections.singletonMap(key, 1L)); fail("Expected bounded keys"); }
		catch (IllegalArgumentException expected) { }
		keys.put(key, 1L);
		assertFalse(engine.canTrustDecisionKeys(keys));
	}

	@Test public void millisecondReplayEnrichesNanosecondLegacyDecisions() throws Exception
	{
		for (LootProposalStatus status : Arrays.asList(LootProposalStatus.ACCEPTED, LootProposalStatus.REJECTED))
		{
			LootshareEngine engine = engine();
			LootProposal local = LootProposal.pending(77L, 1L, event("precision"))
				.decide(status, Instant.parse("2026-10-07T12:00:00.123456789Z"), Arrays.asList(
					new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
			assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, local).getResult());
			// Gson bypasses constructors; simulate an older snapshot that retained nanoseconds.
			com.google.gson.Gson gson = new com.google.gson.Gson().newBuilder()
				.registerTypeAdapter(Instant.class, new com.communitylootshare.utils.InstantTypeAdapter()).create();
			String legacy = gson.toJson(engine.snapshot()).replace("2026-10-07T12:00:00.123Z", "2026-10-07T12:00:00.123456789Z");
			engine.restore(gson.fromJson(legacy, com.communitylootshare.domain.LootshareState.class));
			assertEquals(Instant.parse("2026-10-07T12:00:00.123Z"), engine.getProposal("precision").get().getDecidedAt());
			KeyPair key = LootDecisionReceipt.generateKey();
			LootProposal signed = local.withDecisionReceipt(LootDecisionReceipt.sign(1L, key, local));
			com.communitylootshare.party.RecoveryMessage message = new com.communitylootshare.party.RecoveryMessage(1L, signed);
			message.setMemberId(2L);
			LootProposal replay = message.decode(77L).get();
			assertTrue(replay.getDecisionReceipt().verifies(replay));
			assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, replay).getResult());
			assertNotNull(engine.getProposal("precision").get().getDecisionReceipt());
			assertEquals(Instant.parse("2026-10-07T12:00:00.123Z"), engine.getProposal("precision").get().getDecidedAt());
			assertEquals(MutationResult.DUPLICATE, engine.importFinalizedProposal(1L, replay).getResult());
			LootProposal later = LootProposal.pending(77L, 1L, local.getEvent())
				.decide(status, replay.getDecidedAt().plusMillis(1L), local.getParticipants());
			assertEquals(MutationResult.CONFLICT, engine.importFinalizedProposal(1L, later).getResult());
		}
	}

	private static LootshareEngine engine()
	{
		LootshareEngine engine = new LootshareEngine();
		engine.enterParty(77L, "s", Instant.EPOCH);
		engine.updateHostState(1L, 1L, 100L, 1L);
		return engine;
	}
	private static SharedLootEvent event(String id)
	{
		return new SharedLootEvent(id, "Alice", "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, 1001L)));
	}
	private static LootProposal finalized(String id, LootProposalStatus status)
	{
		return LootProposal.pending(77L, 1L, event(id)).decide(status, Instant.ofEpochSecond(1), Arrays.asList(
			new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
	}
}
