/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.integration;

import com.google.gson.Gson;
import com.communitylootshare.CommunityLootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareCalculation;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.party.CommunityLootshareDecisionMessage;
import com.communitylootshare.party.CommunityLootshareProposalMessage;
import com.communitylootshare.persistence.CommunityLootshareStorage;
import com.communitylootshare.sessions.CommunityLootshareEngine;
import com.communitylootshare.sessions.CommunityLootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.loottracker.LootReceived;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommunityLootshareControllerTest
{
	private static final long PARTY_ID = 77L;

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Client client;
	private PartyService partyService;
	private LootCaptureService captureService;
	private CommunityLootshareEngine engine;
	private CommunityLootshareStorage storage;
	private CommunityLootshareController controller;
	private PartyMember localMember;
	private PartyMember remoteMember;
	private File storageFile;

	@Before
	public void setUp() throws Exception
	{
		client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		partyService = mock(PartyService.class);
		ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		captureService = mock(LootCaptureService.class);
		engine = new CommunityLootshareEngine();
		storageFile = new File(temporaryFolder.getRoot(), "history/community-lootshare.json");
		storage = new CommunityLootshareStorage(storageFile, new Gson());

		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		localMember = member(1L, "Alice");
		remoteMember = member(2L, "Bob");
		when(partyService.isInParty()).thenReturn(true);
		when(partyService.getPartyId()).thenReturn(PARTY_ID);
		when(partyService.getLocalMember()).thenReturn(localMember);
		when(partyService.getMembers()).thenReturn(Arrays.asList(localMember, remoteMember));
		when(partyService.getMemberById(1L)).thenReturn(localMember);
		when(partyService.getMemberById(2L)).thenReturn(remoteMember);

		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Alice");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getTickCount()).thenReturn(42);

		controller = new CommunityLootshareController(client, clientThread, partyService, executor,
			captureService, engine, new LootshareCalculator(), storage);
		controller.start();
		assertTrue(controller.isReady());
		clearInvocations(partyService);
	}

	@Test
	public void capturesApprovesCalculatesAndPersistsAnOwnedProposal()
	{
		LootReceived received = mock(LootReceived.class);
		SharedLootEvent event = event("local", "Alice", 200L);
		when(captureService.capture(eq(received), eq("Alice"), any(Instant.class), eq(42L),
			eq((long) CommunityLootshareConfig.DEFAULT_MINIMUM_BUNDLE_VALUE))).thenReturn(Optional.of(event));

		controller.onLootReceived(received);

		assertEquals(1, controller.getPendingOwnedProposals().size());
		verify(partyService).send(any(CommunityLootshareProposalMessage.class));
		assertEquals(MutationResult.APPLIED, controller.approveProposal("local"));
		verify(partyService).send(any(CommunityLootshareDecisionMessage.class));

		LootshareCalculation calculation = controller.getActiveCalculation();
		assertEquals(200L, calculation.getTotalAcceptedValue());
		assertEquals(2, calculation.getBalances().size());
		assertEquals(1, calculation.getTransfers().size());
		assertEquals(100L, calculation.getTransfers().get(0).getAmount());

		CommunityLootshareEngine restored = new CommunityLootshareEngine();
		restored.restore(storage.load(storageFile).getState());
		assertEquals(LootProposalStatus.ACCEPTED, restored.getProposal("local").get().getStatus());
		assertEquals(1, restored.getActiveSession().get().getAcceptedProposals().size());
	}

	@Test
	public void acceptsOnlyTheProposalOwnerAsDecisionSender()
	{
		LootProposal remoteProposal = LootProposal.pending(PARTY_ID, remoteMember.getMemberId(),
			event("remote", "Bob", 300L));
		CommunityLootshareProposalMessage proposalMessage = new CommunityLootshareProposalMessage(remoteProposal);
		proposalMessage.setMemberId(remoteMember.getMemberId());
		controller.onProposalMessage(proposalMessage);
		assertEquals(LootProposalStatus.PENDING, engine.getProposal("remote").get().getStatus());

		LootProposal accepted = remoteProposal.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(2),
			Arrays.asList(new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
		CommunityLootshareDecisionMessage wrongSender = new CommunityLootshareDecisionMessage(accepted);
		wrongSender.setMemberId(localMember.getMemberId());
		controller.onDecisionMessage(wrongSender);
		assertEquals(LootProposalStatus.PENDING, engine.getProposal("remote").get().getStatus());

		CommunityLootshareDecisionMessage ownerDecision = new CommunityLootshareDecisionMessage(accepted);
		ownerDecision.setMemberId(remoteMember.getMemberId());
		controller.onDecisionMessage(ownerDecision);
		assertEquals(LootProposalStatus.ACCEPTED, engine.getProposal("remote").get().getStatus());

		CommunityLootshareProposalMessage outsider = new CommunityLootshareProposalMessage(
			LootProposal.pending(PARTY_ID, 3L, event("outsider", "Mallory", 1L)));
		outsider.setMemberId(3L);
		controller.onProposalMessage(outsider);
		assertTrue(!engine.getProposal("outsider").isPresent());
	}

	private static PartyMember member(long memberId, String displayName)
	{
		PartyMember member = new PartyMember(memberId);
		member.setDisplayName(displayName);
		member.setLoggedIn(true);
		return member;
	}

	private static SharedLootEvent event(String proposalId, String recipient, long value)
	{
		return new SharedLootEvent(proposalId, recipient, "Boss", Instant.EPOCH,
			Collections.singletonList(new SharedLootItem(100, 100, 1L, value)));
	}
}
