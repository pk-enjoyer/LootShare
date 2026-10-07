/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.integration;

import com.communitylootshare.domain.LootshareSession;
import java.security.KeyPair;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentCaptor;

import com.communitylootshare.LootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.domain.*;
import com.communitylootshare.party.*;
import com.communitylootshare.persistence.LootshareStorage;
import com.communitylootshare.sessions.*;
import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class LootshareControllerRecoveryTest
{
	@Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test public void restartPreservesQueuedSnapshotWithRuneLiteExecutorOrdering() throws Exception
	{
		Fixture f = new Fixture();
		List<Runnable> tasks = new ArrayList<>();
		doAnswer(call -> { tasks.add(call.getArgument(0)); return null; })
			.when(f.executor).execute(any(Runnable.class));
		f.controller.start();
		runAll(tasks);
		assertEquals(0L, f.controller.getActiveCalculation().getTotalAcceptedValue());
		f.controller.addManualGp(1L, 1000L);
		assertEquals(1000L, f.controller.getActiveCalculation().getTotalAcceptedValue());
		f.controller.stop();
		f.controller.start();
		// RuneLite injects a single-thread scheduled executor, so drain the save before the load.
		assertEquals(2, tasks.size());
		runAll(tasks);
		assertEquals("Restart loaded the older on-disk ledger", 1000L,
			f.controller.getActiveCalculation().getTotalAcceptedValue());
	}

	@Test public void returningGuestCanConfirmTransferredHostAfterItsPersistedHostHasLeft() throws Exception
	{
		Fixture f = new Fixture();
		PartyMember first = member(2L, "Bob");
		PartyMember guest = member(3L, "Charlie");
		PartyMember host = member(4L, "Diana");
		f.engine.enterParty(77L, "old", Instant.EPOCH);
		f.engine.updateHostState(1L, 1L, 100L, 1L);
		assertTrue(f.storage.save(f.file, f.engine.snapshot()));
		when(f.party.getLocalMember()).thenReturn(guest);
		when(f.party.getMembers()).thenReturn(Arrays.asList(first, host, guest));
		when(f.party.getMemberById(1L)).thenReturn(null);
		when(f.party.getMemberById(2L)).thenReturn(first);
		when(f.party.getMemberById(3L)).thenReturn(guest);
		when(f.party.getMemberById(4L)).thenReturn(host);
		f.controller.start();
		HostMessage announcement = new HostMessage(4L, 100L, 3L);
		announcement.setMemberId(4L);
		f.controller.onHostMessage(announcement);
		HostMessage confirmation = new HostMessage(4L, 100L, 3L);
		confirmation.setMemberId(2L);
		f.controller.onHostMessage(confirmation);
		assertEquals("Both the live host and first member confirmation were ignored", 4L,
			f.controller.getActiveHostMemberId());
		assertTrue(f.controller.getActiveHostSettings().isPresent());
	}

	@Test public void invalidDomainHistoryMustNotBeReplacedOnShutdown() throws Exception
	{
		Fixture f = new Fixture();
		String invalid = "{\"schemaVersion\":5,\"activeSessionId\":\"s\",\"sessions\":["
			+ "{\"sessionId\":\"s\",\"partyId\":77,\"startedAt\":null,\"acceptedProposals\":[]}]}";
		byte[] original = invalid.getBytes(StandardCharsets.UTF_8);
		Files.write(f.file.toPath(), original);
		f.controller.start();
		f.controller.stop();
		assertArrayEquals("Domain-invalid history was silently replaced with an empty ledger",
			original, Files.readAllBytes(f.file.toPath()));
	}

	@Test public void decisionDuringLoadCompletionCannotOvertakeItsDeferredProposal() throws Exception
	{
		Fixture f = new Fixture();
		when(f.party.getLocalMember()).thenReturn(member(2L, "Bob"));
		LootProposal accepted = proposal("deferred", 2L, 1000L).decide(
			LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1),
			Arrays.asList(new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
		DecisionMessage decision = new DecisionMessage(accepted);
		decision.setMemberId(1L);
		List<Runnable> tasks = new ArrayList<>();
		boolean[] deliverDecisionOnSaveScheduling = {false};
		doAnswer(call -> {
			if (deliverDecisionOnSaveScheduling[0])
			{
				deliverDecisionOnSaveScheduling[0] = false;
				// The websocket callback arrives after host/proposal, but before deferred replay.
				f.controller.onDecisionMessage(decision);
			}
			tasks.add(call.getArgument(0));
			return null;
		}).when(f.executor).execute(any(Runnable.class));
		f.controller.start();
		assertFalse(f.controller.isReady());
		HostMessage host = new HostMessage(1L, 100L, 1L);
		host.setMemberId(1L);
		f.controller.onHostMessage(host);
		ProposalMessage proposal = new ProposalMessage(accepted);
		proposal.setMemberId(2L);
		f.controller.onProposalMessage(proposal);
		deliverDecisionOnSaveScheduling[0] = true;
		runAll(tasks);
		assertEquals("Readiness was exposed before deferred host/proposal replay, losing the newer decision",
			LootProposalStatus.ACCEPTED, f.engine.getProposal("deferred").get().getStatus());
	}

	@Test public void recoveringHostMustReceiveFinalizedHistoryFromASynchronizedGuest() throws Exception
	{
		Fixture guest = new Fixture();
		guest.controller.start();
		guest.controller.addManualGp(1L, 1000L);
		guest.controller.transferHost(2L);
		assertEquals(1000L, guest.controller.getActiveCalculation().getTotalAcceptedValue());

		Fixture recoveringHost = new Fixture();
		when(recoveringHost.party.getLocalMember()).thenReturn(member(2L, "Bob"));
		recoveringHost.controller.start();
		HostMessage confirmation = new HostMessage(2L, guest.engine.getActiveHostSettings().get(),
			guest.engine.getActiveHostRevision(), guest.engine.getActiveMemberApprovalStatuses(),
			guest.engine.getActiveDecisionKeys(), guest.engine.getActiveDecisionAuthorizations());
		confirmation.setMemberId(1L);
		recoveringHost.controller.onHostMessage(confirmation);
		assertEquals(2L, recoveringHost.controller.getActiveHostMemberId());

		// Replay the guest's real synchronization response into the recovering host.
		doAnswer(call -> {
			Object sent = call.getArgument(0);
			if (sent instanceof ProposalMessage)
			{
				((ProposalMessage) sent).setMemberId(1L);
				recoveringHost.controller.onProposalMessage((ProposalMessage) sent);
			}
			else if (sent instanceof DecisionMessage)
			{
				((DecisionMessage) sent).setMemberId(1L);
				recoveringHost.controller.onDecisionMessage((DecisionMessage) sent);
			}
			else if (sent instanceof RecoveryMessage)
			{
				((RecoveryMessage) sent).setMemberId(1L);
				recoveringHost.controller.onRecoveryMessage((RecoveryMessage) sent);
			}
			return null;
		}).when(guest.party).send(any(net.runelite.client.party.messages.PartyMessage.class));
		net.runelite.client.party.messages.UserSync request = new net.runelite.client.party.messages.UserSync();
		request.setMemberId(2L);
		guest.controller.onUserSync(request);
		assertEquals("Guest has finalized history, but host sync never restores it", 1000L,
			recoveringHost.controller.getActiveCalculation().getTotalAcceptedValue());
	}

	@Test public void anyLivePeerCanRecoverSignedHistoryForADepartedExcludedOwner() throws Exception
	{
		Fixture source = new Fixture();
		source.controller.start();
		source.controller.setMemberApproved(2L, true);
		source.controller.addManualGp(1L, 1001L);
		LootProposal original = source.engine.getActiveSession().get().getAcceptedProposals().get(0);
		source.controller.transferHost(2L);
		Fixture host = recoveringHost(source, false);
		assertEquals(null, host.party.getMemberById(1L));
		assertEquals(MemberApprovalStatus.PENDING, host.controller.getMemberApprovalStatus(1L));
		RecoveryMessage recovered = new RecoveryMessage(2L, original);
		recovered.setMemberId(3L);
		host.controller.onRecoveryMessage(recovered);
		RecoveryMessage completed = RecoveryMessage.completed(2L);
		completed.setMemberId(3L);
		host.controller.onRecoveryMessage(completed);
		assertEquals(original.getParticipants(), host.engine.getProposal(original.getProposalId()).get().getParticipants());
		assertEquals(1001L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(501L, host.controller.getActiveCalculation().getBalances().get(0).getEntitledValue());
		assertEquals(500L, host.controller.getActiveCalculation().getTransfers().get(0).getAmount());
		LootshareEngine restored = new LootshareEngine();
		restored.restore(host.storage.load(host.file).getState());
		assertTrue(restored.getProposal(original.getProposalId()).get().getDecisionReceipt().verifies(original));
	}

	@Test public void rejectsForgedUntrustedUnsignedAndMisaddressedGuestRecovery() throws Exception
	{
		Fixture source = new Fixture();
		source.controller.start();
		source.controller.addManualGp(1L, 1000L);
		LootProposal original = source.engine.getActiveSession().get().getAcceptedProposals().get(0);
		source.controller.transferHost(2L);
		Fixture host = recoveringHost(source, false);
		LootProposal forged = LootProposal.pending(77L, 1L, new SharedLootEvent(original.getProposalId(), "Alice", "Manual GP",
			original.getEvent().getCapturedAt(), Collections.singletonList(new SharedLootItem(0, 0, 1L, 9000L))))
			.decide(LootProposalStatus.ACCEPTED, original.getDecidedAt(), original.getParticipants())
			.withDecisionReceipt(original.getDecisionReceipt());
		KeyPair untrustedKey = LootDecisionReceipt.generateKey();
		LootProposal untrusted = original.withDecisionReceipt(LootDecisionReceipt.sign(3L, untrustedKey, original));
		for (LootProposal invalid : Arrays.asList(forged, untrusted, original.withDecisionReceipt(null)))
		{
			RecoveryMessage message = new RecoveryMessage(2L, invalid);
			message.setMemberId(3L);
			host.controller.onRecoveryMessage(message);
			assertEquals(0L, host.controller.getActiveCalculation().getTotalAcceptedValue());
			assertFalse(host.engine.getProposal(original.getProposalId()).isPresent());
		}
		RecoveryMessage wrongTarget = new RecoveryMessage(3L, original);
		wrongTarget.setMemberId(3L);
		host.controller.onRecoveryMessage(wrongTarget);
		DecisionMessage unauthorizedDecision = new DecisionMessage(original);
		unauthorizedDecision.setMemberId(3L);
		host.controller.onDecisionMessage(unauthorizedDecision);
		assertEquals(0L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		RecoveryMessage valid = new RecoveryMessage(2L, original);
		valid.setMemberId(3L);
		host.controller.onRecoveryMessage(valid);
		assertEquals(1000L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		host.controller.onRecoveryMessage(valid);
		assertEquals(1, host.engine.getActiveSession().get().getAcceptedProposals().size());
	}

	@Test public void legacyUnsignedRecoveryRequiresTheBootstrapMemberDuringRecovery() throws Exception
	{
		Fixture source = new Fixture();
		source.controller.start();
		source.controller.addManualGp(1L, 1000L);
		LootProposal legacy = source.engine.getActiveSession().get().getAcceptedProposals().get(0).withDecisionReceipt(null);
		source.controller.transferHost(2L);
		Fixture host = recoveringHost(source, true);
		RecoveryMessage message = new RecoveryMessage(2L, legacy);
		message.setMemberId(3L);
		host.controller.onRecoveryMessage(message);
		assertEquals(0L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		message.setMemberId(1L);
		host.controller.onRecoveryMessage(message);
		assertEquals(1000L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		LootProposal recovered = host.engine.getProposal(legacy.getProposalId()).get();
		assertEquals(legacy.getParticipants(), recovered.getParticipants());
		assertTrue(recovered.getDecisionReceipt().verifies(recovered));
		assertEquals(2L, recovered.getDecisionReceipt().getSignerMemberId());
	}

	@Test public void recoveryTimeoutIsCancelledAndCannotResumeAfterShutdown() throws Exception
	{
		Fixture source = new Fixture();
		source.controller.start();
		source.controller.transferHost(2L);
		Fixture host = recoveringHost(source, false);
		ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
		verify(host.executor).schedule(timeout.capture(), eq(3L), eq(TimeUnit.SECONDS));
		host.controller.addManualGp(2L, 1000L);
		assertEquals(1, host.controller.getPendingOwnedProposals().size());
		host.controller.stop();
		verify(host.timeout).cancel(false);
		clearInvocations(host.party);
		timeout.getValue().run();
		verify(host.party, never()).send(any(net.runelite.client.party.messages.PartyMessage.class));
		assertEquals(1, host.engine.getPendingOwnedBy(2L).size());
	}

	@Test public void recoveryCompletesBeforePendingLootIsDecidedAndTimeoutProvidesAFallback() throws Exception
	{
		Fixture source = new Fixture();
		source.controller.start();
		source.controller.transferHost(2L);
		Fixture host = recoveringHost(source, false);
		host.controller.addManualGp(2L, 1000L);
		assertEquals(0L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
		verify(host.executor).schedule(timeout.capture(), eq(3L), eq(TimeUnit.SECONDS));
		timeout.getValue().run();
		assertTrue(host.controller.getPendingOwnedProposals().isEmpty());
		assertEquals(1000L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		verify(host.timeout).cancel(false);
	}

	@Test public void firstHostRecoversALostLedgerWithoutReplacingItsSigningIdentity() throws Exception
	{
		Fixture host = new Fixture();
		host.controller.start();
		host.controller.setMemberApproved(2L, true);
		host.controller.addManualGp(1L, 1001L);
		LootProposal accepted = host.engine.getActiveSession().get().getAcceptedProposals().get(0);
		String key = accepted.getDecisionReceipt().getPublicKey();
		host.controller.stop();
		Files.write(host.file.toPath(), new byte[0]);
		host.controller.start();
		assertEquals(0L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		RecoveryMessage recovery = new RecoveryMessage(1L, accepted);
		recovery.setMemberId(2L);
		host.controller.onRecoveryMessage(recovery);
		RecoveryMessage completed = RecoveryMessage.completed(1L);
		completed.setMemberId(2L);
		host.controller.onRecoveryMessage(completed);
		assertEquals(1001L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(2, host.engine.getProposal(accepted.getProposalId()).get().getParticipants().size());
		host.controller.addManualGp(1L, 1000L);
		assertEquals(2001L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		for (LootProposal proposal : host.engine.getActiveSession().get().getAcceptedProposals())
		{
			assertEquals(key, proposal.getDecisionReceipt().getPublicKey());
		}
	}

	@Test public void cachedTransferOverridesAnOlderPersistedHostOnRestart() throws Exception
	{
		Fixture host = new Fixture();
		host.controller.start();
		byte[] staleLedger = Files.readAllBytes(host.file.toPath());
		host.controller.transferHost(2L);
		host.controller.stop();
		Files.write(host.file.toPath(), staleLedger);
		host.controller.start();
		assertEquals(2L, host.controller.getActiveHostMemberId());
	}

	@Test public void formerHostCannotInventHistoryDuringOrAfterRecovery() throws Exception
	{
		Fixture source = new Fixture();
		source.controller.start();
		KeyPair formerKey = source.storage.decisionKey(source.file);
		source.controller.transferHost(2L);
		Fixture host = recoveringHost(source, true);
		assertFalse(host.engine.getActiveHostSettings().get().isAllowMemberManualGp());
		assertEquals(MemberApprovalStatus.PENDING, host.controller.getMemberApprovalStatus(3L));
		for (boolean complete : Arrays.asList(false, true))
		{
			if (complete)
			{
				ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
				verify(host.executor).schedule(timeout.capture(), eq(3L), eq(TimeUnit.SECONDS));
				timeout.getValue().run();
			}
			// Backdating does not prove that this decision existed before the transfer.
			LootProposal invented = LootProposal.pending(77L, 3L, new SharedLootEvent(
				"invented-" + complete, "Charlie", "Manual GP", Instant.EPOCH,
				Collections.singletonList(new SharedLootItem(0, 0, 1L, 9000L))))
				.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1),
					Collections.singletonList(new LootshareParticipant(3L, "Charlie")));
			invented = invented.withDecisionReceipt(LootDecisionReceipt.sign(1L, formerKey, invented));
			assertTrue(invented.getDecisionReceipt().isTrustedBy(host.engine.getActiveDecisionKeys()));
			RecoveryMessage message = new RecoveryMessage(2L, invented);
			message.setMemberId(1L);
			host.controller.onRecoveryMessage(message);
			assertFalse("Former host introduced an unauthorized decision", host.engine.getProposal(invented.getProposalId()).isPresent());
			assertEquals(0L, host.controller.getActiveCalculation().getTotalAcceptedValue());
		}
	}

	@Test public void synchronizedGuestRequestsHistoryWhenAuthorityTransfersToIt() throws Exception
	{
		Fixture guest = synchronizedGuest();
		clearInvocations(guest.party);
		HostMessage transfer = new HostMessage(2L, guest.engine.getActiveHostSettings().get(),
			guest.engine.getActiveHostRevision() + 1L, guest.engine.getActiveMemberApprovalStatuses(),
			guest.engine.getActiveDecisionKeys(), guest.engine.getActiveDecisionAuthorizations());
		transfer.setMemberId(1L);
		guest.controller.onHostMessage(transfer);
		assertEquals(2L, guest.controller.getActiveHostMemberId());
		verify(guest.party).send(any(net.runelite.client.party.messages.UserSync.class));
	}

	@Test public void automaticSuccessorRequestsHistoryWhenPreviousHostLeaves() throws Exception
	{
		Fixture guest = synchronizedGuest();
		PartyMember local = member(2L, "Bob");
		PartyMember peer = member(3L, "Charlie");
		when(guest.party.getMembers()).thenReturn(Arrays.asList(local, peer));
		when(guest.party.getMemberById(1L)).thenReturn(null);
		when(guest.party.getMemberById(3L)).thenReturn(peer);
		clearInvocations(guest.party);
		net.runelite.client.party.events.UserPart part = new net.runelite.client.party.events.UserPart(1L);
		guest.controller.onUserPart(part);
		assertEquals(2L, guest.controller.getActiveHostMemberId());
		verify(guest.party).send(any(net.runelite.client.party.messages.UserSync.class));
	}

	@Test public void transferSyncRestoresMissingHistoryBeforeDecidingNewContributions() throws Exception
	{
		Fixture formerHost = new Fixture();
		formerHost.controller.start();
		formerHost.controller.setMemberApproved(2L, true);
		formerHost.controller.addManualGp(1L, 1001L);
		LootProposal historical = formerHost.engine.getActiveSession().get().getAcceptedProposals().get(0);
		formerHost.controller.transferHost(2L);
		Fixture newHost = synchronizedGuest();
		doAnswer(call -> {
			Object sent = call.getArgument(0);
			if (sent instanceof RecoveryMessage)
			{
				((RecoveryMessage) sent).setMemberId(1L);
				newHost.controller.onRecoveryMessage((RecoveryMessage) sent);
			}
			return null;
		}).when(formerHost.party).send(any(net.runelite.client.party.messages.PartyMessage.class));
		doAnswer(call -> {
			Object sent = call.getArgument(0);
			if (sent instanceof net.runelite.client.party.messages.UserSync)
			{
				((net.runelite.client.party.messages.UserSync) sent).setMemberId(2L);
				formerHost.controller.onUserSync((net.runelite.client.party.messages.UserSync) sent);
			}
			return null;
		}).when(newHost.party).send(any(net.runelite.client.party.messages.PartyMessage.class));
		HostMessage transfer = new HostMessage(2L, formerHost.engine.getActiveHostSettings().get(),
			formerHost.engine.getActiveHostRevision(), formerHost.engine.getActiveMemberApprovalStatuses(),
			formerHost.engine.getActiveDecisionKeys(), formerHost.engine.getActiveDecisionAuthorizations());
		transfer.setMemberId(1L);
		newHost.controller.onHostMessage(transfer);
		assertEquals(1001L, newHost.controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(historical.getParticipants(), newHost.engine.getProposal(historical.getProposalId()).get().getParticipants());
		newHost.controller.addManualGp(2L, 1000L);
		assertEquals(2001L, newHost.controller.getActiveCalculation().getTotalAcceptedValue());
		verify(newHost.timeout).cancel(false);
	}

	private Fixture synchronizedGuest() throws Exception
	{
		Fixture guest = new Fixture();
		when(guest.party.getLocalMember()).thenReturn(member(2L, "Bob"));
		guest.controller.start();
		HostMessage initial = new HostMessage(1L, 100L, 1L);
		initial.setMemberId(1L);
		guest.controller.onHostMessage(initial);
		assertTrue(guest.controller.getActiveHostSettings().isPresent());
		return guest;
	}

	private Fixture recoveringHost(Fixture source, boolean founderStillPresent) throws Exception
	{
		Fixture host = new Fixture();
		LootshareSession session = new LootshareSession("recovery", 77L, Instant.EPOCH);
		session.setHostState(2L, source.engine.getActiveHostSettings().get(), source.engine.getActiveHostRevision(),
			Collections.singletonMap(2L, MemberApprovalStatus.APPROVED));
		session.trustDecisionKeys(source.engine.getActiveDecisionKeys());
		session.trustDecisionAuthorizations(source.engine.getActiveDecisionAuthorizations());
		LootshareState state = new LootshareState();
		state.setSessions(Collections.singletonList(session));
		state.setActiveSessionId(session.getSessionId());
		assertTrue(host.storage.save(host.file, state));
		assertTrue(host.storage.load(host.file).isWritable());
		PartyMember local = member(2L, "Bob");
		PartyMember peer = member(3L, "Charlie");
		when(host.party.getLocalMember()).thenReturn(local);
		when(host.party.getMemberById(2L)).thenReturn(local);
		when(host.party.getMemberById(3L)).thenReturn(peer);
		if (founderStillPresent)
		{
			when(host.party.getMembers()).thenReturn(Arrays.asList(member(1L, "Alice"), local, peer));
		}
		else
		{
			when(host.party.getMemberById(1L)).thenReturn(null);
			when(host.party.getMembers()).thenReturn(Arrays.asList(local, peer));
		}
		host.controller.start();
		assertEquals(2L, host.controller.getActiveHostMemberId());
		return host;
	}

	private static void runAll(List<Runnable> tasks)
	{
		while (!tasks.isEmpty()) tasks.remove(0).run();
	}

	private static PartyMember member(long id, String name)
	{
		PartyMember member = new PartyMember(id);
		member.setDisplayName(name);
		member.setLoggedIn(true);
		return member;
	}

	private static LootProposal proposal(String id, long owner, long value)
	{
		return LootProposal.pending(77L, owner, new SharedLootEvent(id, "Bob", "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, value))));
	}

	private class Fixture
	{
		final Client client = mock(Client.class);
		final ClientThread clientThread = mock(ClientThread.class);
		final PartyService party = mock(PartyService.class);
		final LootshareConfig config = mock(LootshareConfig.class);
		final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		final ScheduledFuture<?> timeout = mock(ScheduledFuture.class);
		final LootshareEngine engine = new LootshareEngine();
		final File file;
		final LootshareStorage storage;
		final LootshareController controller;

		Fixture() throws Exception
		{
			file = temporaryFolder.newFile();
			storage = new LootshareStorage(file, new Gson());
			doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
				.when(executor).execute(any(Runnable.class));
			doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
				.when(clientThread).invokeLater(any(Runnable.class));
			doReturn(timeout).when(executor).schedule(any(Runnable.class), eq(3L), eq(TimeUnit.SECONDS));
			PartyMember local = member(1L, "Alice");
			PartyMember remote = member(2L, "Bob");
			when(party.isInParty()).thenReturn(true);
			when(party.getPartyId()).thenReturn(77L);
			when(party.getLocalMember()).thenReturn(local);
			when(party.getMembers()).thenReturn(Arrays.asList(local, remote));
			when(party.getMemberById(1L)).thenReturn(local);
			when(party.getMemberById(2L)).thenReturn(remote);
			when(config.minimumSharedLootValue()).thenReturn(100);
			when(config.lootValueBasis()).thenReturn(LootValueBasis.GRAND_EXCHANGE);
			controller = new LootshareController(client, clientThread, party, config, executor,
				mock(LootCaptureService.class), engine, new LootshareCalculator(), storage);
		}
	}
}
