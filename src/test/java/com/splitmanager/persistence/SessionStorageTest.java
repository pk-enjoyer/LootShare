/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.persistence;

import com.google.gson.Gson;
import com.splitmanager.PluginConfig;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.models.Session;
import com.splitmanager.utils.InstantTypeAdapter;
import java.io.File;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;

public class SessionStorageTest
{
	private final Gson gson = new Gson().newBuilder()
		.registerTypeAdapter(Instant.class, new InstantTypeAdapter())
		.create();

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void savesAndLoadsVersionedSessionData() throws Exception
	{
		File file = temporaryFolder.newFile("sessions.json");
		SessionStorage storage = new SessionStorage(file, gson);
		Session session = new Session("session-id", Instant.EPOCH, null);
		SessionStorageData data = new SessionStorageData();
		data.setCurrentSessionId("session-id");
		data.setHistoryLoaded(true);
		data.setSessions(Collections.singletonList(session));

		assertTrue(storage.save(data));

		SessionStorageData restored = storage.load();
		assertEquals(SessionStorageData.CURRENT_SCHEMA_VERSION, restored.getSchemaVersion());
		assertEquals("session-id", restored.getCurrentSessionId());
		assertTrue(restored.isHistoryLoaded());
		assertEquals(1, restored.getSessions().size());
		assertEquals("session-id", restored.getSessions().get(0).getId());
	}

	@Test
	public void loadsSchemaOneEventsFromLegacyKillsFieldAndSavesEventsField() throws Exception
	{
		File file = temporaryFolder.newFile("legacy-sessions.json");
		String legacyJson = "{"
			+ "\"schemaVersion\":1,"
			+ "\"currentSessionId\":\"session-id\","
			+ "\"historyLoaded\":true,"
			+ "\"sessions\":[{"
			+ "\"id\":\"session-id\","
			+ "\"start\":\"1970-01-01T00:00:00Z\","
			+ "\"players\":[\"Alice\"],"
			+ "\"kills\":[{"
			+ "\"sessionId\":\"session-id\","
			+ "\"at\":\"1970-01-01T00:00:01Z\","
			+ "\"player\":\"Alice\","
			+ "\"amount\":123"
			+ "}]"
			+ "}]"
			+ "}";
		Files.write(file.toPath(), Collections.singletonList(legacyJson), StandardCharsets.UTF_8);
		SessionStorage storage = new SessionStorage(file, gson);

		SessionStorageData restored = storage.load();

		assertEquals(1, restored.getSessions().size());
		Session restoredSession = restored.getSessions().get(0);
		assertEquals(1, restoredSession.getEvents().size());
		SplitEvent restoredEvent = restoredSession.getEvents().get(0);
		assertEquals("Alice", restoredEvent.getPlayer());
		assertEquals(123L, restoredEvent.getAmount().longValue());

		assertTrue(storage.save(restored));
		String savedJson = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue(savedJson.contains("\"schemaVersion\":3"));
		assertTrue(savedJson.contains("\"events\""));
		assertTrue(savedJson.contains("\"threads\""));
		assertFalse(savedJson.contains("\"kills\":"));
	}

	@Test
	public void missingFileReturnsEmptyData()
	{
		File file = new File(temporaryFolder.getRoot(), "missing/sessions.json");
		SessionStorage storage = new SessionStorage(file, gson);

		assertFalse(storage.exists());
		SessionStorageData data = storage.load();

		assertEquals(SessionStorageData.CURRENT_SCHEMA_VERSION, data.getSchemaVersion());
		assertTrue(data.getSessions().isEmpty());
		assertNull(data.getCurrentSessionId());
		assertFalse(data.isHistoryLoaded());
	}

	@Test
	public void invalidJsonReturnsEmptyData() throws Exception
	{
		File file = temporaryFolder.newFile("sessions.json");
		Files.write(file.toPath(), Collections.singletonList("{not valid json"), StandardCharsets.UTF_8);
		SessionStorage storage = new SessionStorage(file, gson);

		SessionStorageData data = storage.load();

		assertTrue(data.getSessions().isEmpty());
		assertNull(data.getCurrentSessionId());
	}

