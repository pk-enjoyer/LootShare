/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.persistence;

import com.google.gson.Gson;
import com.communitylootshare.domain.CommunityLootshareState;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSettings;
import com.communitylootshare.domain.LootValueBasis;
import com.communitylootshare.domain.MemberApprovalStatus;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.sessions.CommunityLootshareEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CommunityLootshareStorageTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private final Gson gson = new Gson();

	@Test
	public void atomicallySavesAndLoadsProfileHistoryWithPriceSnapshots() throws Exception
	{
		File file = new File(temporaryFolder.getRoot(), "nested/history.json");
		CommunityLootshareStorage storage = new CommunityLootshareStorage(file, gson);
		CommunityLootshareEngine engine = acceptedEngine();

		assertTrue(storage.save(file, engine.snapshot()));
		assertTrue(file.isFile());
		CommunityLootshareStorage.LoadResult loaded = storage.load(file);
		assertTrue(loaded.isWritable());
		assertEquals(CommunityLootshareState.CURRENT_SCHEMA_VERSION, loaded.getState().getSchemaVersion());

		CommunityLootshareEngine restored = new CommunityLootshareEngine();
		restored.restore(loaded.getState());
		LootProposal proposal = restored.getProposal("p1").get();
		assertEquals(LootProposalStatus.ACCEPTED, proposal.getStatus());
		assertEquals(101, proposal.getEvent().getItems().get(0).getPricingId());
		assertEquals(50L, proposal.getEvent().getItems().get(0).getUnitPrice());
		assertEquals(100L, proposal.getEvent().getTotal());
		assertEquals(1L, restored.getActiveHostMemberId());
		assertEquals(100_000L, restored.getActiveMinimumSharedLootValue());
		assertEquals(LootValueBasis.HIGH_ALCHEMY,
			restored.getActiveHostSettings().get().getLootValueBasis());
		assertTrue(restored.getActiveHostSettings().get().isIncludeLoggedOutMembers());
		assertEquals(MemberApprovalStatus.APPROVED, restored.getActiveMemberApprovalStatus(1L));
		assertEquals(MemberApprovalStatus.EXCLUDED, restored.getActiveMemberApprovalStatus(2L));
	}

	@Test
	public void missingAndEmptyFilesLoadAsWritableEmptyState() throws Exception
	{
		File missing = new File(temporaryFolder.getRoot(), "missing.json");
		CommunityLootshareStorage storage = new CommunityLootshareStorage(missing, gson);
		assertTrue(storage.load(missing).isWritable());
		assertTrue(storage.load(missing).getState().getSessions().isEmpty());

		File empty = temporaryFolder.newFile("empty.json");
		assertTrue(storage.load(empty).isWritable());
		assertTrue(storage.load(empty).getState().getProposals().isEmpty());
		assertFalse(storage.load(null).isWritable());

		File schemaOne = temporaryFolder.newFile("schema-one.json");
		Files.write(schemaOne.toPath(), Collections.singletonList(
			"{\"schemaVersion\":1,\"proposals\":[],\"sessions\":[]}"), StandardCharsets.UTF_8);
		assertTrue(storage.load(schemaOne).isWritable());
	}

	@Test
	public void malformedFutureAndOversizedFilesAreReadOnly() throws Exception
	{
		File malformed = temporaryFolder.newFile("malformed.json");
		Files.write(malformed.toPath(), Collections.singletonList("{not json"), StandardCharsets.UTF_8);
		CommunityLootshareStorage storage = new CommunityLootshareStorage(malformed, gson);
		assertFalse(storage.load(malformed).isWritable());

		File future = temporaryFolder.newFile("future.json");
		Files.write(future.toPath(), Collections.singletonList("{\"schemaVersion\":"
			+ (CommunityLootshareState.CURRENT_SCHEMA_VERSION + 1) + "}"), StandardCharsets.UTF_8);
		assertFalse(storage.load(future).isWritable());

		File invalidSchema = temporaryFolder.newFile("invalid-schema.json");
		Files.write(invalidSchema.toPath(), Collections.singletonList("{\"schemaVersion\":-1}"), StandardCharsets.UTF_8);
		assertFalse(storage.load(invalidSchema).isWritable());

		File oversized = temporaryFolder.newFile("oversized.json");
		Files.write(oversized.toPath(), new byte[5 * 1024 * 1024 + 1]);
		assertFalse(storage.load(oversized).isWritable());

		File directory = temporaryFolder.newFolder("not-a-file");
		assertFalse(storage.load(directory).isWritable());
	}

	@Test
	public void migratesThresholdOnlySchemaTwoHostStateToDefaultPolicy() throws Exception
	{
		File file = temporaryFolder.newFile("schema-two-host.json");
		Files.write(file.toPath(), Collections.singletonList(
			"{\"schemaVersion\":2,\"activeSessionId\":\"legacy-host\",\"proposals\":[],"
				+ "\"sessions\":[{\"sessionId\":\"legacy-host\",\"partyId\":10,"
				+ "\"startedAt\":\"1970-01-01T00:00:00Z\",\"hostMemberId\":1,"
				+ "\"minimumSharedLootValue\":123,\"hostRevision\":1,\"acceptedProposals\":[]}]}"),
			StandardCharsets.UTF_8);
		CommunityLootshareStorage storage = new CommunityLootshareStorage(file, gson);

		CommunityLootshareStorage.LoadResult loaded = storage.load(file);
		CommunityLootshareEngine restored = new CommunityLootshareEngine();
		restored.restore(loaded.getState());

		assertTrue(loaded.isWritable());
		assertEquals(123L, restored.getActiveHostSettings().get().getMinimumSharedLootValue());
		assertEquals(LootValueBasis.GRAND_EXCHANGE,
			restored.getActiveHostSettings().get().getLootValueBasis());
		assertTrue(restored.getActiveHostSettings().get().isCaptureNpcLoot());
		assertFalse(restored.getActiveHostSettings().get().isIncludeLoggedOutMembers());
		assertEquals(MemberApprovalStatus.APPROVED, restored.getActiveMemberApprovalStatus(1L));
		assertEquals(MemberApprovalStatus.PENDING, restored.getActiveMemberApprovalStatus(2L));
	}

	@Test
	public void profileBackedPathIsContainedAndSanitized()
	{
		ConfigManager configManager = mock(ConfigManager.class);
		ConfigProfile profile = mock(ConfigProfile.class);
		when(configManager.getProfile()).thenReturn(profile);
		when(profile.getName()).thenReturn("../escaped/profile");
		when(profile.getId()).thenReturn(42L);
		CommunityLootshareStorage storage = new CommunityLootshareStorage(configManager, gson);

		Path pluginDirectory = new File(RuneLite.RUNELITE_DIR, "community-lootshare").toPath()
			.toAbsolutePath().normalize();
		Path path = storage.resolveCurrentFile().toPath().toAbsolutePath().normalize();
		assertTrue(path.startsWith(pluginDirectory));
		assertFalse(path.toString().contains(".."));
		assertTrue(path.getFileName().toString().endsWith(".community-lootshare.json"));

		when(configManager.getProfile()).thenReturn(null);
		assertEquals(null, storage.resolveCurrentFile());
	}

	@Test
	public void nullAndUnwritableSaveTargetsFailWithoutThrowing() throws Exception
	{
		File file = new File(temporaryFolder.getRoot(), "history.json");
		CommunityLootshareStorage storage = new CommunityLootshareStorage(file, gson);
		assertFalse(storage.save(null, new CommunityLootshareState()));
		assertFalse(storage.save(file, null));
		assertFalse(storage.save(temporaryFolder.newFolder("directory-target"), new CommunityLootshareState()));
	}

	@Test
	public void requiresInjectedGson()
	{
		try
		{
			new CommunityLootshareStorage(new File("unused"), null);
			fail("Expected invalid Gson to be rejected");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected.
		}
	}

	private static CommunityLootshareEngine acceptedEngine()
	{
		CommunityLootshareEngine engine = new CommunityLootshareEngine();
		engine.enterParty(10L, "session", Instant.EPOCH);
		engine.updateHostState(1L, 1L, new LootshareSettings(100_000L, LootValueBasis.HIGH_ALCHEMY,
			true, false, true, false, true, true), 1L);
		engine.updateMemberApprovalStatus(1L, 2L, MemberApprovalStatus.EXCLUDED, 2L);
		SharedLootEvent event = new SharedLootEvent("p1", "Alice", "Boss", Instant.ofEpochSecond(1),
			Collections.singletonList(new SharedLootItem(100, 101, 2, 50)));
		engine.addProposal(LootProposal.pending(10L, 1L, event));
		engine.decide("p1", 1L, LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(2),
			Collections.singletonList(new LootshareParticipant(1L, "Alice")));
		return engine;
	}
}
