/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.domain;

import com.communitylootshare.sessions.LootshareCalculator;
import com.communitylootshare.sessions.LootshareEngine;
import com.communitylootshare.sessions.LootshareEngine.MutationResult;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.party.HistoryMessage;
import com.communitylootshare.persistence.LootshareStorage;
import com.communitylootshare.utils.InstantTypeAdapter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HostHistoryEventTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	private final Gson gson = new Gson().newBuilder().registerTypeAdapter(Instant.class, new InstantTypeAdapter()).create();
	private static final HostPeriod PERIOD = new HostPeriod("period-a", 1L, "Alice", null, Instant.EPOCH, null);

	@Test public void settingsAuditPreservesMultiSegmentRostersPricesAndArchivedSummary() throws Exception
	{
		LootshareSession session = session();
		LootProposal first = accepted("first", 1L, 1001L, Arrays.asList(member(1), member(2)));
		LootProposal second = accepted("second", 2L, 501L, Arrays.asList(member(2), member(3)));
		session.addAcceptedProposal(first); session.addAcceptedProposal(second);
		SettlementSummary before = SettlementSummary.from(new LootshareCalculator().calculate(session));
		assertEquals(1502L, before.getTotal());
		assertEquals(Arrays.asList("first", "second"), before.getProposalIds());
		session.setHostState(1L, LootshareSettings.defaults(750L), 2L);
		SettlementSummary after = SettlementSummary.from(new LootshareCalculator().calculate(session));
		assertEquals(1001L, after.getTotal());
		assertEquals(501L, after.getBalances().get(0).getEntitledValue());
		assertEquals(500L, after.getTransfers().get(0).getAmount());
		KeyPair key = LootDecisionReceipt.generateKey();
		HostHistoryEvent event = settings("apply", 2L, before, after).sign(1L, key);
		assertTrue(event.verifies());
		assertEquals(before, event.getBefore()); assertEquals(after, event.getAfter());
		assertEquals(HostHistoryEvent.Kind.SETTINGS_APPLIED, event.getKind());
		assertEquals(HostHistoryEvent.Reason.SETTINGS, event.getReason()); assertTrue(event.isComplete());
		assertEquals(77L, event.getPartyId()); assertEquals(Instant.ofEpochSecond(5), event.getOccurredAt());
		assertEquals(750L, event.getNewSettings().getMinimumSharedLootValue());
		assertEquals(first.getEvent().getItems(), session.getAcceptedProposals().get(0).getEvent().getItems());
		assertEquals(first.getParticipants(), session.getAcceptedProposals().get(0).getParticipants());
		HostHistoryEvent decoded = gson.fromJson(gson.toJson(event), HostHistoryEvent.class).validatedCopy();
		assertTrue(decoded.verifies()); assertEquals(event.commitment(), decoded.commitment());
		JsonObject altered = gson.toJsonTree(event).getAsJsonObject();
		altered.addProperty("complete", false);
		HostHistoryEvent forged = gson.fromJson(altered, HostHistoryEvent.class).validatedCopy();
		assertFalse(forged.verifies()); assertNotEquals(event.commitment(), forged.commitment());
		assertFalse(settings("unsigned", 2L, before, after).verifies());
	}

	@Test public void checkpointRoundTripMigrationAndPartyRejoinKeepFrozenHistory() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		LootshareEngine engine = new LootshareEngine();
		engine.enterParty(77L, "ledger", Instant.EPOCH);
		engine.updateHostState(1L, 1L, LootshareSettings.defaults(0L), 1L);
		engine.setHostPeriod(PERIOD, false);
		engine.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		LootProposal proposal = accepted("original", 1L, 1001L, Arrays.asList(member(1), member(2)));
		assertEquals(MutationResult.APPLIED, engine.importFinalizedProposal(1L, proposal).getResult());
		engine.markHostBoundary(Instant.ofEpochSecond(10), HostHistoryEvent.Reason.TRANSFER);
		LootshareSession outgoing = engine.getActiveSession().get();
		assertEquals(HostHistoryEvent.Reason.TRANSFER, outgoing.getClosingReason());
		SettlementSummary summary = SettlementSummary.from(new LootshareCalculator().calculate(outgoing));
		HostHistoryEvent checkpoint = closure("close:period-a", 1L, PERIOD.end(Instant.ofEpochSecond(10)), summary, true).sign(1L, key);
		assertTrue(engine.commitHistoryEvent(checkpoint));
		assertFalse(engine.commitHistoryEvent(checkpoint));
		assertNull(engine.getActiveSession().get().getClosingPeriod());
		assertEquals(MutationResult.APPLIED, engine.updateHostState(1L, 2L, LootshareSettings.defaults(0L), 2L));
		HostPeriod next = new HostPeriod("period-b", 2L, "Bob", "period-a", Instant.ofEpochSecond(10), null);
		engine.setHostPeriod(next, false);
		engine.updateHostState(2L, 2L, LootshareSettings.defaults(2000L), 3L);
		assertEquals(0L, new LootshareCalculator().calculate(engine.getActiveSession().get()).getTotalAcceptedValue());
		engine.leaveParty(Instant.ofEpochSecond(20));
		File file = temporary.newFile();
		LootshareStorage storage = new LootshareStorage(file, gson);
		assertTrue(storage.save(file, engine.snapshot()));
		LootshareStorage.LoadResult loaded = storage.load(file);
		assertTrue(loaded.isWritable()); assertEquals(8, loaded.getState().getSchemaVersion());
		LootshareEngine restored = new LootshareEngine(); restored.restore(loaded.getState());
		assertEquals(MutationResult.APPLIED, restored.enterParty(77L, "ignored-new-id", Instant.ofEpochSecond(30)));
		LootshareSession rejoined = restored.getActiveSession().get();
		assertEquals("ledger", rejoined.getSessionId()); assertEquals(next, rejoined.getHostPeriod());
		assertEquals(2000L, rejoined.getMinimumSharedLootValue());
		assertEquals(1001L, rejoined.getHistoryEvents().get(0).getAfter().getTotal());
		assertFalse(rejoined.isEarlierHostChainUnknown());
		assertEquals(proposal.getParticipants(), rejoined.getAcceptedProposals().get(0).getParticipants());

		JsonObject legacy = gson.toJsonTree(engine.snapshot()).getAsJsonObject(); legacy.addProperty("schemaVersion", 7);
		JsonObject ledger = legacy.getAsJsonArray("sessions").get(0).getAsJsonObject();
		for (String field : Arrays.asList("hostPeriod", "historyEvents", "historyCommitments", "earlierHostChainUnknown")) { ledger.remove(field); }
		Files.write(file.toPath(), gson.toJson(legacy).getBytes(StandardCharsets.UTF_8));
		LootshareStorage.LoadResult migrated = storage.load(file);
		assertTrue(migrated.isWritable());
		assertTrue(migrated.getState().getSessions().get(0).isEarlierHostChainUnknown());
		assertEquals(1, migrated.getState().getSessions().get(0).getAcceptedProposals().size());
	}

	@Test public void authenticatedMetadataDeduplicatesOutOfOrderEventsAndRejectsConflicts() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		SettlementSummary empty = SettlementSummary.from(LootshareCalculation.empty());
		LootshareSession session = session(); session.setHostState(1L, LootshareSettings.defaults(0), 3L);
		session.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		HostHistoryEvent early = settings("early", 2L, empty, empty).sign(1L, key);
		HostHistoryEvent late = settings("late", 3L, empty, empty).sign(1L, key);
		Map<String, HistoryCommitment> anchors = new LinkedHashMap<>();
		anchors.put(early.getEventId(), new HistoryCommitment(2L, early.commitment()));
		anchors.put(late.getEventId(), new HistoryCommitment(3L, late.commitment()));
		session.setHistoryMetadata(PERIOD, anchors, false, false);
		assertTrue(session.addHistoryEvent(late)); assertTrue(session.addHistoryEvent(early));
		assertFalse(session.addHistoryEvent(early));
		assertEquals("early", session.getHistoryEvents().get(0).getEventId());
		session.validateHistorySignatures();
		HostMessage message = new HostMessage(session); message.setMemberId(1L);
		HostMessage roundTrip = new Gson().fromJson(new Gson().toJson(message), HostMessage.class);
		roundTrip.setMemberId(1L);
		assertEquals(PERIOD, roundTrip.decode().get().getHostPeriod());
		assertEquals(anchors, roundTrip.decode().get().getHistoryCommitments());
		assertFalse(roundTrip.decode().get().isEarlierHistoryOmitted());
		assertFalse(roundTrip.decode().get().isEarlierHostChainUnknown());
		expectInvalid(() -> session.addHistoryEvent(settings("uncommitted", 3L, empty, empty).sign(1L, key)));
		Map<String, HistoryCommitment> conflicting = Collections.singletonMap("early", new HistoryCommitment(2L, late.commitment()));
		expectInvalid(() -> session.setHistoryMetadata(PERIOD, conflicting, false, false));
		assertEquals(anchors, session.getHistoryCommitments());
		JsonObject corrupt = gson.toJsonTree(session).getAsJsonObject();
		corrupt.getAsJsonArray("historyEvents").get(0).getAsJsonObject().addProperty("complete", false);
		File file = temporary.newFile();
		LootshareSession invalid = gson.fromJson(corrupt, LootshareSession.class);
		expectInvalid(invalid::validateHistorySignatures);
		LootshareState invalidState = new LootshareState(); invalidState.setSessions(Collections.singletonList(invalid));
		invalidState.setActiveSessionId(invalid.getSessionId());
		Files.writeString(file.toPath(), gson.toJson(invalidState));
		byte[] original = Files.readAllBytes(file.toPath());
		assertFalse(new LootshareStorage(file, gson).load(file).isWritable());
		assertArrayEquals(original, Files.readAllBytes(file.toPath()));
	}

	@Test public void retentionKeepsLedgerAndActivePeriodWhileMarkingEarlierHistoryOmitted() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		LootshareEngine engine = new LootshareEngine(); engine.enterParty(77L, "retention", Instant.EPOCH);
		engine.updateHostState(1L, 1L, LootshareSettings.defaults(0), 1L); engine.setHostPeriod(PERIOD, false);
		engine.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		engine.importFinalizedProposal(1L, accepted("kept-loot", 1L, 1001L, Arrays.asList(member(1), member(2))));
		SettlementSummary empty = SettlementSummary.from(LootshareCalculation.empty());
		for (int index = 1; index <= 257; index++)
		{
			engine.updateHostState(1L, 1L, LootshareSettings.defaults(0L), index + 1L);
			assertTrue(engine.commitHistoryEvent(settings("event-" + index, index + 1L, empty, empty).sign(1L, key)));
		}
		LootshareSession session = engine.getActiveSession().get();
		assertEquals(256, session.getHistoryEvents().size()); assertEquals(256, session.getHistoryCommitments().size());
		assertEquals("event-2", session.getHistoryEvents().get(0).getEventId());
		assertTrue(session.isEarlierHistoryOmitted()); assertEquals(PERIOD, session.getHostPeriod());
		assertEquals(1001L, new LootshareCalculator().calculate(session).getTotalAcceptedValue());
		File file = temporary.newFile(); LootshareStorage storage = new LootshareStorage(file, gson);
		assertTrue(storage.save(file, engine.snapshot())); assertTrue(storage.load(file).isWritable());
		assertFalse(engine.commitHistoryEvent(null));
		assertFalse(engine.commitHistoryEvent(settings("invalid-unsigned", 258L, empty, empty)));
		assertEquals(256, engine.getActiveSession().get().getHistoryCommitments().size());
	}

	@Test public void chunkTransportIsBoundedAndDoesNotDuplicateItemPayloads() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		List<LootshareCalculation.Balance> manyMembers = new ArrayList<>();
		for (long id = 1; id <= 200; id++) { manyMembers.add(new LootshareCalculation.Balance(id, "Member " + id, 0L, 0L)); }
		SettlementSummary summary = new SettlementSummary(0L, manyMembers, Collections.emptyList(), Collections.emptyList());
		HostHistoryEvent event = closure("close:period-a", 1L, PERIOD.end(Instant.ofEpochSecond(1)), summary, false).sign(1L, key);
		List<HistoryMessage> chunks = HistoryMessage.chunks(event, 2L, false, gson);
		assertTrue(chunks.size() > 1); StringBuilder joined = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++)
		{
			HistoryMessage chunk = gson.fromJson(gson.toJson(chunks.get(i)), HistoryMessage.class); chunk.setMemberId(1L);
			assertTrue(chunk.isValid(77L)); assertFalse(chunk.isValid(78L)); assertEquals(i, chunk.getIndex());
			assertEquals(chunks.size(), chunk.getCount()); assertEquals(2L, chunk.getTargetMemberId());
			assertEquals(event.getEventId(), chunk.getEventId()); assertFalse(chunk.isLive()); joined.append(chunk.getData());
		}
		assertTrue(gson.fromJson(joined.toString(), HostHistoryEvent.class).validatedCopy().verifies());
		assertFalse(joined.toString().contains("unitPrice"));
		HistoryMessage invalid = new HistoryMessage(); assertFalse(invalid.isValid(77L));
		JsonObject json = gson.toJsonTree(chunks.get(0)).getAsJsonObject();
		for (String field : Arrays.asList("count", "index", "protocolVersion"))
		{
			JsonObject bad = json.deepCopy(); bad.addProperty(field, 999);
			HistoryMessage message = gson.fromJson(bad, HistoryMessage.class); message.setMemberId(1L); assertFalse(message.isValid(77L));
		}
		JsonObject bad = json.deepCopy(); bad.addProperty("data", String.join("", Collections.nCopies(4097, "x")));
		HistoryMessage oversized = gson.fromJson(bad, HistoryMessage.class); oversized.setMemberId(1L); assertFalse(oversized.isValid(77L));
		expectInvalid(() -> HistoryMessage.chunks(event, -1L, false, gson));
	}

	@Test public void authorityAndAuditMutationsRejectInvalidRequestsWithoutPartialChanges() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		LootshareEngine engine = new LootshareEngine();
		SettlementSummary empty = SettlementSummary.from(LootshareCalculation.empty());
		HostHistoryEvent audit = settings("atomic-apply", 2L, empty, empty).sign(1L, key);
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.applySettingsWithHistory(1L, 1L, audit.getNewSettings(), audit));
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.transferHostWithHistory(1L, 2L, 1L, null, null));
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.importHistoryEvent(audit));
		assertEquals(MutationResult.NO_ACTIVE_SESSION, engine.updateHostSnapshot(1L, 1L, LootshareSettings.defaults(0), 1L,
			Collections.emptyMap(), null, Collections.emptyMap(), false, false));
		engine.enterParty(77L, "atomic", Instant.EPOCH); engine.updateHostState(1L, 1L, LootshareSettings.defaults(0), 1L);
		engine.setHostPeriod(PERIOD, false);
		engine.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		assertEquals(MutationResult.NOT_HOST, engine.applySettingsWithHistory(2L, 1L, audit.getNewSettings(), audit));
		assertEquals(MutationResult.CONFLICT, engine.applySettingsWithHistory(1L, 2L, audit.getNewSettings(), audit));
		assertEquals(MutationResult.INVALID, engine.applySettingsWithHistory(1L, 1L, audit.getNewSettings(), null));
		JsonObject tampered = gson.toJsonTree(audit).getAsJsonObject(); tampered.addProperty("complete", false);
		HostHistoryEvent invalidSignature = gson.fromJson(tampered, HostHistoryEvent.class).validatedCopy();
		assertEquals(MutationResult.INVALID, engine.applySettingsWithHistory(1L, 1L, audit.getNewSettings(), invalidSignature));
		assertEquals(1L, engine.getActiveHostRevision()); assertEquals(0L, engine.getActiveMinimumSharedLootValue());
		assertTrue(engine.getActiveSession().get().getHistoryCommitments().isEmpty());
		assertEquals(MutationResult.INVALID, engine.importHistoryEvent(invalidSignature));
		assertEquals(MutationResult.INVALID, engine.updateHostSnapshot(1L, 2L, LootshareSettings.defaults(900), 2L,
			Collections.emptyMap(), PERIOD, Collections.emptyMap(), false, false));
		assertEquals(1L, engine.getActiveHostMemberId()); assertEquals(1L, engine.getActiveHostRevision());
		HostPeriod changed = new HostPeriod("unexpected", 1L, "Alice", null, Instant.EPOCH, null);
		assertEquals(MutationResult.CONFLICT, engine.updateHostSnapshot(1L, 1L, LootshareSettings.defaults(0), 1L,
			engine.getActiveMemberApprovalStatuses(), changed, Collections.emptyMap(), false, false));
		HostPeriod next = new HostPeriod("next", 2L, "Bob", "period-a", Instant.ofEpochSecond(10), null);
		HostHistoryEvent close = closure("close:period-a", 2L, PERIOD.end(Instant.ofEpochSecond(10)), empty, true).sign(1L, key);
		assertEquals(MutationResult.NOT_HOST, engine.transferHostWithHistory(2L, 2L, 1L, next, close));
		assertEquals(MutationResult.CONFLICT, engine.transferHostWithHistory(1L, 2L, 2L, next, close));
		assertEquals(MutationResult.INVALID, engine.transferHostWithHistory(1L, 1L, 1L, next, close));
		assertEquals(MutationResult.INVALID, engine.transferHostWithHistory(1L, 2L, 1L, next, null));
		assertEquals(1L, engine.getActiveHostMemberId()); assertEquals(PERIOD, engine.getActiveSession().get().getHostPeriod());
		assertEquals(MutationResult.APPLIED, engine.transferHostWithHistory(1L, 2L, 1L, next, close));
		assertEquals(next, engine.getActiveSession().get().getHostPeriod());
		assertEquals(1, engine.getActiveSession().get().getHistoryEvents().size());
		assertEquals(MutationResult.DUPLICATE, engine.importHistoryEvent(close));
		assertFalse(engine.commitHistoryEvent(invalidSignature));
	}

	@Test public void domainRejectsInvalidChronologyAndNonConservingSettlements() throws Exception
	{
		SettlementSummary empty = SettlementSummary.from(LootshareCalculation.empty());
		expectInvalid(() -> new HostPeriod("bad id", 1L, "Alice", null, Instant.EPOCH, null));
		expectInvalid(() -> new HostPeriod("a", 1L, "Alice", "a", Instant.EPOCH, null));
		expectInvalid(() -> new HostPeriod("a", 1L, "Alice", null, null, null));
		expectInvalid(() -> new HostPeriod("a", 1L, "Alice", "b", Instant.ofEpochSecond(1), Instant.EPOCH));
		assertEquals(PERIOD, PERIOD.validatedCopy()); assertEquals("period-a", PERIOD.getPeriodId());
		assertEquals("Alice", PERIOD.getHostDisplayName()); assertEquals(1L, PERIOD.getHostMemberId());
		assertNull(PERIOD.getPredecessorId()); assertNull(PERIOD.getEndedAt());
		expectInvalid(() -> new HistoryCommitment(0L, "x"));
		expectInvalid(() -> new HistoryCommitment(1L, String.join("", Collections.nCopies(44, "x"))));
		HostHistoryEvent unsigned = settings("valid", 2L, empty, empty);
		expectInvalid(() -> new HostHistoryEvent("invalid", 0L, 1L, PERIOD, Instant.EPOCH, HostHistoryEvent.Kind.SETTINGS_APPLIED,
			HostHistoryEvent.Reason.SETTINGS, LootshareSettings.defaults(0), LootshareSettings.defaults(750), empty, empty, true, null));
		expectInvalid(() -> closure("invalid", 1L, PERIOD, empty, true));
		expectInvalid(() -> new HostHistoryEvent("invalid", 77L, 1L, PERIOD, Instant.EPOCH, HostHistoryEvent.Kind.SETTINGS_APPLIED,
			HostHistoryEvent.Reason.SETTINGS, LootshareSettings.defaults(0), LootshareSettings.defaults(0), empty, empty, true, null));
		expectInvalid(() -> new SettlementSummary(-1, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
		expectInvalid(() -> new SettlementSummary(1, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
		expectInvalid(() -> new SettlementSummary(0, null, Collections.emptyList(), Collections.emptyList()));
		expectInvalid(() -> new SettlementSummary(0, Collections.emptyList(), Collections.emptyList(), Arrays.asList("duplicate", "duplicate")));
		expectInvalid(() -> new SettlementSummary(0, Collections.emptyList(), Collections.emptyList(), Collections.singletonList("")));
		LootshareCalculation.Balance zero = new LootshareCalculation.Balance(1L, "Alice", 0, 0);
		expectInvalid(() -> new SettlementSummary(0, Arrays.asList(zero, zero), Collections.emptyList(), Collections.emptyList()));
		List<LootshareCalculation.Balance> balances = Arrays.asList(new LootshareCalculation.Balance(1L, "Alice", 10, 5), new LootshareCalculation.Balance(2L, "Bob", 0, 5));
		expectInvalid(() -> new SettlementSummary(10, balances, Collections.emptyList(), Collections.emptyList()));
		expectInvalid(() -> new SettlementSummary(10, balances, Collections.singletonList(new LootshareCalculation.Transfer(2L, "Bob", 1L, "Alice", 5)), Collections.emptyList()));
		LootshareSession session = session();
		expectInvalid(() -> session.setHistoryMetadata(new HostPeriod("other", 2L, "Bob", null, Instant.EPOCH, null), Collections.emptyMap(), false, false));
		expectInvalid(() -> session.setHistoryMetadata(PERIOD, null, false, false));
		expectInvalid(() -> session.setHistoryMetadata(PERIOD, Collections.singletonMap("future", new HistoryCommitment(2L, unsigned.commitment())), false, false));
		expectInvalid(() -> session.markClosingPeriod(PERIOD, LootshareSettings.defaults(), HostHistoryEvent.Reason.TRANSFER));
		session.markClosingPeriod(PERIOD.end(Instant.ofEpochSecond(1)), LootshareSettings.defaults(), HostHistoryEvent.Reason.DEPARTURE);
		assertEquals(PERIOD.end(Instant.ofEpochSecond(1)), session.snapshot().getClosingPeriod());
		session.clearClosingPeriod(); assertNull(session.getClosingSettings());
	}

	@Test public void queuedBoundariesSurviveProfileReloadAndOutOfOrderClosure() throws Exception
	{
		LootshareSession session = session();
		LootshareSettings firstSettings = LootshareSettings.defaults(100);
		LootshareSettings secondSettings = LootshareSettings.defaults(750);
		HostPeriod first = PERIOD.end(Instant.ofEpochSecond(10));
		HostPeriod second = new HostPeriod("period-b", 2L, "Bob", "period-a", Instant.ofEpochSecond(10), Instant.ofEpochSecond(20));
		session.markClosingPeriod(first, firstSettings, HostHistoryEvent.Reason.DEPARTURE);
		session.markClosingPeriod(second, secondSettings, HostHistoryEvent.Reason.LOCAL_LEAVE);
		session.markClosingPeriod(second.end(Instant.ofEpochSecond(30)), LootshareSettings.defaults(999), HostHistoryEvent.Reason.DEPARTURE);
		session.markClosingPeriod(first.end(Instant.ofEpochSecond(30)), LootshareSettings.defaults(999), HostHistoryEvent.Reason.DEPARTURE);
		LootshareState state = new LootshareState(); state.setSessions(Collections.singletonList(session)); state.setActiveSessionId(session.getSessionId());
		File file = temporary.newFile(); LootshareStorage storage = new LootshareStorage(file, gson);
		assertTrue(storage.save(file, state));
		LootshareStorage.LoadResult loaded = storage.load(file); assertTrue(loaded.isWritable());
		LootshareSession restored = loaded.getState().getSessions().get(0);
		assertEquals(first, restored.getClosingPeriod()); assertEquals(firstSettings, restored.getClosingSettings());
		LootshareSession ordered = restored.snapshot(); ordered.clearClosingPeriod();
		assertEquals(second, ordered.getClosingPeriod()); assertEquals(secondSettings, ordered.getClosingSettings());
		assertEquals(HostHistoryEvent.Reason.LOCAL_LEAVE, ordered.getClosingReason());
		ordered.clearClosingPeriod(); assertNull(ordered.getClosingPeriod());

		// Replayed closure of a later period removes only that queued boundary.
		KeyPair key = LootDecisionReceipt.generateKey();
		restored.trustDecisionKeys(Collections.singletonMap(LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		SettlementSummary empty = SettlementSummary.from(LootshareCalculation.empty());
		HostHistoryEvent replay = closure("close:period-b", 1L, second, empty, true).sign(1L, key);
		restored.setHistoryMetadata(PERIOD, Collections.singletonMap(replay.getEventId(), new HistoryCommitment(1L, replay.commitment())), false, false);
		assertTrue(restored.addHistoryEvent(replay));
		restored.markClosingPeriod(second, secondSettings, HostHistoryEvent.Reason.LOCAL_LEAVE);
		assertEquals(first, restored.snapshot().getClosingPeriod());
		restored.clearClosingPeriod(); assertNull(restored.getClosingPeriod());

		// Existing schema-8 files without the queue still preserve their first boundary.
		JsonObject legacy = gson.toJsonTree(state).getAsJsonObject();
		legacy.getAsJsonArray("sessions").get(0).getAsJsonObject().remove("queuedClosingPeriods");
		Files.write(file.toPath(), gson.toJson(legacy).getBytes(StandardCharsets.UTF_8));
		LootshareStorage.LoadResult oldProfile = storage.load(file); assertTrue(oldProfile.isWritable());
		LootshareSession oldSession = oldProfile.getState().getSessions().get(0);
		assertEquals(first, oldSession.getClosingPeriod());
		oldSession.markClosingPeriod(second, secondSettings, HostHistoryEvent.Reason.LOCAL_LEAVE);
		oldSession.clearClosingPeriod(); assertEquals(second, oldSession.getClosingPeriod());
	}

	private static LootshareSession session()
	{
		LootshareSession session = new LootshareSession("s", 77L, Instant.EPOCH);
		session.setHostState(1L, LootshareSettings.defaults(0), 1L); session.setHistoryMetadata(PERIOD, Collections.emptyMap(), false, false); return session;
	}
	private static LootshareParticipant member(long id) { return new LootshareParticipant(id, "Member " + id); }
	private static LootProposal accepted(String id, long owner, long value, List<LootshareParticipant> roster)
	{
		return LootProposal.pending(77L, owner, new SharedLootEvent(id, "Member " + owner, "Loot", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, value))))
			.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1), roster);
	}
	private static HostHistoryEvent settings(String id, long revision, SettlementSummary before, SettlementSummary after)
	{
		return new HostHistoryEvent(id, 77L, revision, PERIOD, Instant.ofEpochSecond(5), HostHistoryEvent.Kind.SETTINGS_APPLIED,
			HostHistoryEvent.Reason.SETTINGS, LootshareSettings.defaults(0), LootshareSettings.defaults(750), before, after, true, null);
	}
	private static HostHistoryEvent closure(String id, long revision, HostPeriod period, SettlementSummary summary, boolean complete)
	{
		return new HostHistoryEvent(id, 77L, revision, period, period.getEndedAt() == null ? Instant.EPOCH : period.getEndedAt(),
			HostHistoryEvent.Kind.PERIOD_CLOSED, HostHistoryEvent.Reason.TRANSFER, LootshareSettings.defaults(0), LootshareSettings.defaults(0), summary, summary, complete, null);
	}
	private interface CheckedAction { void run() throws Exception; }
	private static void expectInvalid(CheckedAction action) throws Exception
	{
		try { action.run(); fail("Expected invalid history to be rejected"); }
		catch (IllegalArgumentException expected) { }
	}
}
