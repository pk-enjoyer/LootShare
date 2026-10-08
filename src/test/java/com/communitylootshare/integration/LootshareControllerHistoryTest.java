/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.integration;

import com.communitylootshare.LootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.domain.*;
import com.communitylootshare.party.*;
import com.communitylootshare.persistence.LootshareStorage;
import com.communitylootshare.sessions.LootshareCalculator;
import com.communitylootshare.sessions.LootshareEngine;
import com.communitylootshare.sessions.LootshareEngine.MutationResult;
import com.google.gson.Gson;
import java.io.File;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.PartyMessage;
import net.runelite.client.party.messages.UserSync;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class LootshareControllerHistoryTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test public void configurationEditsWaitForExplicitApplicationAndRejectStaleClicks() throws Exception
	{
		Fixture f = new Fixture(1L, 100); f.controller.start(); f.controller.setMemberApproved(2L, true);
		f.controller.addManualGp(1L, 1001L);
		long revision = f.controller.getActiveHostRevision();
		List<LootshareParticipant> roster = f.engine.getActiveSession().get().getAcceptedProposals().get(0).getParticipants();
		when(f.config.minimumSharedLootValue()).thenReturn(2000);
		when(f.config.lootValueBasis()).thenReturn(LootValueBasis.HIGH_ALCHEMY);
		f.outbox.clear(); f.controller.onLocalConfigurationChanged();
		assertTrue(f.controller.canApplyMySettings());
		assertEquals(1001L, f.controller.getActiveCalculation().getTotalAcceptedValue());
		assertTrue(f.engine.getActiveSession().get().getHistoryEvents().isEmpty());
		assertTrue(f.outbox.isEmpty());
		assertEquals(MutationResult.CONFLICT, f.controller.applyMySettings(78L, 1L, revision));
		assertEquals(MutationResult.CONFLICT, f.controller.applyMySettings(77L, 2L, revision));
		assertEquals(MutationResult.CONFLICT, f.controller.applyMySettings(77L, 1L, revision - 1));
		assertEquals(MutationResult.APPLIED, f.controller.applyMySettings(77L, 1L, revision));
		assertEquals(revision + 1, f.controller.getActiveHostRevision());
		assertEquals(0L, f.controller.getActiveCalculation().getTotalAcceptedValue());
		HostHistoryEvent event = f.engine.getActiveSession().get().getHistoryEvents().get(0);
		assertEquals(1001L, event.getBefore().getTotal()); assertEquals(0L, event.getAfter().getTotal());
		assertEquals(roster, f.engine.getActiveSession().get().getAcceptedProposals().get(0).getParticipants());
		assertEquals(1001L, f.engine.getActiveSession().get().getAcceptedProposals().get(0).getEvent().getTotal());
		assertTrue(event.verifies()); assertFalse(f.controller.canApplyMySettings());
		int messages = f.outbox.size();
		assertEquals(MutationResult.DUPLICATE, f.controller.applyMySettings(77L, 1L, revision + 1));
		assertEquals(messages, f.outbox.size());
		assertEquals(1, f.engine.getActiveSession().get().getHistoryEvents().size());
		ArgumentCaptor<QueuedMessage> notices = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(f.chat).queue(notices.capture());
		assertTrue(notices.getValue().getRuneLiteFormattedMessage().contains("100 → 2,000 gp"));
		assertTrue(notices.getValue().getRuneLiteFormattedMessage().contains("High alchemy"));
		assertTrue(notices.getValue().getRuneLiteFormattedMessage().contains("saved history unchanged"));
	}

	@Test public void deliberateTransferResolvesPendingUnderOutgoingPolicyAndInheritsSettings() throws Exception
	{
		Fixture source = new Fixture(1L, 100); source.controller.start(); source.controller.setMemberApproved(2L, true);
		HostPeriod first = source.engine.getActiveSession().get().getHostPeriod();
		LootProposal pending = proposal("pending-at-transfer", 2L, 1001L);
		assertEquals(MutationResult.APPLIED, source.engine.addProposal(pending));
		when(source.config.minimumSharedLootValue()).thenReturn(999999);
		assertEquals(MutationResult.APPLIED, source.controller.transferHost(2L));
		LootshareSession transferred = source.engine.getActiveSession().get();
		assertEquals(100L, transferred.getMinimumSharedLootValue());
		assertEquals(1001L, source.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(MemberApprovalStatus.APPROVED, transferred.getMemberApprovalStatus(1L));
		assertEquals(first.getPeriodId(), transferred.getHostPeriod().getPredecessorId());
		HostHistoryEvent checkpoint = transferred.getHistoryEvents().get(0);
		assertEquals(HostHistoryEvent.Reason.TRANSFER, checkpoint.getReason());
		assertEquals(1001L, checkpoint.getAfter().getTotal()); assertTrue(checkpoint.isComplete());
		assertEquals(LootProposalStatus.ACCEPTED, transferred.getAcceptedProposals().get(0).getStatus());

		Fixture next = new Fixture(2L, 2000); next.controller.start(); announce(source, next, 1L);
		assertFalse(next.controller.canApplyMySettings());
		assertEquals(MutationResult.CONFLICT, next.controller.applyMySettings(77L, 2L, next.controller.getActiveHostRevision()));
		UserSync sync = new UserSync(); sync.setMemberId(2L); source.outbox.clear(); source.controller.onUserSync(sync);
		for (PartyMessage message : new ArrayList<>(source.outbox)) { deliver(message, 1L, next); }
		complete(next, 3L);
		assertEquals(100L, next.controller.getActiveHostSettings().get().getMinimumSharedLootValue());
		assertEquals(1001L, next.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(checkpoint.commitment(), next.engine.getActiveSession().get().getHistoryEvents().get(0).commitment());
		assertTrue(next.controller.canApplyMySettings());
		assertEquals(MutationResult.APPLIED, next.controller.applyMySettings(77L, 2L, next.controller.getActiveHostRevision()));
		assertEquals(0L, next.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(1001L, next.engine.getActiveSession().get().getHistoryEvents().get(0).getAfter().getTotal());
		assertEquals(MutationResult.APPLIED, next.controller.transferHost(3L));
		assertEquals(3L, next.controller.getActiveHostMemberId());
		assertEquals(2, next.engine.getActiveSession().get().getHistoryEvents().stream()
			.filter(e -> e.getKind() == HostHistoryEvent.Kind.PERIOD_CLOSED).count());
	}

	@Test public void departureRecoveryFreezesIncompleteCheckpointAndLateLootOnlyImprovesActiveLedger() throws Exception
	{
		Fixture source = new Fixture(1L, 100); source.controller.start(); source.controller.setMemberApproved(2L, true);
		source.controller.addManualGp(1L, 1001L);
		LootProposal original = source.engine.getActiveSession().get().getAcceptedProposals().get(0);
		Fixture successor = new Fixture(2L, 2000); successor.controller.start(); announce(source, successor, 1L);
		HostPeriod old = successor.engine.getActiveSession().get().getHostPeriod();
		successor.depart(1L);
		assertEquals(2L, successor.controller.getActiveHostMemberId());
		assertEquals(100L, successor.controller.getActiveHostSettings().get().getMinimumSharedLootValue());
		assertEquals(MemberApprovalStatus.PENDING, successor.controller.getMemberApprovalStatus(1L));
		assertTrue(successor.engine.getActiveSession().get().getHistoryEvents().isEmpty());
		assertEquals(MutationResult.APPLIED, successor.controller.addManualGp(2L, 1500L));
		assertEquals(0, successor.engine.getActiveSession().get().getAcceptedProposals().size());
		assertFalse(successor.controller.canApplyMySettings());
		successor.timeouts.get(successor.timeouts.size() - 1).run();
		LootshareSession recovered = successor.engine.getActiveSession().get();
		HostHistoryEvent checkpoint = recovered.getHistoryEvents().get(0);
		assertFalse(checkpoint.isComplete()); assertEquals(old.getPeriodId(), checkpoint.getPeriod().getPeriodId());
		assertEquals(0L, checkpoint.getAfter().getTotal()); assertEquals(HostHistoryEvent.Reason.DEPARTURE, checkpoint.getReason());
		assertEquals(1500L, successor.controller.getActiveCalculation().getTotalAcceptedValue());
		RecoveryMessage late = new RecoveryMessage(2L, original); late.setMemberId(3L); successor.controller.onRecoveryMessage(late);
		assertEquals(2501L, successor.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(0L, successor.engine.getActiveSession().get().getHistoryEvents().get(0).getAfter().getTotal());
		assertEquals(original.getParticipants(), successor.engine.getProposal(original.getProposalId()).get().getParticipants());
		assertTrue(successor.controller.getActiveCalculation().getBalances().stream().anyMatch(b -> b.getMemberId() == 1L));
		long revision = successor.controller.getActiveHostRevision();
		successor.controller.onUserPart(new UserPart(1L));
		assertEquals(revision, successor.controller.getActiveHostRevision());
		verify(successor.chat, times(2)).queue(any(QueuedMessage.class));
		assertTrue(successor.storage.load(successor.file).isWritable());
	}

	@Test public void completeRecoveryIncludesCommittedAuditsAndRejectsFormerHostFabrications() throws Exception
	{
		Fixture source = new Fixture(1L, 100); source.controller.start(); source.controller.setMemberApproved(2L, true);
		source.controller.addManualGp(1L, 1001L);
		when(source.config.minimumSharedLootValue()).thenReturn(750);
		source.controller.applyMySettings(77L, 1L, source.controller.getActiveHostRevision());
		HostHistoryEvent audit = source.engine.getActiveSession().get().getHistoryEvents().get(0);
		LootProposal original = source.engine.getActiveSession().get().getAcceptedProposals().get(0);
		Fixture next = new Fixture(2L, 1); next.controller.start(); announce(source, next, 1L); next.depart(1L);
		RecoveryMessage recovery = new RecoveryMessage(2L, original); recovery.setMemberId(3L); next.controller.onRecoveryMessage(recovery);
		for (HistoryMessage chunk : source.storage.historyMessages(audit, 2L, false)) { deliver(chunk, 3L, next); }
		complete(next, 3L);
		HostHistoryEvent checkpoint = next.engine.getActiveSession().get().getHistoryEvents().stream()
			.filter(event -> event.getKind() == HostHistoryEvent.Kind.PERIOD_CLOSED).findFirst().get();
		assertTrue(checkpoint.isComplete()); assertEquals(1001L, checkpoint.getAfter().getTotal());
		assertEquals(750L, checkpoint.getOldSettings().getMinimumSharedLootValue());
		assertEquals(2, next.engine.getActiveSession().get().getHistoryEvents().size());
		// A former host can sign more events with its trusted key, but cannot authorize them now.
		KeyPair formerKey = source.storage.decisionKey(source.file);
		HostHistoryEvent fabricated = new HostHistoryEvent("forged-late", 77L, audit.getRevision(), audit.getPeriod(), audit.getOccurredAt(),
			audit.getKind(), audit.getReason(), audit.getOldSettings(), audit.getNewSettings(), audit.getBefore(), audit.getAfter(), true, null).sign(1L, formerKey);
		for (HistoryMessage chunk : source.storage.historyMessages(fabricated, 2L, true)) { deliver(chunk, 3L, next); }
		assertEquals(2, next.engine.getActiveSession().get().getHistoryEvents().size());
		HostHistoryEvent conflicting = new HostHistoryEvent(checkpoint.getEventId(), 77L, checkpoint.getRevision(), checkpoint.getPeriod(), checkpoint.getOccurredAt(),
			checkpoint.getKind(), checkpoint.getReason(), checkpoint.getOldSettings(), checkpoint.getNewSettings(), checkpoint.getBefore(), checkpoint.getAfter(), false, null).sign(1L, formerKey);
		for (HistoryMessage chunk : source.storage.historyMessages(conflicting, 2L, false)) { deliver(chunk, 3L, next); }
		assertTrue(next.engine.getActiveSession().get().getHistoryEvents().stream().filter(e -> e.getEventId().equals(checkpoint.getEventId())).findFirst().get().isComplete());
	}

	@Test public void liveAuditCanArriveBeforeHostSnapshotAndReplayDoesNotRepeatNotices() throws Exception
	{
		Fixture source = new Fixture(1L, 100); source.controller.start();
		Fixture guest = new Fixture(2L, 1000); guest.controller.start(); announce(source, guest, 1L);
		when(source.config.minimumSharedLootValue()).thenReturn(2000);
		source.controller.applyMySettings(77L, 1L, source.controller.getActiveHostRevision());
		HostHistoryEvent event = source.engine.getActiveSession().get().getHistoryEvents().get(0);
		List<HistoryMessage> chunks = source.storage.historyMessages(event, 0L, true);
		Collections.reverse(chunks);
		for (HistoryMessage chunk : chunks) { deliver(chunk, 1L, guest); deliver(chunk, 1L, guest); }
		assertTrue(guest.engine.getActiveSession().get().getHistoryEvents().isEmpty());
		announce(source, guest, 1L);
		assertEquals(1, guest.engine.getActiveSession().get().getHistoryEvents().size());
		verify(guest.chat).queue(any(QueuedMessage.class));
		for (HistoryMessage chunk : source.storage.historyMessages(event, 0L, false)) { deliver(chunk, 1L, guest); }
		announce(source, guest, 1L);
		verify(guest.chat).queue(any(QueuedMessage.class));
		Fixture lateJoiner = new Fixture(3L, 1); lateJoiner.controller.start(); announce(source, lateJoiner, 1L);
		for (HistoryMessage chunk : source.storage.historyMessages(event, 3L, false)) { deliver(chunk, 1L, lateJoiner); }
		assertEquals(1, lateJoiner.engine.getActiveSession().get().getHistoryEvents().size());
		verify(lateJoiner.chat, never()).queue(any(QueuedMessage.class));
	}

	@Test public void restartAndLocalRejoinRetainSettingsHistoryAndHostIdentity() throws Exception
	{
		Fixture f = new Fixture(1L, 100); f.controller.start(); f.controller.setMemberApproved(2L, true);
		f.controller.addManualGp(1L, 1001L);
		when(f.config.minimumSharedLootValue()).thenReturn(500);
		f.controller.applyMySettings(77L, 1L, f.controller.getActiveHostRevision());
		HostPeriod period = f.engine.getActiveSession().get().getHostPeriod();
		f.controller.stop(); when(f.config.minimumSharedLootValue()).thenReturn(2000); f.controller.start();
		assertEquals(period, f.engine.getActiveSession().get().getHostPeriod());
		assertEquals(500L, f.controller.getActiveHostSettings().get().getMinimumSharedLootValue());
		assertEquals(1001L, f.controller.getActiveCalculation().getTotalAcceptedValue());
		complete(f, 2L); complete(f, 3L);
		f.controller.transferHost(2L);
		String sessionId = f.engine.getActiveSession().get().getSessionId();
		when(f.party.isInParty()).thenReturn(false); f.controller.onPartyChanged(new PartyChanged(null, null));
		assertEquals(2, f.controller.getHistory().get(0).getHistoryEvents().size());
		when(f.party.isInParty()).thenReturn(true); f.controller.onPartyChanged(new PartyChanged("pass", 77L));
		assertEquals(2L, f.controller.getActiveHostMemberId());
		assertEquals(sessionId, f.engine.getActiveSession().get().getSessionId());
		assertEquals(500L, f.engine.getActiveSession().get().getMinimumSharedLootValue());
		assertEquals(2, f.engine.getActiveSession().get().getHistoryEvents().size());
		assertEquals(MutationResult.NOT_HOST, f.controller.applyMySettings(77L, 1L, f.controller.getActiveHostRevision()));
	}

	@Test public void returningFormerHostWaitsForLiveAuthorityInsteadOfReclaimingFromItsCache() throws Exception
	{
		Fixture former = new Fixture(1L, 100); former.controller.start(); former.controller.setMemberApproved(2L, true);
		Fixture successor = new Fixture(2L, 2000); successor.controller.start(); announce(former, successor, 1L);
		when(former.party.isInParty()).thenReturn(false);
		former.controller.onPartyChanged(new PartyChanged(null, null)); former.controller.stop();
		successor.depart(1L); successor.timeouts.get(successor.timeouts.size() - 1).run();
		former.members.removeIf(member -> member.getMemberId() == 1L); former.members.add(former.local);
		when(former.party.isInParty()).thenReturn(true); former.controller.start();
		assertEquals(0L, former.controller.getActiveHostMemberId()); assertFalse(former.controller.canApplyMySettings());
		announce(successor, former, 2L);
		assertEquals(2L, former.controller.getActiveHostMemberId());
		assertEquals(100L, former.controller.getActiveHostSettings().get().getMinimumSharedLootValue());
		for (HostHistoryEvent event : successor.engine.getActiveSession().get().getHistoryEvents())
		{
			for (HistoryMessage chunk : successor.storage.historyMessages(event, 1L, false)) { deliver(chunk, 2L, former); }
		}
		assertNull(former.engine.getActiveSession().get().getClosingPeriod());
	}

	@Test public void loggingOutOfOsrsWithinPartyDoesNotCloseHostPeriod() throws Exception
	{
		Fixture host = new Fixture(1L, 100); host.controller.start();
		HostPeriod period = host.engine.getActiveSession().get().getHostPeriod();
		host.local.setLoggedIn(false); host.controller.onLocalConfigurationChanged();
		assertEquals(1L, host.controller.getActiveHostMemberId());
		assertEquals(period, host.engine.getActiveSession().get().getHostPeriod());
		assertTrue(host.engine.getActiveSession().get().getHistoryEvents().isEmpty());
		verify(host.chat, never()).queue(any(QueuedMessage.class));
	}

	@Test public void failedWriteSurfacesNoticeAndPreservesLastGoodHistory() throws Exception
	{
		Fixture f = new Fixture(1L, 100); f.controller.start(); f.controller.addManualGp(1L, 1000L);
		byte[] lastGood = java.nio.file.Files.readAllBytes(f.file.toPath());
		LootshareStorage failing = new LootshareStorage(f.file, new Gson())
		{
			@Override public boolean save(File file, LootshareState state) { return false; }
		};
		LootshareController controller = new LootshareController(f.client, f.thread, f.party, f.config, f.executor,
			mock(LootCaptureService.class), f.engine, new LootshareCalculator(), failing);
		controller.start(); completeController(controller, 2L, 1L); completeController(controller, 3L, 1L);
		controller.addManualGp(1L, 1500L);
		assertNotNull(controller.getPersistenceNotice());
		assertArrayEquals(lastGood, java.nio.file.Files.readAllBytes(f.file.toPath()));
	}

	@Test public void consecutiveDeparturesArchiveEveryHostBoundary() throws Exception
	{
		Fixture first = new Fixture(1L, 100); first.controller.start();
		Fixture second = new Fixture(2L, 2000); second.controller.start(); announce(first, second, 1L);
		Fixture third = new Fixture(3L, 3000); third.controller.start(); announce(first, third, 1L);
		HostPeriod firstPeriod = first.engine.getActiveSession().get().getHostPeriod();
		second.depart(1L); third.depart(1L); announce(second, third, 2L);
		HostPeriod secondPeriod = second.engine.getActiveSession().get().getHostPeriod();
		assertTrue(second.engine.getActiveSession().get().getHistoryEvents().isEmpty());
		third.depart(2L);
		LootshareSession session = third.engine.getActiveSession().get();
		assertEquals(3L, session.getHostMemberId());
		assertEquals(100L, session.getMinimumSharedLootValue());
		assertEquals(secondPeriod.getPeriodId(), session.getHostPeriod().getPredecessorId());
		assertEquals(2, session.getHistoryEvents().size());
		Set<String> closed = new HashSet<>();
		for (HostHistoryEvent event : session.getHistoryEvents())
		{
			closed.add(event.getPeriod().getPeriodId());
			assertEquals(HostHistoryEvent.Reason.DEPARTURE, event.getReason());
			assertEquals(100L, event.getOldSettings().getMinimumSharedLootValue());
			assertTrue(event.verifies());
		}
		assertEquals(new HashSet<>(Arrays.asList(firstPeriod.getPeriodId(), secondPeriod.getPeriodId())), closed);
		assertNull(session.getClosingPeriod());
		third.controller.onUserPart(new UserPart(2L));
		assertEquals(2, third.engine.getActiveSession().get().getHistoryEvents().size());
		assertEquals(2, third.storage.load(third.file).getState().getSessions().get(0).getHistoryEvents().size());
	}

	@Test public void historyRecoveredAfterGuestSyncIsRebroadcastWithoutLiveNotices() throws Exception
	{
		Fixture first = new Fixture(1L, 100); first.controller.start();
		when(first.config.minimumSharedLootValue()).thenReturn(750);
		first.controller.applyMySettings(77L, 1L, first.controller.getActiveHostRevision());
		HostHistoryEvent audit = first.engine.getActiveSession().get().getHistoryEvents().get(0);
		Fixture next = new Fixture(2L, 2000); next.controller.start(); announce(first, next, 1L); next.depart(1L);
		Fixture guest = new Fixture(3L, 3000); guest.controller.start(); announce(first, guest, 1L); guest.depart(1L);
		announce(next, guest, 2L);
		UserSync sync = new UserSync(); sync.setMemberId(3L);
		next.outbox.clear(); next.controller.onUserSync(sync);
		for (PartyMessage message : new ArrayList<>(next.outbox)) { deliver(message, 2L, guest); }
		assertTrue(guest.engine.getActiveSession().get().getHistoryEvents().isEmpty());
		clearInvocations(guest.chat); next.outbox.clear();
		for (HistoryMessage chunk : first.storage.historyMessages(audit, 2L, false)) { deliver(chunk, 3L, next); }
		assertTrue(next.outbox.stream().anyMatch(message -> message instanceof HistoryMessage && !((HistoryMessage) message).isLive()));
		for (PartyMessage message : new ArrayList<>(next.outbox)) { deliver(message, 2L, guest); }
		assertEquals(1, guest.engine.getActiveSession().get().getHistoryEvents().size());
		next.outbox.clear();
		for (HistoryMessage chunk : first.storage.historyMessages(audit, 2L, false)) { deliver(chunk, 3L, next); }
		assertTrue(next.outbox.isEmpty());
		complete(next, 3L);
		for (PartyMessage message : new ArrayList<>(next.outbox)) { deliver(message, 2L, guest); }
		assertEquals(2, guest.engine.getActiveSession().get().getHistoryEvents().size());
		verify(guest.chat, never()).queue(any(QueuedMessage.class));
	}

	@Test public void settingsAuditClampsTimeToInheritedPeriodStart() throws Exception
	{
		Fixture host = new Fixture(1L, 100); host.controller.start();
		Instant future = Instant.parse("2100-01-01T00:00:00Z");
		HostPeriod inherited = new HostPeriod("future-inherited", 1L, "Alice", "former-period", future, null);
		host.engine.setHostPeriod(inherited, false);
		when(host.config.minimumSharedLootValue()).thenReturn(750);
		assertEquals(MutationResult.APPLIED, host.controller.applyMySettings(77L, 1L, host.controller.getActiveHostRevision()));
		HostHistoryEvent audit = host.engine.getActiveSession().get().getHistoryEvents().get(0);
		assertEquals(future, audit.getOccurredAt()); assertEquals(inherited, audit.getPeriod()); assertTrue(audit.verifies());
	}

	@Test public void transferClampsTimeToInheritedPeriodStart() throws Exception
	{
		Fixture host = new Fixture(1L, 100); host.controller.start();
		Instant future = Instant.parse("2100-01-01T00:00:00Z");
		HostPeriod inherited = new HostPeriod("future-inherited", 1L, "Alice", "former-period", future, null);
		host.engine.setHostPeriod(inherited, false);
		assertEquals(MutationResult.APPLIED, host.controller.transferHost(2L));
		LootshareSession session = host.engine.getActiveSession().get();
		HostHistoryEvent checkpoint = session.getHistoryEvents().get(0);
		assertEquals(future, checkpoint.getOccurredAt()); assertEquals(future, checkpoint.getPeriod().getEndedAt());
		assertEquals(future, session.getHostPeriod().getStartedAt()); assertTrue(checkpoint.verifies());
	}

	private static void announce(Fixture source, Fixture target, long sender)
	{
		HostMessage snapshot = new HostMessage(source.engine.getActiveSession().get()); snapshot.setMemberId(sender); target.controller.onHostMessage(snapshot);
	}
	private static void complete(Fixture f, long sender) { completeController(f.controller, sender, f.local.getMemberId()); }
	private static void completeController(LootshareController controller, long sender, long target)
	{
		RecoveryMessage complete = RecoveryMessage.completed(target); complete.setMemberId(sender); controller.onRecoveryMessage(complete);
	}
	private static void deliver(PartyMessage message, long sender, Fixture target)
	{
		if (message instanceof HistoryMessage) { ((HistoryMessage) message).setMemberId(sender); target.controller.onHistoryMessage((HistoryMessage) message); }
		else if (message instanceof RecoveryMessage) { ((RecoveryMessage) message).setMemberId(sender); target.controller.onRecoveryMessage((RecoveryMessage) message); }
		else if (message instanceof HostMessage) { ((HostMessage) message).setMemberId(sender); target.controller.onHostMessage((HostMessage) message); }
		else if (message instanceof ProposalMessage) { ((ProposalMessage) message).setMemberId(sender); target.controller.onProposalMessage((ProposalMessage) message); }
		else if (message instanceof DecisionMessage) { ((DecisionMessage) message).setMemberId(sender); target.controller.onDecisionMessage((DecisionMessage) message); }
	}
	private static LootProposal proposal(String id, long owner, long amount)
	{
		return LootProposal.pending(77L, owner, new SharedLootEvent(id, "Member " + owner, "Loot", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, amount))));
	}
	private class Fixture
	{
		final Client client = mock(Client.class);
		final ClientThread thread = mock(ClientThread.class);
		final PartyService party = mock(PartyService.class);
		final LootshareConfig config = mock(LootshareConfig.class);
		final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		final ChatMessageManager chat = mock(ChatMessageManager.class);
		final LootshareEngine engine = new LootshareEngine();
		final List<PartyMessage> outbox = new ArrayList<>();
		final List<Runnable> timeouts = new ArrayList<>();
		final List<PartyMember> members = new ArrayList<>();
		final File file;
		final LootshareStorage storage;
		final LootshareController controller;
		final PartyMember local;
		Fixture(long memberId, int minimum) throws Exception
		{
			file = temporary.newFile(); storage = new LootshareStorage(file, new Gson());
			for (int id = 1; id <= 3; id++)
			{
				PartyMember member = new PartyMember(id); member.setDisplayName(id == 1 ? "Alice" : id == 2 ? "Bob" : "Charlie");
				member.setLoggedIn(true); members.add(member); when(party.getMemberById((long) id)).thenReturn(member);
			}
			local = members.get((int) memberId - 1);
			when(party.isInParty()).thenReturn(true); when(party.getPartyId()).thenReturn(77L);
			when(party.getMembers()).thenReturn(members); when(party.getLocalMember()).thenReturn(local);
			when(config.minimumSharedLootValue()).thenReturn(minimum); when(config.lootValueBasis()).thenReturn(LootValueBasis.GRAND_EXCHANGE);
			when(config.captureNpcLoot()).thenReturn(true); when(config.captureEventLoot()).thenReturn(true);
			when(config.capturePlayerLoot()).thenReturn(true); when(config.capturePickpocketLoot()).thenReturn(true); when(config.captureUnknownLoot()).thenReturn(true);
			doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
			doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(thread).invokeLater(any(Runnable.class));
			doAnswer(call -> { timeouts.add(call.getArgument(0)); return mock(ScheduledFuture.class); }).when(executor).schedule(any(Runnable.class), eq(3L), eq(TimeUnit.SECONDS));
			doAnswer(call -> { outbox.add(call.getArgument(0)); return null; }).when(party).send(any(PartyMessage.class));
			controller = new LootshareController(client, thread, party, config, executor, mock(LootCaptureService.class), engine, new LootshareCalculator(), storage);
			controller.setChatMessageManager(chat);
		}
		void depart(long memberId)
		{
			members.removeIf(m -> m.getMemberId() == memberId); when(party.getMemberById(memberId)).thenReturn(null);
			controller.onUserPart(new UserPart(memberId));
		}
	}
}
