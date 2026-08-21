/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.communitylootshare.domain.CommunityLootshareState;
import com.communitylootshare.utils.InstantTypeAdapter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;

@Singleton
@Slf4j
public class CommunityLootshareStorage
{
	private static final String PLUGIN_DIRECTORY = "community-lootshare";
	private static final String FILE_SUFFIX = ".community-lootshare.json";
	private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;

	private final ConfigManager configManager;
	private final File fixedFile;
	private final Gson gson;

	@Inject
	public CommunityLootshareStorage(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.fixedFile = null;
		this.gson = configuredGson(gson);
	}

	public CommunityLootshareStorage(File fixedFile, Gson gson)
	{
		this.configManager = null;
		this.fixedFile = fixedFile;
		this.gson = configuredGson(gson);
	}

	private static Gson configuredGson(Gson gson)
	{
		if (gson == null)
		{
			throw new IllegalArgumentException("Injected Gson is required");
		}
		return gson.newBuilder()
			.registerTypeAdapter(Instant.class, new InstantTypeAdapter())
			.create();
	}

	@Nullable
	public File resolveCurrentFile()
	{
		if (fixedFile != null)
		{
			return fixedFile;
		}
		if (configManager == null)
		{
			return null;
		}
		ConfigProfile profile = configManager.getProfile();
		if (profile == null)
		{
			return null;
		}
		File pluginDirectory = new File(RuneLite.RUNELITE_DIR, PLUGIN_DIRECTORY);
		String fileName = sanitizeFilePart(profile.getName()) + "-" + profile.getId() + FILE_SUFFIX;
		return new File(pluginDirectory, fileName);
	}

	public LoadResult load(@Nullable File file)
	{
		if (file == null)
		{
			return LoadResult.unavailable();
		}
		if (!file.exists())
		{
			return LoadResult.writable(new CommunityLootshareState());
		}
		if (!file.isFile() || file.length() > MAX_FILE_BYTES)
		{
			log.warn("Community Lootshare history is unavailable because its storage file is invalid or oversized");
			return LoadResult.readOnly(new CommunityLootshareState());
		}
		if (file.length() == 0L)
		{
			return LoadResult.writable(new CommunityLootshareState());
		}

		try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8))
		{
			CommunityLootshareState state = gson.fromJson(reader, CommunityLootshareState.class);
			if (state == null)
			{
				return LoadResult.writable(new CommunityLootshareState());
			}
			if (state.getSchemaVersion() <= 0
				|| state.getSchemaVersion() > CommunityLootshareState.CURRENT_SCHEMA_VERSION)
			{
				log.warn("Community Lootshare history uses unsupported schema {}", state.getSchemaVersion());
				return LoadResult.readOnly(new CommunityLootshareState());
			}
			return LoadResult.writable(state);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Failed to load Community Lootshare history; the existing file will not be overwritten", e);
			return LoadResult.readOnly(new CommunityLootshareState());
		}
	}

	public boolean save(@Nullable File file, CommunityLootshareState state)
	{
		if (file == null || state == null)
		{
			return false;
		}

		File temporaryFile = null;
		try
		{
			File parent = file.getAbsoluteFile().getParentFile();
			Files.createDirectories(parent.toPath());
			String temporaryPrefix = file.getName().length() >= 3 ? file.getName() : "cls";
			temporaryFile = File.createTempFile(temporaryPrefix, ".tmp", parent);
			state.setSchemaVersion(CommunityLootshareState.CURRENT_SCHEMA_VERSION);
			try (FileOutputStream output = new FileOutputStream(temporaryFile);
			     FileChannel channel = output.getChannel();
			     OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8))
			{
				gson.toJson(state, writer);
				writer.flush();
				channel.force(true);
			}

			try
			{
				Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ignored)
			{
				Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			temporaryFile = null;
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Failed to save Community Lootshare history", e);
			return false;
		}
		finally
		{
			if (temporaryFile != null)
			{
				try
				{
					Files.deleteIfExists(temporaryFile.toPath());
				}
				catch (IOException ignored)
				{
					log.debug("Failed to remove a Community Lootshare temporary file", ignored);
				}
			}
		}
	}

	private static String sanitizeFilePart(String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return "profile";
		}
		String sanitized = value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_");
		while (sanitized.contains(".."))
		{
			sanitized = sanitized.replace("..", "_");
		}
		return sanitized.isEmpty() ? "profile" : sanitized;
	}

	public static final class LoadResult
	{
		private final CommunityLootshareState state;
		private final boolean writable;

		private LoadResult(CommunityLootshareState state, boolean writable)
		{
			this.state = state;
			this.writable = writable;
		}

		private static LoadResult writable(CommunityLootshareState state)
		{
			return new LoadResult(state, true);
		}

		private static LoadResult readOnly(CommunityLootshareState state)
		{
			return new LoadResult(state, false);
		}

		private static LoadResult unavailable()
		{
			return readOnly(new CommunityLootshareState());
		}

		public CommunityLootshareState getState()
		{
			return state;
		}

		public boolean isWritable()
		{
			return writable;
		}
	}
}