	@Test
	public void saveCreatesParentDirectories()
	{
		File file = new File(temporaryFolder.getRoot(), "nested/sessions.json");
		SessionStorage storage = new SessionStorage(file, gson);

		assertTrue(storage.save(new SessionStorageData()));
		assertTrue(file.exists());
	}

	@Test
	public void profileBackedStorageSanitizesProfileNameForFilePath()
	{
		ConfigManager configManager = mock(ConfigManager.class);
		ConfigProfile profile = mock(ConfigProfile.class);
		when(configManager.getProfile()).thenReturn(profile);
		when(profile.getName()).thenReturn("../escaped/profile");
		when(profile.getId()).thenReturn(123L);

		SessionStorage storage = new SessionStorage(configManager, gson);

		Path pluginDir = new File(RuneLite.RUNELITE_DIR, "auto-split-manager").toPath().toAbsolutePath().normalize();
		Path storagePath = new File(storage.describeLocation()).toPath().toAbsolutePath().normalize();
		assertTrue(storagePath.startsWith(pluginDir));
		assertFalse(storagePath.toString().contains(".."));
	}

	@Test
	public void saveDoesNotArchiveWhenPrimaryExceedsCap()
	{
		File file = new File(temporaryFolder.getRoot(), "sessions.json");
		SessionStorage storage = new SessionStorage(file, gson, 2000L);
		SessionStorageData data = new SessionStorageData();
		List<Session> sessions = new ArrayList<>();
		sessions.addAll(completedThread("old", Instant.EPOCH, repeat("OldPlayer", 300)));
		sessions.addAll(completedThread("new", Instant.EPOCH.plusSeconds(100), "NewPlayer"));
		data.setSessions(sessions);

		assertTrue(storage.save(data));

		assertTrue(storage.load().getSessions().stream().anyMatch(session -> "old".equals(session.getId())));
		assertFalse(storage.hasArchives());
	}

	@Test
	public void archivePrimaryIfNeededArchivesOldestCompletedThreadsWhenPrimaryExceedsCap()
	{
		File file = new File(temporaryFolder.getRoot(), "sessions.json");
		SessionStorage storage = new SessionStorage(file, gson, 2000L);
		SessionStorageData data = new SessionStorageData();
		List<Session> sessions = new ArrayList<>();
		sessions.addAll(completedThread("old", Instant.EPOCH, repeat("OldPlayer", 300)));
		sessions.addAll(completedThread("new", Instant.EPOCH.plusSeconds(100), "NewPlayer"));
		data.setSessions(sessions);

		assertTrue(storage.save(data));
		Optional<SessionStorageData> archivedData = storage.archivePrimaryIfNeeded();

		SessionStorageData primary = storage.load();
		assertTrue(archivedData.isPresent());
		assertFalse(primary.getSessions().stream().anyMatch(session -> "old".equals(session.getId())));
		assertTrue(primary.getSessions().stream().anyMatch(session -> "new".equals(session.getId())));
		assertFalse(archivedData.get().getSessions().stream().anyMatch(session -> "old".equals(session.getId())));
		assertTrue(archivedData.get().getSessions().stream().anyMatch(session -> "new".equals(session.getId())));
		assertTrue(storage.hasArchives());

		SessionStorageData archive = loadArchive(file);
		assertTrue(archive.getSessions().stream().anyMatch(session -> "old".equals(session.getId())));
		assertTrue(archive.getSessions().stream().anyMatch(session -> "old-child".equals(session.getId())));
		assertFalse(archive.getSessions().stream().anyMatch(session -> "new".equals(session.getId())));
	}

	@Test
	public void archivePrimaryIfNeededCanRunOnlyOnceForRetainedPrimaryData()
	{
		File file = new File(temporaryFolder.getRoot(), "sessions.json");
		SessionStorage storage = new SessionStorage(file, gson, 2000L);
		SessionStorageData data = new SessionStorageData();
		List<Session> sessions = new ArrayList<>();
		sessions.addAll(completedThread("old", Instant.EPOCH, repeat("OldPlayer", 300)));
		sessions.addAll(completedThread("new", Instant.EPOCH.plusSeconds(100), "NewPlayer"));
		data.setSessions(sessions);

		assertTrue(storage.save(data));
		Optional<SessionStorageData> archivedData = storage.archivePrimaryIfNeeded();
		assertTrue(archivedData.isPresent());
		assertFalse(archivedData.get().getSessions().stream().anyMatch(session -> "old".equals(session.getId())));
		assertFalse(storage.archivePrimaryIfNeeded().isPresent());

		File[] archiveFiles = file.getParentFile().listFiles((dir, name) -> name.contains(".archive-"));
		assertTrue(archiveFiles != null);
		assertEquals(1, archiveFiles.length);
	}

