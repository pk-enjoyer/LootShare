/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager.testing;

import com.google.gson.Gson;
import com.splitmanager.ManagerKnownPlayers;
import com.splitmanager.ManagerPlugin;
import com.splitmanager.ManagerSession;
import com.splitmanager.PluginConfig;
import com.splitmanager.models.PendingValue;
import com.splitmanager.persistence.SessionStorage;
import com.splitmanager.persistence.SessionStorageData;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class UiScenarioFixture
{
	private final PluginConfig config;
	private final ManagerKnownPlayers players;
	private final ManagerSession sessions;

	private UiScenarioFixture()
	{
		config = mock(PluginConfig.class);
		when(config.enableTour()).thenReturn(false);
		when(config.tourUpdateInfoSeenVersion()).thenReturn("3.1.0");
		when(config.directPayments()).thenReturn(false);
		when(config.copyForDiscord()).thenReturn(false);
		when(config.flipSettlementSign()).thenReturn(false);
		when(config.accountForGeTax()).thenReturn(true);
		when(config.geTaxMinimumValue()).thenReturn("15m");
		when(config.geTaxPercent()).thenReturn(2.0d);
		when(config.geTaxMaxPerLoot()).thenReturn("5m");
		when(config.defaultValueMultiplier()).thenReturn(PluginConfig.ValueMultiplier.THOUSAND);

		Gson gson = new Gson();
		players = new ManagerKnownPlayers(config, gson);
		players.addKnownPlayer("Alice");
		players.addKnownPlayer("Bob");
		players.addKnownPlayer("Cara");

		SessionStorage storage = mock(SessionStorage.class);
		when(storage.save(any(SessionStorageData.class))).thenReturn(true);
		when(storage.load()).thenReturn(new SessionStorageData());
		sessions = new ManagerSession(config, players, mock(ManagerPlugin.class), gson, storage);
	}

	public static UiScenarioFixture empty()
	{
		return new UiScenarioFixture();
	}

	public static UiScenarioFixture active()
	{
		UiScenarioFixture fixture = new UiScenarioFixture();
		fixture.sessions.startSession();
		fixture.sessions.addPlayerToActive("Alice");
		fixture.sessions.addPlayerToActive("Bob");
		fixture.sessions.addLoot("Alice", 100_000_000L);
		fixture.sessions.addLoot("Bob", 50_000_000L);
		fixture.players.trySetAltMain("Cara", "Alice");
		fixture.sessions.addPendingValue(PendingValue.of(
			PendingValue.Type.PVM, "Clan", "Alice received a drop", 25_000_000L, "Alice"));
		return fixture;
	}

	public static UiScenarioFixture loadedHistory(boolean bobHasLoot)
	{
		UiScenarioFixture fixture = new UiScenarioFixture();
		fixture.sessions.startSession();
		fixture.sessions.addPlayerToActive("Alice");
		fixture.sessions.addLoot("Alice", 100_000_000L);
		fixture.sessions.addPlayerToActive("Bob");
		if (bobHasLoot)
		{
			fixture.sessions.addLoot("Bob", 50_000_000L);
		}
		fixture.sessions.removePlayerFromSession("Bob");
		fixture.sessions.stopSession();
		String historyId = fixture.sessions.getHistorySessionsNewestFirst().get(0).getId();
		Optional<?> loaded = fixture.sessions.loadHistory(historyId);
		if (loaded.isEmpty())
		{
			throw new IllegalStateException("Unable to build loaded history fixture");
		}
		return fixture;
	}

	public PluginConfig getConfig() { return config; }
	public ManagerKnownPlayers getPlayers() { return players; }
	public ManagerSession getSessions() { return sessions; }
}
