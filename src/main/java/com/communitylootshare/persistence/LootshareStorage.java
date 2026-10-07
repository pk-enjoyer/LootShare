/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.communitylootshare.persistence;

import com.communitylootshare.domain.LootshareState;
import com.communitylootshare.domain.LootProposal;
import com.communitylootshare.domain.LootProposalStatus;
import com.communitylootshare.domain.LootDecisionReceipt;
import com.communitylootshare.LootshareConfig;
import com.communitylootshare.party.HostMessage;
import com.communitylootshare.domain.LootshareSession;
import com.communitylootshare.sessions.LootshareEngine;
import com.communitylootshare.utils.InstantTypeAdapter;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.Optional;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;

@Singleton
@Slf4j
public class LootshareStorage
{
	private static final String PLUGIN_DIRECTORY = "community-lootshare";
	private static final String FILE_SUFFIX = ".community-lootshare.json";
	static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
	static final long MAX_EXPANDED_BYTES = 64L * 1024L * 1024L;

	private final ConfigManager configManager;
	private final File fixedFile;
	private final Gson gson;
	private final Map<File, KeyPair> signingKeys = new LinkedHashMap<>();
	private final Map<Long, String> fixedHostStates = new HashMap<>();

	@Inject
	public LootshareStorage(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.fixedFile = null;
		this.gson = configuredGson(gson);
	}

	public LootshareStorage(File fixedFile, Gson gson)
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

	/** Returns a key prepared by the asynchronous profile load; never performs disk I/O. */
	public synchronized KeyPair decisionKey(@Nullable File file) throws GeneralSecurityException
	{
		KeyPair key = signingKeys.get(file);
		if (key == null)
		{
			throw new GeneralSecurityException("Profile signing key is unavailable");
		}
		return key;
	}

