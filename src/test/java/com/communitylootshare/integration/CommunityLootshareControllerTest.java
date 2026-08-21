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
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.party.CommunityLootshareDecisionMessage;
import com.communitylootshare.party.CommunityLootshareHostMessage;
import com.communitylootshare.party.CommunityLootshareProposalMessage;
import com.communitylootshare.persistence.CommunityLootshareStorage;
import com.communitylootshare.sessions.CommunityLootshareEngine;
import com.communitylootshare.sessions.CommunityLootshareEngine.MutationResult;
import com.communitylootshare.sessions.LootshareCalculator;
import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommunityLootshareControllerTest
{
	private static final long PARTY_ID = 77L;

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Client client;
	private PartyService partyService;
	private CommunityLootshareConfig config;
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
		config = mock(CommunityLootshareConfig.class);
		when(config.minimumSharedLootValue()).thenReturn(100);
		when(config.lootValueBasis()).thenReturn(LootValueBasis.GRAND_EXCHANGE);
		when(config.captureNpcLoot()).thenReturn(true);
		when(config.captureEventLoot()).thenReturn(true);
		when(config.capturePlayerLoot()).thenReturn(true);
		when(config.capturePickpocketLoot()).thenReturn(true);
		when(config.captureUnknownLoot()).thenReturn(true);
		when(config.includeLoggedOutMembers()).thenReturn(false);
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

		controller = new CommunityLootshareController(client, clientThread, partyService, config, executor,
			captureService, engine, new LootshareCalculator(), storage);
		controller.start();
		assertTrue(controller.isReady());
		clearInvocations(partyService);
	}

	@Test
	public void capturesApprovesCalculatesAndPersistsAnOwnedProposal()
	{
		assertEquals(MutationResult.APPLIED,
			controller.setMemberApproved(remoteMember.getMemberId(), true));
		LootReceived received = mock(LootReceived.class);
		List<ItemStack> stacks = Collections.singletonList(new ItemStack(100, 1));
		when(received.getName()).thenReturn("Boss");
		when(received.getItems()).thenReturn(stacks);
		SharedLootEvent event = event("local", "Alice", 200L);
		when(captureService.capture(eq("Boss"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.GRAND_EXCHANGE)))
			.thenReturn(Optional.of(event));

		controller.onLootReceived(received);

		assertTrue(controller.getPendingOwnedProposals().isEmpty());
		assertEquals(MemberApprovalStatus.APPROVED,
			controller.getMemberApprovalStatus(remoteMember.getMemberId()));
		assertEquals(2, engine.getProposal("local").get().getParticipants().size());
		verify(partyService).send(any(CommunityLootshareProposalMessage.class));
		verify(partyService).send(any(CommunityLootshareDecisionMessage.class));
		assertEquals(MutationResult.DUPLICATE, controller.approveProposal("local"));

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
	public void capturesServerNpcLootDirectlyWithEveryReportedStack()
	{
		NPCComposition composition = mock(NPCComposition.class);
		when(composition.getName()).thenReturn("<col=ff0000>Boss</col>");
		List<ItemStack> stacks = Arrays.asList(
			new ItemStack(100, 1), new ItemStack(101, 4), new ItemStack(102, 3));
		ServerNpcLoot received = new ServerNpcLoot(composition, stacks);
		SharedLootEvent event = event("server-npc", "Alice", 200L);
		when(captureService.capture(eq("Boss"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.GRAND_EXCHANGE)))
			.thenReturn(Optional.of(event));

		controller.onServerNpcLoot(received);

		assertTrue(engine.getProposal("server-npc").isPresent());
		verify(captureService).capture(eq("Boss"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.GRAND_EXCHANGE));
		verify(partyService).send(any(CommunityLootshareProposalMessage.class));
	}

	@Test
	public void leavesPickpocketServerLootOnTheExistingLootReceivedPath()
	{
		ChatMessage pickpocket = new ChatMessage();
		pickpocket.setType(ChatMessageType.GAMEMESSAGE);
		pickpocket.setMessage("You pick the Vyre's pocket.");
		controller.onChatMessage(pickpocket);
		NPCComposition composition = mock(NPCComposition.class);
		when(composition.getName()).thenReturn("Vyre");

		controller.onServerNpcLoot(new ServerNpcLoot(composition,
			Collections.singletonList(new ItemStack(24777, 1))));

		verify(captureService, never()).capture(any(String.class), any(), any(String.class),
			any(Instant.class), anyLong(), any(LootValueBasis.class));
	}

	@Test
	public void sendsLowValueLootButOnlyTheHostThresholdControlsCalculations()
	{
		LootReceived received = mock(LootReceived.class);
		List<ItemStack> stacks = Collections.singletonList(new ItemStack(100, 1));
		when(received.getName()).thenReturn("Goblin");
		when(received.getItems()).thenReturn(stacks);
		SharedLootEvent lowValueEvent = event("low-value", "Alice", 50L);
		when(captureService.capture(eq("Goblin"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.GRAND_EXCHANGE)))
			.thenReturn(Optional.of(lowValueEvent));

		controller.onLootReceived(received);

		assertTrue(engine.getProposal("low-value").isPresent());
		verify(partyService).send(any(CommunityLootshareProposalMessage.class));
		assertEquals(MutationResult.DUPLICATE, controller.approveProposal("low-value"));
		assertEquals(0L, controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(1, engine.getActiveSession().get().getAcceptedProposals().size());

		when(config.minimumSharedLootValue()).thenReturn(0);
		controller.onMinimumSharedLootValueChanged();

		assertEquals(50L, controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(0L, engine.getActiveMinimumSharedLootValue());
		verify(partyService).send(any(CommunityLootshareHostMessage.class));
	}

	@Test
	public void electsTheFirstMemberAndSupportsHostTransferAndRecipientConfiguration()
	{
		assertEquals(localMember.getMemberId(), controller.getActiveHostMemberId());
		assertEquals(100L, engine.getActiveMinimumSharedLootValue());
		assertEquals(MutationResult.INVALID, controller.transferHost(localMember.getMemberId()));
		assertEquals(MutationResult.APPLIED, controller.transferHost(remoteMember.getMemberId()));
		assertEquals(remoteMember.getMemberId(), controller.getActiveHostMemberId());
		ArgumentCaptor<CommunityLootshareHostMessage> transferMessage =
			ArgumentCaptor.forClass(CommunityLootshareHostMessage.class);
		verify(partyService).send(transferMessage.capture());
		transferMessage.getValue().setMemberId(localMember.getMemberId());
		CommunityLootshareHostMessage.DecodedHostState transferred = transferMessage.getValue().decode().get();
		assertEquals(remoteMember.getMemberId(), transferred.getHostMemberId());
		assertEquals(MemberApprovalStatus.APPROVED,
			transferred.getApprovalStatuses().get(remoteMember.getMemberId()));

		CommunityLootshareHostMessage recipientSettings = new CommunityLootshareHostMessage(
			remoteMember.getMemberId(), 500L, 3L);
		recipientSettings.setMemberId(remoteMember.getMemberId());
		controller.onHostMessage(recipientSettings);
		assertEquals(500L, engine.getActiveMinimumSharedLootValue());
		assertEquals(3L, engine.getActiveHostRevision());

		when(config.minimumSharedLootValue()).thenReturn(1);
		controller.onMinimumSharedLootValueChanged();
		assertEquals(500L, engine.getActiveMinimumSharedLootValue());
	}

	@Test
	public void rejectsAnInitialHostClaimFromAnyoneExceptTheFirstPartyMember()
	{
		Instant transition = engine.getActiveSession().get().getStartedAt().plusSeconds(1L);
		assertEquals(MutationResult.APPLIED, engine.leaveParty(transition));
		assertEquals(MutationResult.APPLIED, engine.enterParty(PARTY_ID, "fresh", transition));
		CommunityLootshareHostMessage remoteClaim = new CommunityLootshareHostMessage(2L, 100L, 1L);
		remoteClaim.setMemberId(2L);

		controller.onHostMessage(remoteClaim);

		assertEquals(0L, controller.getActiveHostMemberId());
		CommunityLootshareHostMessage firstClaim = new CommunityLootshareHostMessage(1L, 100L, 1L);
		firstClaim.setMemberId(1L);
		controller.onHostMessage(firstClaim);
		assertEquals(1L, controller.getActiveHostMemberId());
	}

	@Test
	public void electsADeterministicSuccessorWhenTheHostLeavesWithoutTransferring()
	{
		assertEquals(MutationResult.APPLIED, controller.transferHost(remoteMember.getMemberId()));
		PartyMember third = member(3L, "Charlie");
		when(partyService.getMemberById(2L)).thenReturn(null);
		when(partyService.getMemberById(3L)).thenReturn(third);
		when(partyService.getMembers()).thenReturn(Arrays.asList(third, localMember));

		controller.onUserPart(new UserPart(remoteMember.getMemberId()));

		assertEquals(localMember.getMemberId(), controller.getActiveHostMemberId());
		assertEquals(3L, engine.getActiveHostRevision());
	}

	@Test
	public void guestUsesTheHostCapturePolicyAndValuationBasis()
	{
		assertEquals(MutationResult.APPLIED, controller.transferHost(remoteMember.getMemberId()));
		LootshareSettings hostSettings = new LootshareSettings(250L, LootValueBasis.HIGH_ALCHEMY,
			false, true, false, false, false, true);
		CommunityLootshareHostMessage hostMessage = new CommunityLootshareHostMessage(
			remoteMember.getMemberId(), hostSettings, 3L);
		hostMessage.setMemberId(remoteMember.getMemberId());
		controller.onHostMessage(hostMessage);

		assertEquals(hostSettings, controller.getActiveHostSettings().get());
		when(config.captureEventLoot()).thenReturn(false);
		controller.onLocalConfigurationChanged();
		assertEquals(hostSettings, controller.getActiveHostSettings().get());
		clearInvocations(captureService, partyService);

		LootReceived playerLoot = mock(LootReceived.class);
		when(playerLoot.getType()).thenReturn(LootRecordType.PLAYER);
		when(playerLoot.getName()).thenReturn("Player");
		when(playerLoot.getItems()).thenReturn(Collections.singletonList(new ItemStack(100, 1)));
		controller.onLootReceived(playerLoot);
		verify(captureService, never()).capture(any(String.class), any(), any(String.class),
			any(Instant.class), anyLong(), any(LootValueBasis.class));

		LootReceived eventLoot = mock(LootReceived.class);
		List<ItemStack> stacks = Collections.singletonList(new ItemStack(101, 1));
		when(eventLoot.getType()).thenReturn(LootRecordType.EVENT);
		when(eventLoot.getName()).thenReturn("Raid reward");
		when(eventLoot.getItems()).thenReturn(stacks);
		when(captureService.capture(eq("Raid reward"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.HIGH_ALCHEMY))).thenReturn(Optional.of(event("host-event", "Alice", 300L)));

		controller.onLootReceived(eventLoot);

		assertTrue(engine.getProposal("host-event").isPresent());
		verify(captureService).capture(eq("Raid reward"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.HIGH_ALCHEMY));
	}

	@Test
	public void pausesCaptureUntilAHostSnapshotIsAvailable()
	{
		assertEquals(MutationResult.APPLIED, engine.vacateHost(localMember.getMemberId()));
		clearInvocations(captureService);
		LootReceived received = mock(LootReceived.class);
		when(received.getType()).thenReturn(LootRecordType.NPC);
		when(received.getName()).thenReturn("Boss");
		when(received.getItems()).thenReturn(Collections.singletonList(new ItemStack(100, 1)));

		controller.onLootReceived(received);

		assertTrue(!controller.getActiveHostSettings().isPresent());
		verify(captureService, never()).capture(any(String.class), any(), any(String.class),
			any(Instant.class), anyLong(), any(LootValueBasis.class));
	}

	@Test
	public void rejoiningGuestWaitsForTheLiveHostToConfirmAPersistedSnapshot()
	{
		assertTrue(controller.getActiveHostSettings().isPresent());
		when(partyService.getMembers()).thenReturn(Arrays.asList(remoteMember, localMember));

		controller.onPartyChanged(new net.runelite.client.events.PartyChanged("party-pass", PARTY_ID));

		assertTrue(!controller.getActiveHostSettings().isPresent());
		LootshareSettings liveSettings = new LootshareSettings(450L, LootValueBasis.HIGH_ALCHEMY,
			false, true, false, true, false, true);
		CommunityLootshareHostMessage liveConfirmation = new CommunityLootshareHostMessage(
			remoteMember.getMemberId(), liveSettings, 2L);
		liveConfirmation.setMemberId(remoteMember.getMemberId());
		controller.onHostMessage(liveConfirmation);
		assertEquals(remoteMember.getMemberId(), controller.getActiveHostMemberId());
		assertEquals(liveSettings, controller.getActiveHostSettings().get());
	}

	@Test
	public void localHostCanResumePersistedAuthorityAfterAPluginRestart()
	{
		when(partyService.getMembers()).thenReturn(Arrays.asList(remoteMember, localMember));
		controller.stop();

		controller.start();

		assertEquals(localMember.getMemberId(), controller.getActiveHostMemberId());
		assertEquals(LootshareSettings.defaults(100L), controller.getActiveHostSettings().get());
	}

	@Test
	public void hostPolicyCanIncludeLoggedOutMembersInTheFrozenRoster()
	{
		remoteMember.setLoggedIn(false);
		when(config.includeLoggedOutMembers()).thenReturn(true);
		controller.onLocalConfigurationChanged();
		assertEquals(MutationResult.APPLIED,
			controller.setMemberApproved(remoteMember.getMemberId(), true));
		List<ItemStack> stacks = Collections.singletonList(new ItemStack(100, 1));
		LootReceived received = mock(LootReceived.class);
		when(received.getType()).thenReturn(LootRecordType.NPC);
		when(received.getName()).thenReturn("Boss");
		when(received.getItems()).thenReturn(stacks);
		when(captureService.capture(eq("Boss"), eq(stacks), eq("Alice"), any(Instant.class), eq(42L),
			eq(LootValueBasis.GRAND_EXCHANGE))).thenReturn(Optional.of(event("offline", "Alice", 200L)));

		controller.onLootReceived(received);
		assertEquals(MutationResult.DUPLICATE, controller.approveProposal("offline"));

		LootProposal accepted = engine.getProposal("offline").get();
		assertEquals(2, accepted.getParticipants().size());
		assertEquals(remoteMember.getMemberId(), accepted.getParticipants().get(1).getMemberId());
	}

	@Test
	public void hostEligibilityAutomaticallyDecidesFutureDropsWithoutRewritingAcceptedSplits()
	{
		CommunityLootshareProposalMessage pendingDrop = new CommunityLootshareProposalMessage(
			LootProposal.pending(PARTY_ID, remoteMember.getMemberId(), event("pending-member", "Bob", 200L)));
		pendingDrop.setMemberId(remoteMember.getMemberId());
		controller.onProposalMessage(pendingDrop);
		assertEquals(LootProposalStatus.REJECTED,
			engine.getProposal("pending-member").get().getStatus());

		assertEquals(MutationResult.APPLIED, engine.addProposal(
			LootProposal.pending(PARTY_ID, remoteMember.getMemberId(), event("before-toggle", "Bob", 250L))));
		assertEquals(MutationResult.APPLIED,
			controller.setMemberApproved(remoteMember.getMemberId(), true));
		assertEquals(LootProposalStatus.REJECTED,
			engine.getProposal("before-toggle").get().getStatus());
		CommunityLootshareProposalMessage approvedDrop = new CommunityLootshareProposalMessage(
			LootProposal.pending(PARTY_ID, remoteMember.getMemberId(), event("approved-member", "Bob", 300L)));
		approvedDrop.setMemberId(remoteMember.getMemberId());
		controller.onProposalMessage(approvedDrop);
		LootProposal accepted = engine.getProposal("approved-member").get();
		assertEquals(LootProposalStatus.ACCEPTED, accepted.getStatus());
		assertEquals(2, accepted.getParticipants().size());
		assertEquals(300L, controller.getActiveCalculation().getTotalAcceptedValue());

		assertEquals(MutationResult.APPLIED,
			controller.setMemberApproved(remoteMember.getMemberId(), false));
		CommunityLootshareProposalMessage excludedDrop = new CommunityLootshareProposalMessage(
			LootProposal.pending(PARTY_ID, remoteMember.getMemberId(), event("excluded-member", "Bob", 400L)));
		excludedDrop.setMemberId(remoteMember.getMemberId());
		controller.onProposalMessage(excludedDrop);
		assertEquals(LootProposalStatus.REJECTED,
			engine.getProposal("excluded-member").get().getStatus());
		assertEquals(300L, controller.getActiveCalculation().getTotalAcceptedValue());
		assertEquals(2, engine.getProposal("approved-member").get().getParticipants().size());
	}

	@Test
	public void acceptsOnlyTheHostAsDecisionSender()
	{
		LootProposal remoteProposal = LootProposal.pending(PARTY_ID, remoteMember.getMemberId(),
			event("remote", "Bob", 300L));
		assertEquals(MutationResult.APPLIED, engine.addProposal(remoteProposal));
		assertEquals(LootProposalStatus.PENDING, engine.getProposal("remote").get().getStatus());

		LootProposal accepted = remoteProposal.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(2),
			Arrays.asList(new LootshareParticipant(1L, "Alice"), new LootshareParticipant(2L, "Bob")));
		CommunityLootshareDecisionMessage ownerDecision = new CommunityLootshareDecisionMessage(accepted);
		ownerDecision.setMemberId(remoteMember.getMemberId());
		controller.onDecisionMessage(ownerDecision);
		assertEquals(LootProposalStatus.PENDING, engine.getProposal("remote").get().getStatus());

		CommunityLootshareDecisionMessage hostDecision = new CommunityLootshareDecisionMessage(accepted);
		hostDecision.setMemberId(localMember.getMemberId());
		controller.onDecisionMessage(hostDecision);
		assertEquals(LootProposalStatus.ACCEPTED, engine.getProposal("remote").get().getStatus());

		CommunityLootshareProposalMessage outsider = new CommunityLootshareProposalMessage(
			LootProposal.pending(PARTY_ID, 3L, event("outsider", "Mallory", 1L)));
		outsider.setMemberId(3L);
		controller.onProposalMessage(outsider);
		assertTrue(!engine.getProposal("outsider").isPresent());
	}

	@Test
	public void notifiesTheUiWhenProfileLoadingCompletes()
	{
		controller.stop();
		AtomicInteger changes = new AtomicInteger();
		controller.setStateChangeListener(changes::incrementAndGet);

		controller.start();

		assertEquals(1, changes.get());
		assertTrue(controller.isReady());
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
