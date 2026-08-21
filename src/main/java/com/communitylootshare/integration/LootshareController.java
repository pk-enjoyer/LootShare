/* Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause */
package com.communitylootshare.integration;

import com.communitylootshare.LootshareConfig;
import com.communitylootshare.capture.LootCaptureService;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.party.DecisionMessage;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.party.ProposalMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

@Singleton public class LootshareController
{
	private final Client client; private final PartyService partyService; private final LootshareConfig config; private final LootCaptureService capture;
	private final Map<String, LootProposal> proposals = new LinkedHashMap<>();
	private LootshareSettings settings; private long partyId; private long hostId; private long revision;
	@Inject public LootshareController(Client client, PartyService partyService, LootshareConfig config, LootCaptureService capture)
	{ this.client = client; this.partyService = partyService; this.config = config; this.capture = capture; }
	public void start() { }
	public void stop() { clear(); }
	public void onPartyChanged(PartyChanged event) { clear(); if (event != null && event.getPartyId() != null) partyId = event.getPartyId(); }
	public void onUserJoin(UserJoin event)
	{
		PartyMember local = partyService.getLocalMember();
		if (partyId > 0L && hostId == 0L && local != null && event != null && event.getMemberId() == local.getMemberId()) publishHost();
	}
	public void onUserPart(UserPart event) { if (event != null && event.getMemberId() == hostId) { hostId = 0L; PartyMember local = partyService.getLocalMember(); if (local != null) publishHost(); } }
	public void onLocalConfigurationChanged() { if (partyService.getLocalMember() != null && partyService.getLocalMember().getMemberId() == hostId) publishHost(); }
	public void onLootReceived(LootReceived event)
	{ if (event != null && event.getType() != LootRecordType.PLAYER) capture("Loot", event.getItems()); }
	public void onServerNpcLoot(ServerNpcLoot event)
	{ if (event != null && event.getComposition() != null) capture(event.getComposition().getName(), event.getItems()); }
	private void capture(String source, Collection<ItemStack> items)
	{
		if (!partyService.isInParty() || settings == null || partyId <= 0L) return;
		PartyMember local = partyService.getLocalMember(); Player player = client.getLocalPlayer();
		if (local == null || player == null || player.getName() == null) return;
		Optional<SharedLootEvent> event = capture.capture(source == null ? "Loot" : source, items, player.getName(), Instant.now(), client.getTickCount(), settings.getLootValueBasis());
		if (!event.isPresent() || event.get().getTotal() < settings.getMinimumSharedLootValue()) return;
		LootProposal proposal = LootProposal.pending(partyId, local.getMemberId(), event.get()); proposals.putIfAbsent(proposal.getProposalId(), proposal); partyService.send(new ProposalMessage(proposal)); if (local.getMemberId() == hostId) accept(proposal);
	}
	public void onProposalMessage(ProposalMessage message)
	{ if (message == null || message.getMemberId() == hostId) return; message.decode(partyId).ifPresent(p -> { if (!proposals.containsKey(p.getProposalId())) { proposals.put(p.getProposalId(), p); if (hostId == partyService.getLocalMember().getMemberId()) accept(p); } }); }
	public void onDecisionMessage(DecisionMessage message)
	{ if (message == null || message.getMemberId() != hostId) return; message.decode().ifPresent(d -> { LootProposal p = proposals.get(d.getProposalId()); if (p != null && !p.isAccepted()) p.accept(d.getDecidedAt(), d.getParticipants()); }); }
	public void onHostMessage(HostMessage message)
	{ if (message != null) message.decode().ifPresent(d -> { if (message.getMemberId() == d.getHostMemberId() && d.getRevision() >= revision) { hostId = d.getHostMemberId(); revision = d.getRevision(); settings = d.getSettings(); } }); }
	public void onUserSync(UserSync sync)
	{ PartyMember local = partyService.getLocalMember(); if (local == null || local.getMemberId() != hostId) return; partyService.send(new HostMessage(hostId, localSettings(), revision)); for (LootProposal p : proposals.values()) { partyService.send(new ProposalMessage(p)); if (p.isAccepted()) partyService.send(new DecisionMessage(p)); } }
	private void publishHost() { PartyMember local = partyService.getLocalMember(); if (local == null) return; hostId = local.getMemberId(); settings = localSettings(); revision++; partyService.send(new HostMessage(hostId, settings, revision)); }
	private LootshareSettings localSettings() { return new LootshareSettings(config.minimumSharedLootValue(), config.lootValueBasis(), config.includeLoggedOutMembers()); }
	private void accept(LootProposal proposal)
	{ if (proposal.isAccepted()) return; List<LootshareParticipant> roster = new ArrayList<>(); for (PartyMember member : partyService.getMembers()) if (settings.isIncludeLoggedOutMembers() || member.isLoggedIn()) roster.add(new LootshareParticipant(member.getMemberId(), member.getDisplayName())); if (roster.isEmpty()) return; proposal.accept(Instant.now(), roster); partyService.send(new DecisionMessage(proposal)); }
	private void clear() { proposals.clear(); capture.resetDeduplication(); partyId = 0L; hostId = 0L; revision = 0L; settings = null; }
	public String getSummary() { long total = 0L; for (LootProposal p : proposals.values()) if (p.isAccepted()) total += p.getEvent().getTotal(); return partyId == 0L ? "Create or join a RuneLite Party to begin sharing loot." : "Shared loot: " + total + " gp"; }
}