	private synchronized void loadDecisionKey(@Nullable File file) throws IOException, GeneralSecurityException
	{
		if (signingKeys.containsKey(file))
		{
			return;
		}
		KeyPair key;
		if (file == null)
		{
			key = LootDecisionReceipt.generateKey();
		}
		else
		{
			File keyFile = new File(file.getAbsolutePath() + ".signer");
			if (keyFile.exists())
			{
				if (!keyFile.isFile() || keyFile.length() > 2048L)
				{
					throw new IOException("Invalid signing key file");
				}
				SigningKey stored = gson.fromJson(Files.readString(keyFile.toPath()), SigningKey.class);
				if (stored == null || stored.publicKey == null || stored.privateKey == null)
				{
					throw new IOException("Incomplete signing key file");
				}
				KeyFactory factory = KeyFactory.getInstance("EC");
				key = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(stored.publicKey))),
					factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.privateKey))));
				Signature proof = Signature.getInstance("SHA256withECDSA");
				byte[] challenge = "community-lootshare-signing-key".getBytes(StandardCharsets.UTF_8);
				proof.initSign(key.getPrivate());
				proof.update(challenge);
				byte[] signed = proof.sign();
				proof.initVerify(key.getPublic());
				proof.update(challenge);
				if (!proof.verify(signed))
				{
					throw new GeneralSecurityException("Signing key pair does not match");
				}
			}
			else
			{
				key = LootDecisionReceipt.generateKey();
				Files.createDirectories(keyFile.toPath().getParent());
				try (OutputStream out = Files.newOutputStream(keyFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
				{
					try
					{
						Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------"));
					}
					catch (UnsupportedOperationException ignored)
					{
						// Non-POSIX systems use the user's RuneLite directory permissions.
					}
					out.write(gson.toJson(new SigningKey(key)).getBytes(StandardCharsets.UTF_8));
				}
			}
		}
		if (signingKeys.size() >= 16)
		{
			signingKeys.remove(signingKeys.keySet().iterator().next());
		}
		signingKeys.put(file, key);
	}

	private static final class SigningKey
	{
		private final String publicKey;
		private final String privateKey;

		private SigningKey(KeyPair key)
		{
			publicKey = LootDecisionReceipt.publicKey(key);
			privateKey = Base64.getEncoder().encodeToString(key.getPrivate().getEncoded());
		}
	}

	public synchronized void rememberHostState(long partyId, HostMessage message)
	{
		String json = gson.toJson(message);
		if (configManager == null)
		{
			fixedHostStates.put(partyId, json);
		}
		else
		{
			configManager.setConfiguration(LootshareConfig.GROUP, "hostState." + partyId, json);
		}
	}

	public synchronized Optional<HostMessage.DecodedHostState> recallHostState(long partyId)
	{
		String json = configManager == null ? fixedHostStates.get(partyId)
			: configManager.getConfiguration(LootshareConfig.GROUP, "hostState." + partyId);
		if (json == null || json.length() > 512_000)
		{
			return Optional.empty();
		}
		try
		{
			HostMessage message = gson.fromJson(json, HostMessage.class);
			if (message == null)
			{
				return Optional.empty();
			}
			message.setMemberId(message.getHostMemberId());
			return message.decode();
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to restore cached Community Lootshare host authority", e);
			return Optional.empty();
		}
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
		String fileName = "profile-" + profile.getId() + FILE_SUFFIX;
		return new File(pluginDirectory, fileName);
	}

	public LoadResult load(@Nullable File file)
	{
		try
		{
			loadDecisionKey(file);
		}
		catch (IOException | GeneralSecurityException | RuntimeException e)
		{
			// Do not log parser exceptions: they may contain private key material.
			log.warn("Community Lootshare signing identity is unavailable; preserving profile files");
			return LoadResult.readOnly(new LootshareState());
		}
		if (file == null)
		{
			return LoadResult.unavailable();
		}
		if (!file.exists())
		{
			return loadLegacyProfile(file);
		}
		if (!file.isFile() || file.length() > MAX_EXPANDED_BYTES)
		{
			log.warn("Community Lootshare history is unavailable because its storage file is invalid or oversized");
			return LoadResult.readOnly(new LootshareState());
		}
		if (file.length() == 0L)
		{
			return LoadResult.writable(new LootshareState());
		}

		try (Reader reader = openReader(file))
		{
			LootshareState state = gson.fromJson(reader, LootshareState.class);
			if (state == null)
			{
				return LoadResult.writable(new LootshareState());
			}
			if (state.getSchemaVersion() <= 0
				|| state.getSchemaVersion() > LootshareState.CURRENT_SCHEMA_VERSION)
			{
				log.warn("Community Lootshare history uses unsupported schema {}", state.getSchemaVersion());
				return LoadResult.readOnly(new LootshareState());
			}
			validateState(state);
			return LoadResult.writable(state);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Failed to load Community Lootshare history; the existing file will not be overwritten", e);
			return LoadResult.readOnly(new LootshareState());
		}
		catch (RuntimeException e)
		{
			log.warn("Community Lootshare history contains invalid records; preserving the original file", e);
			return LoadResult.readOnly(new LootshareState());
		}
	}

	private LoadResult loadLegacyProfile(File file)
	{
		String name = file.getName();
		if (!name.matches("profile-[0-9]+\\.community-lootshare\\.json"))
		{
			return LoadResult.writable(new LootshareState());
		}
		String suffix = name.substring("profile".length());
		File parent = file.getAbsoluteFile().getParentFile();
		File[] legacyFiles = parent.listFiles(candidate -> candidate.isFile()
			&& candidate.getName().endsWith(suffix) && !candidate.equals(file));
		if (legacyFiles == null || legacyFiles.length == 0)
		{
			return LoadResult.writable(new LootshareState());
		}
		if (legacyFiles.length != 1)
		{
			log.warn("Multiple legacy Community Lootshare histories match this profile; preserving them for recovery");
			return LoadResult.readOnly(new LootshareState());
		}
		LoadResult legacy = load(legacyFiles[0]);
		if (!legacy.isWritable() || !save(file, legacy.getState()))
		{
			return LoadResult.readOnly(legacy.getState());
		}
		// Retain the original name-based file as a migration backup.
		return legacy;
	}

	private static void validateState(LootshareState state)
	{
		if (state.getProposals().size() > LootshareEngine.MAX_PROPOSALS
			|| state.getSessions().size() > LootshareEngine.MAX_SESSIONS)
		{
			throw new IllegalArgumentException("History exceeds the supported record limits");
		}
		Map<String, LootProposal> records = new HashMap<>();
		List<LootProposal> allRecords = new ArrayList<>(state.getProposals());
		for (LootProposal proposal : state.getProposals())
		{
			LootProposal validated = proposal.validatedCopy();
			if (records.putIfAbsent(validated.getProposalId(), validated) != null)
			{
				throw new IllegalArgumentException("History contains duplicate proposal IDs");
			}
		}
		Set<String> sessionIds = new HashSet<>();
		Map<Long, Map<String, Long>> decisionKeys = new HashMap<>();
		boolean activeFound = state.getActiveSessionId() == null;
		for (LootshareSession persisted : state.getSessions())
		{
			Set<String> archivedIds = new HashSet<>();
			for (LootProposal proposal : persisted.getAcceptedProposals())
			{
				if (!archivedIds.add(proposal.getProposalId()))
				{
					throw new IllegalArgumentException("Session contains duplicate finalized proposals");
				}
				allRecords.add(proposal);
			}
			for (LootProposal proposal : persisted.getRejectedProposals())
			{
				if (!archivedIds.add(proposal.getProposalId()))
				{
					throw new IllegalArgumentException("Session contains duplicate finalized proposals");
				}
				allRecords.add(proposal);
			}
			LootshareSession session = persisted.snapshot();
			Map<String, Long> partyKeys = decisionKeys.computeIfAbsent(session.getPartyId(), ignored -> new HashMap<>());
			for (Map.Entry<String, Long> key : session.getDecisionKeys().entrySet())
			{
				Long existing = partyKeys.putIfAbsent(key.getKey(), key.getValue());
				if (existing != null && !existing.equals(key.getValue()))
				{
					throw new IllegalArgumentException("History contains conflicting decision keys");
				}
			}
			if (!sessionIds.add(session.getSessionId()))
			{
				throw new IllegalArgumentException("History contains duplicate session IDs");
			}
			if (session.getSessionId().equals(state.getActiveSessionId()))
			{
				activeFound = session.isActive();
			}
			for (LootProposal proposal : session.getAcceptedProposals())
			{
				validateArchivedRecord(records, proposal);
			}
			for (LootProposal proposal : session.getRejectedProposals())
			{
				validateArchivedRecord(records, proposal);
			}
		}
		if (!activeFound)
		{
			throw new IllegalArgumentException("History references an invalid active session");
		}
		for (LootProposal proposal : allRecords)
		{
			LootDecisionReceipt receipt = proposal.getDecisionReceipt();
			if (receipt != null && (!receipt.isTrustedBy(decisionKeys.getOrDefault(proposal.getPartyId(), java.util.Collections.emptyMap()))
				|| !receipt.verifies(proposal)))
			{
				throw new IllegalArgumentException("History contains an invalid decision receipt");
			}
		}
	}

	private static void validateArchivedRecord(Map<String, LootProposal> records, LootProposal proposal)
	{
		LootProposal existing = records.putIfAbsent(proposal.getProposalId(), proposal);
		if (existing != null && (!existing.hasSameIdentity(proposal)
			|| (existing.getStatus() != LootProposalStatus.PENDING
				&& (existing.getStatus() != proposal.getStatus()
					|| !existing.getDecidedAt().equals(proposal.getDecidedAt())
					|| !existing.getParticipants().equals(proposal.getParticipants())))))
		{
			throw new IllegalArgumentException("History contains conflicting proposal records");
		}
		if (existing != null && existing.getStatus() == LootProposalStatus.PENDING)
		{
			records.put(proposal.getProposalId(), proposal);
		}
	}

	private Reader openReader(File file) throws IOException
	{
		BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()));
		try
		{
			input.mark(2);
			boolean compressed = input.read() == 0x1f && input.read() == 0x8b;
			input.reset();
			if (compressed && file.length() > MAX_FILE_BYTES)
			{
				throw new IOException("Community Lootshare compressed history exceeds its storage limit");
			}
			InputStream decoded = compressed ? new GZIPInputStream(input) : input;
			return new InputStreamReader(new BoundedInputStream(decoded, MAX_EXPANDED_BYTES), StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException e)
		{
			input.close();
			throw e;
		}
	}

	public boolean save(@Nullable File file, LootshareState state)
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
			state.setSchemaVersion(LootshareState.CURRENT_SCHEMA_VERSION);
			try (FileOutputStream output = new FileOutputStream(temporaryFile);
			     FileChannel channel = output.getChannel();
			     GZIPOutputStream compressed = new GZIPOutputStream(new BoundedOutputStream(output, MAX_FILE_BYTES));
			     OutputStreamWriter writer = new OutputStreamWriter(
				     new BoundedOutputStream(compressed, MAX_EXPANDED_BYTES), StandardCharsets.UTF_8))
			{
				// Keep the existing profile path and read legacy plain JSON, but compress new
				// snapshots. Both limits are enforced before replacing the last good file.
				gson.toJson(state, writer);
				writer.flush();
				compressed.finish();
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

	private static final class BoundedOutputStream extends FilterOutputStream
	{
		private final long limit;
		private long count;

		private BoundedOutputStream(OutputStream output, long limit)
		{
			super(output);
			this.limit = limit;
		}

		@Override
		public void write(int value) throws IOException
		{
			write(new byte[]{(byte) value}, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException
		{
			if (length > limit - count)
			{
				throw new IOException("Community Lootshare history exceeds its storage limit");
			}
			out.write(bytes, offset, length);
			count += length;
		}
	}

	private static final class BoundedInputStream extends FilterInputStream
	{
		private final long limit;
		private long count;

		private BoundedInputStream(InputStream input, long limit)
		{
			super(input);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException
		{
			int value = in.read();
			if (value >= 0)
			{
				checkCount(1);
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException
		{
			int read = in.read(bytes, offset, length);
			if (read > 0)
			{
				checkCount(read);
			}
			return read;
		}

		private void checkCount(int read) throws IOException
		{
			count += read;
			if (count > limit)
			{
				throw new IOException("Community Lootshare expanded history exceeds its storage limit");
			}
		}
	}

	public static final class LoadResult
	{
		private final LootshareState state;
		private final boolean writable;

		private LoadResult(LootshareState state, boolean writable)
		{
			this.state = state;
			this.writable = writable;
		}

		private static LoadResult writable(LootshareState state)
		{
			return new LoadResult(state, true);
		}

		private static LoadResult readOnly(LootshareState state)
		{
			return new LoadResult(state, false);
		}

		private static LoadResult unavailable()
		{
			return readOnly(new LootshareState());
		}

		public LootshareState getState()
		{
			return state;
		}

		public boolean isWritable()
		{
			return writable;
		}
	}
}
