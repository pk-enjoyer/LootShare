/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.communitylootshare.persistence;

import com.communitylootshare.domain.LootDecisionReceipt;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootshareParticipant;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.SharedLootEvent;
import com.communitylootshare.domain.SharedLootItem;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.utils.InstantTypeAdapter;
import java.io.InputStream;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import org.mockito.ArgumentCaptor;
import com.google.gson.Gson;
import java.io.File;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LootshareStorageProfileTest
{
	@Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
	@Test public void renamingAProfileMustKeepTheSameHistoryFile()
	{
		ConfigManager manager = mock(ConfigManager.class);
		ConfigProfile profile = mock(ConfigProfile.class);
		when(manager.getProfile()).thenReturn(profile);
		when(profile.getId()).thenReturn(42L);
		when(profile.getName()).thenReturn("Original name");
		LootshareStorage storage = new LootshareStorage(manager, new Gson());
		File before = storage.resolveCurrentFile();
		when(profile.getName()).thenReturn("Renamed profile");
		assertEquals("Profile identity is unchanged but history lookup moved", before, storage.resolveCurrentFile());
	}

	@Test public void migratesRenamedLegacyProfileHistoryWithoutDeletingItsBackup() throws Exception
	{
		File legacy = new File(temporaryFolder.getRoot(), "Original name-42.community-lootshare.json");
		Files.write(legacy.toPath(), "{\"schemaVersion\":5,\"proposals\":[],\"sessions\":[]}".getBytes(StandardCharsets.UTF_8));
		byte[] backup = Files.readAllBytes(legacy.toPath());
		File stable = new File(temporaryFolder.getRoot(), "profile-42.community-lootshare.json");
		LootshareStorage storage = new LootshareStorage(stable, new Gson());
		assertTrue(storage.load(stable).isWritable());
		assertTrue(stable.isFile());
		assertArrayEquals(backup, Files.readAllBytes(legacy.toPath()));
		assertTrue(storage.load(stable).isWritable());
	}

	@Test public void invalidAndAmbiguousLegacyFilesRemainUntouched() throws Exception
	{
		File stable = new File(temporaryFolder.getRoot(), "profile-42.community-lootshare.json");
		File legacy = new File(temporaryFolder.getRoot(), "Old-42.community-lootshare.json");
		Files.write(legacy.toPath(), "{\"schemaVersion\":5,\"sessions\":[null]}".getBytes(StandardCharsets.UTF_8));
		LootshareStorage storage = new LootshareStorage(stable, new Gson());
		assertFalse(storage.load(stable).isWritable());
		assertFalse(stable.exists());
		Files.write(new File(temporaryFolder.getRoot(), "Other-42.community-lootshare.json").toPath(), new byte[0]);
		assertFalse(storage.load(stable).isWritable());
		assertFalse(stable.exists());
		assertTrue(legacy.isFile());
	}

	@Test public void domainInvalidFilesAreReadOnlyIncludingDuplicateAndDanglingRecords() throws Exception
	{
		File file = temporaryFolder.newFile();
		LootshareStorage storage = new LootshareStorage(file, new Gson());
		String session = "{\"sessionId\":\"s\",\"partyId\":77,\"startedAt\":\"1970-01-01T00:00:00Z\",\"acceptedProposals\":[]}";
		String[] invalid = {
			"{\"schemaVersion\":5,\"proposals\":[null]}",
			"{\"schemaVersion\":5,\"sessions\":[" + session + "," + session + "]}",
			"{\"schemaVersion\":5,\"activeSessionId\":\"missing\"}",
			"{\"schemaVersion\":5,\"activeSessionId\":\"s\",\"sessions\":[" + session.replace("\"acceptedProposals\":[]", "\"acceptedProposals\":[null]") + "]}"
		};
		for (String json : invalid)
		{
			Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
			byte[] original = Files.readAllBytes(file.toPath());
			assertFalse(storage.load(file).isWritable());
			assertArrayEquals(original, Files.readAllBytes(file.toPath()));
		}
	}
	@Test public void signingIdentitySurvivesRestartAndLedgerLossAndIsNotStoredInHistory() throws Exception
	{
		File file = temporaryFolder.newFile();
		LootshareStorage first = new LootshareStorage(file, new Gson());
		assertTrue(first.load(file).isWritable());
		KeyPair key = first.decisionKey(file);
		File signer = new File(file.getAbsolutePath() + ".signer");
		assertTrue(signer.isFile());
		assertTrue(first.save(file, new LootshareState()));
		String history;
		try (InputStream input = new java.util.zip.GZIPInputStream(Files.newInputStream(file.toPath())))
		{
			history = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		String privateKey = Base64.getEncoder().encodeToString(key.getPrivate().getEncoded());
		assertFalse(history.contains(privateKey));
		assertFalse(history.contains("privateKey"));
		Files.delete(file.toPath());
		LootshareStorage restarted = new LootshareStorage(file, new Gson());
		assertTrue(restarted.load(file).isWritable());
		assertArrayEquals(key.getPrivate().getEncoded(), restarted.decisionKey(file).getPrivate().getEncoded());
		assertArrayEquals(key.getPublic().getEncoded(), restarted.decisionKey(file).getPublic().getEncoded());
		if (Files.getFileStore(signer.toPath()).supportsFileAttributeView("posix"))
		{
			assertEquals(PosixFilePermissions.fromString("rw-------"),
				Files.getPosixFilePermissions(signer.toPath()));
		}
		assertFalse(restarted.load(null).isWritable());
		assertNotNull(restarted.decisionKey(null));
		try
		{
			restarted.decisionKey(new File(temporaryFolder.getRoot(), "not-loaded"));
			fail("Unloaded profiles must not silently create a new identity on the client thread");
		}
		catch (GeneralSecurityException expected) { }
	}

	@Test public void corruptIncompleteOversizedAndMismatchedKeysArePreserved() throws Exception
	{
		KeyPair first = LootDecisionReceipt.generateKey();
		KeyPair second = LootDecisionReceipt.generateKey();
		String publicKey = LootDecisionReceipt.publicKey(first);
		String privateKey = Base64.getEncoder().encodeToString(second.getPrivate().getEncoded());
		String[] corrupt = {"null", "{}", "{bad!", "{\"publicKey\":\"bad!\",\"privateKey\":\"bad!\"}",
			"{\"publicKey\":\"" + publicKey + "\",\"privateKey\":\"" + privateKey + "\"}",
			String.join("", Collections.nCopies(2049, "x"))};
		for (String json : corrupt)
		{
			File file = temporaryFolder.newFile();
			File signer = new File(file.getAbsolutePath() + ".signer");
			Files.writeString(signer.toPath(), json);
			LootshareStorage storage = new LootshareStorage(file, new Gson());
			assertFalse(storage.load(file).isWritable());
			assertEquals(json, Files.readString(signer.toPath()));
		}
	}

	@Test public void cachedHostMetadataContainsOnlyPublicKeysAndDecodesSafely() throws Exception
	{
		ConfigManager manager = mock(ConfigManager.class);
		LootshareStorage storage = new LootshareStorage(manager, new Gson());
		HostMessage message = new HostMessage(1L, 100L, 2L);
		assertFalse(storage.recallHostState(77L).isPresent());
		storage.rememberHostState(77L, message);
		ArgumentCaptor<String> serialized = ArgumentCaptor.forClass(String.class);
		verify(manager).setConfiguration(eq(com.communitylootshare.LootshareConfig.GROUP), eq("hostState.77"), serialized.capture());
		String json = serialized.getValue();
		when(manager.getConfiguration(com.communitylootshare.LootshareConfig.GROUP, "hostState.77")).thenReturn(json);
		assertEquals(1L, storage.recallHostState(77L).get().getHostMemberId());
		assertFalse(json.contains("privateKey"));
		for (String invalid : new String[]{"null", "{bad!", "{}", String.join("", Collections.nCopies(100001, "x"))})
		{
			when(manager.getConfiguration(com.communitylootshare.LootshareConfig.GROUP, "hostState.77")).thenReturn(invalid);
			assertFalse(storage.recallHostState(77L).isPresent());
		}
	}

	@Test public void archiveDuplicatesAndInvalidReceiptsCannotHideBehindAnUnsignedIndexCopy() throws Exception
	{
		KeyPair key = LootDecisionReceipt.generateKey();
		LootProposal proposal = LootProposal.pending(77L, 1L,
			new SharedLootEvent("p", "Alice", "Boss", Instant.EPOCH,
				Collections.singletonList(new SharedLootItem(100, 100, 1L, 1000L))))
			.decide(LootProposalStatus.ACCEPTED, Instant.ofEpochSecond(1),
				Collections.singletonList(new LootshareParticipant(1L, "Alice")));
		LootshareSession session = new LootshareSession("s", 77L, Instant.EPOCH);
		session.trustDecisionKeys(Collections.singletonMap(
			LootDecisionReceipt.keyId(LootDecisionReceipt.publicKey(key), 1L), 1L));
		session.addAcceptedProposal(proposal.withDecisionReceipt(LootDecisionReceipt.sign(1L, key, proposal)));
		LootshareState state = new LootshareState();
		state.setActiveSessionId("s");
		state.setSessions(Collections.singletonList(session));
		state.setProposals(Collections.singletonList(proposal));
		Gson gson = new Gson().newBuilder().registerTypeAdapter(Instant.class,
			new InstantTypeAdapter()).create();
		com.google.gson.JsonObject json = gson.toJsonTree(state).getAsJsonObject();
		com.google.gson.JsonArray records = json.getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonArray("acceptedProposals");
		File file = temporaryFolder.newFile();
		LootshareStorage storage = new LootshareStorage(file, gson);
		Files.writeString(file.toPath(), gson.toJson(json));
		assertTrue(storage.load(file).isWritable());
		records.get(0).getAsJsonObject().getAsJsonObject("decisionReceipt").addProperty("signature", "AAAA");
		Files.writeString(file.toPath(), gson.toJson(json));
		assertFalse(storage.load(file).isWritable());
		records.add(records.get(0).deepCopy());
		Files.writeString(file.toPath(), gson.toJson(json));
		assertFalse(storage.load(file).isWritable());
	}

}