	@Test
	public void archivePrimaryIfNeededKeepsActiveCurrentThreadOutOfArchives()
	{
		File file = new File(temporaryFolder.getRoot(), "sessions.json");
		SessionStorage storage = new SessionStorage(file, gson, 1200L);
		SessionStorageData data = new SessionStorageData();
		List<Session> sessions = new ArrayList<>();
		sessions.addAll(completedThread("old", Instant.EPOCH, repeat("OldPlayer", 300)));
		Session activeRoot = new Session("active", Instant.EPOCH.plusSeconds(100), null);
		Session activeChild = new Session("active-child", Instant.EPOCH.plusSeconds(101), "active");
		activeChild.getPlayers().add(repeat("ActivePlayer", 300));
		sessions.add(activeRoot);
		sessions.add(activeChild);
		data.setSessions(sessions);
		data.setCurrentSessionId("active-child");

		assertTrue(storage.save(data));
		Optional<SessionStorageData> archivedData = storage.archivePrimaryIfNeeded();

		SessionStorageData primary = storage.load();
		assertTrue(archivedData.isPresent());
		assertTrue(primary.getSessions().stream().anyMatch(session -> "active".equals(session.getId())));
		assertTrue(primary.getSessions().stream().anyMatch(session -> "active-child".equals(session.getId())));
		assertEquals("active-child", primary.getCurrentSessionId());

		SessionStorageData archive = loadArchive(file);
		assertTrue(archive.getSessions().stream().anyMatch(session -> "old".equals(session.getId())));
		assertFalse(archive.getSessions().stream().anyMatch(session -> "active".equals(session.getId())));
	}

	@Test
	public void profileStorageHandlesUnresolvedAndBlankProfilesWithoutWriting() throws Exception
	{
		ConfigManager unresolvedConfig = mock(ConfigManager.class);
		SessionStorage unresolved = new SessionStorage(unresolvedConfig, gson);

		assertEquals("unresolved session storage", unresolved.describeLocation());
		assertFalse(unresolved.exists());
		assertFalse(unresolved.save(new SessionStorageData()));
		assertFalse(unresolved.hasArchives());

		ConfigManager blankConfig = mock(ConfigManager.class);
		ConfigProfile blankProfile = mock(ConfigProfile.class);
		when(blankConfig.getProfile()).thenReturn(blankProfile);
		when(blankProfile.getName()).thenReturn(" ");
		when(blankProfile.getId()).thenReturn(42L);
		assertTrue(new SessionStorage(blankConfig, gson).describeLocation().contains("profile-42"));

		File parentFile = temporaryFolder.newFile("not-a-directory");
		SessionStorage invalidParent = new SessionStorage(new File(parentFile, "sessions.json"), gson);
		assertFalse(invalidParent.hasArchives());
		assertFalse(invalidParent.save(new SessionStorageData()));
	}

	@Test
	public void loadRejectsNullAndFutureSchemaDocuments() throws Exception
	{
		File file = temporaryFolder.newFile("future.json");
		SessionStorage storage = new SessionStorage(file, gson);

		Files.write(file.toPath(), Collections.singletonList("null"), StandardCharsets.UTF_8);
		assertTrue(storage.load().getSessions().isEmpty());

		Files.write(file.toPath(), Collections.singletonList(
			"{\"schemaVersion\":" + (SessionStorageData.CURRENT_SCHEMA_VERSION + 1) + ",\"sessions\":[]}"),
			StandardCharsets.UTF_8);
		SessionStorageData future = storage.load();
		assertEquals(SessionStorageData.CURRENT_SCHEMA_VERSION, future.getSchemaVersion());
		assertTrue(future.getSessions().isEmpty());
	}

	@Test
	public void backupAndArchiveEdgeCasesStayRecoverable() throws Exception
	{
		File missing = new File(temporaryFolder.getRoot(), "missing.json");
		SessionStorage missingStorage = new SessionStorage(missing, gson);
		assertFalse(missingStorage.backupPrimaryIfExists("schema/one"));

		File primary = temporaryFolder.newFile("profile.auto-split-manager.sessions.json");
		SessionStorage primaryStorage = new SessionStorage(primary, gson, 200L);
		assertTrue(primaryStorage.save(new SessionStorageData()));
		assertTrue(primaryStorage.backupPrimaryIfExists("schema/one"));
		assertFalse(primaryStorage.hasArchives());
		File archive = new File(temporaryFolder.getRoot(),
			"profile.archive-001.auto-split-manager.sessions.json");
		assertTrue(archive.createNewFile());
		assertTrue(primaryStorage.hasArchives());

		Session activeRoot = new Session("active", Instant.EPOCH, null);
		activeRoot.getPlayers().add(repeat("Active", 200));
		SessionStorageData activeOnly = new SessionStorageData();
		activeOnly.setSessions(Collections.singletonList(activeRoot));
		assertTrue(primaryStorage.save(activeOnly));
		assertFalse(primaryStorage.archivePrimaryIfNeeded().isPresent());
	}

	@Test
	public void configBackedStorageCoversMigrationAndFailurePaths()
	{
		ConfigManager configManager = mock(ConfigManager.class);
		ConfigProfile profile = mock(ConfigProfile.class);
		when(configManager.getProfile()).thenReturn(profile);
		when(profile.getName()).thenReturn("profile");
		when(profile.getId()).thenReturn(7L);
		SessionStorage profileStorage = new SessionStorage(configManager, gson);
		PluginConfig keys = mock(PluginConfig.class);
		profileStorage.clearLegacySessionConfig(keys);
		verify(configManager).unsetConfiguration(PluginConfig.GROUP, PluginConfig.KEY_SESSIONS_JSON);
		verify(configManager).unsetConfiguration(PluginConfig.GROUP, PluginConfig.KEY_CURRENT_SESSION_ID);
		verify(configManager).unsetConfiguration(PluginConfig.GROUP, PluginConfig.KEY_HISTORY_LOADED);

		PluginConfig legacy = mock(PluginConfig.class);
		SessionStorage legacyStorage = SessionStorage.legacyConfig(legacy, gson);
		assertTrue(legacyStorage.isLegacyConfigStore());
		assertTrue(legacyStorage.canMigrateLegacy(legacy));
		assertFalse(legacyStorage.backupPrimaryIfExists("backup"));
		assertFalse(legacyStorage.archivePrimaryIfNeeded().isPresent());

		when(legacy.sessionsJson()).thenReturn("{invalid");
		assertFalse(legacyStorage.canMigrateLegacy(legacy));
		assertTrue(legacyStorage.loadLegacy(legacy).getSessions().isEmpty());

		doThrow(new RuntimeException("config unavailable")).when(legacy).sessionsJson(anyString());
		assertFalse(legacyStorage.save(new SessionStorageData()));
	}

	@Test
	public void storageDataRepairsNullCollectionsAfterDeserialization()
	{
		SessionStorageData data = gson.fromJson("{\"sessions\":null,\"threads\":null}", SessionStorageData.class);
		assertNotNull(data);
		assertTrue(data.getSessions().isEmpty());
		assertTrue(data.getThreads().isEmpty());
	}

	private List<Session> completedThread(String rootId, Instant start, String playerName)
	{
		Session root = new Session(rootId, start, null);
		root.setEnd(start.plusSeconds(10));
		Session child = new Session(rootId + "-child", start.plusSeconds(1), rootId);
		child.setEnd(start.plusSeconds(10));
		child.getPlayers().add(playerName);
		child.getEvents().add(new SplitEvent(child.getId(), playerName, 100000L, start.plusSeconds(2)));
		return Arrays.asList(root, child);
	}

	private SessionStorageData loadArchive(File primaryFile)
	{
		File[] archiveFiles = primaryFile.getParentFile().listFiles((dir, name) -> name.contains(".archive-"));
		assertTrue(archiveFiles != null && archiveFiles.length == 1);
		return new SessionStorage(archiveFiles[0], gson).load();
	}

	private String repeat(String value, int times)
	{
		StringBuilder builder = new StringBuilder(value.length() * times);
		for (int i = 0; i < times; i++)
		{
			builder.append(value);
		}
		return builder.toString();
	}
}
