/*
 * Copyright (c) 2025, pk-enjoyer
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.splitmanager;

import com.google.gson.Gson;
import com.splitmanager.models.SplitEvent;
import com.splitmanager.models.PendingValue;
import com.splitmanager.models.PlayerMetrics;
import com.splitmanager.models.Session;
import com.splitmanager.models.SettlementConfigSnapshot;
import com.splitmanager.persistence.SessionStorage;
import com.splitmanager.persistence.SessionStorageData;
import com.splitmanager.sessions.SplitCalculator;
import com.splitmanager.sessions.SplitThreadAdapter;
import com.splitmanager.utils.Formats;
import com.splitmanager.utils.InstantTypeAdapter;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages sessions, persistence, and all the logic for roster changes,
 * child sessions, and live split calculations.
 */
@Singleton
@Slf4j
public class ManagerSession
{
	private static final String MIN_HISTORY_EDIT_VERSION = Session.CURRENT_PLUGIN_VERSION;
	private static final int MAX_HISTORY_EDIT_UNDO_STEPS = 50;

	private final Gson gson;
	private final Map<String, Session> sessions = new LinkedHashMap<>();
	private final List<PendingValue> pendingValues = new ArrayList<>();
	private final ManagerKnownPlayers playerManager;
	private final PluginConfig config;
	private final ManagerPlugin pluginManager;
	private final SessionStorage sessionStorage;
	private final SplitCalculator splitCalculator;
	private final List<HistoryEditSnapshot> historyEditUndoStack = new ArrayList<>();
	private final List<HistoryEditSnapshot> historyEditRedoStack = new ArrayList<>();
	// Cache of all events grouped by mother session id to avoid recomputing on every UI refresh
	private final Map<String, List<SplitEvent>> motherEventsCache = new LinkedHashMap<>();
	@Setter
	private BooleanSupplier historyEditWarningHandler;
	private boolean historyEditWarningAccepted;
	@Getter
	private boolean historyDirty;
	private String historyOriginalSessionsJson;
	private String currentSessionId;
	private String lastMoveEventFailureMessage;
	@Getter
	private boolean historyLoaded;

	/**
	 * Construct a new ManagerSession bound to the session storage.
	 * This instance owns all in-memory session state and persists it via a versioned JSON file.
	 *
	 * @param config backing configuration/store used to load and save state
	 */
	@Inject
	public ManagerSession(PluginConfig config, ManagerKnownPlayers playerManager, ManagerPlugin pluginManager, Gson gson,
	                      SessionStorage sessionStorage)
	{
		this.config = config;
		this.playerManager = playerManager;
		// Use injected client's Gson, customize via newBuilder per guidelines
		this.gson = gson.newBuilder()
			.registerTypeAdapter(Instant.class, new InstantTypeAdapter())
			.create();
		this.pluginManager = pluginManager;
		this.sessionStorage = sessionStorage;
		this.splitCalculator = new SplitCalculator();
	}

	public ManagerSession(PluginConfig config, ManagerKnownPlayers playerManager, ManagerPlugin pluginManager, Gson gson)
	{
		this(config, playerManager, pluginManager, gson, SessionStorage.legacyConfig(config, gson));
	}

	/**
	 * Utility: convert empty string to null when reading config values.
	 */
	private static String emptyToNull(String s)
	{
		return s == null || s.isEmpty() ? null : s;
	}

	/**
	 * Force a full rebuild of the events cache for the current session's thread.
	 */
	public void invalidateEventsCache()
	{
		getCurrentSession().ifPresent(curr -> {
			String motherId = (curr.getMotherId() == null) ? curr.getId() : curr.getMotherId();
			motherEventsCache.remove(motherId);
		});
	}

	public void insertLootAt(int index, String player, Long amount)
	{
		Session editable = getCurrentEditableSession().orElse(null);
		if (editable == null || amount == null)
		{
			return;
		}

		List<SplitEvent> allEvents = getAllEvents();
		if (index < 0 || index > allEvents.size())
		{
			return;
		}

		String mainPlayer = resolveMainName(player);
		if (mainPlayer == null)
		{
			return;
		}
		if (!prepareHistoryMutation())
		{
			return;
		}

		String targetSessionId = editable.getId();
		if (!allEvents.isEmpty())
		{
			int targetIndex = index < allEvents.size() ? index : allEvents.size() - 1;
			targetSessionId = allEvents.get(targetIndex).getSessionId();
		}

		Session targetSession = sessions.get(targetSessionId);
		if (targetSession == null)
		{
			targetSession = editable;
		}

		SplitEvent event = new SplitEvent(targetSession.getId(), mainPlayer, amount, Instant.now());
		event.setType(SplitEvent.TYPE_LOOT);
		List<SplitEvent> reorderedEvents = new ArrayList<>(allEvents);
		reorderedEvents.add(index, event);
		TimelineValidation validation = validateTimeline(reorderedEvents, getCurrentSession().orElse(editable));
		if (!validation.valid)
		{
			return;
		}
		if (rebuildCurrentThreadFromEvents(reorderedEvents))
		{
			saveAfterMutation();
		}
	}

	public boolean removeEventAt(int index)
	{
		return removeEventsAt(Collections.singletonList(index));
	}

	public boolean removeEventsAt(List<Integer> indices)
	{
		lastMoveEventFailureMessage = null;
		List<SplitEvent> allEvents = getAllEvents();
		if (indices == null || indices.isEmpty())
		{
			return false;
		}
		List<SplitEvent> eventsToRemove = new ArrayList<>();
		for (Integer index : indices)
		{
			if (index != null && index >= 0 && index < allEvents.size())
			{
				SplitEvent event = allEvents.get(index);
				if (!eventsToRemove.contains(event))
				{
					eventsToRemove.add(event);
				}
			}
		}
		if (eventsToRemove.isEmpty())
		{
			return false;
		}
		List<SplitEvent> retainedEvents = new ArrayList<>(allEvents);
		retainedEvents.removeAll(eventsToRemove);
		TimelineValidation validation = validateTimeline(retainedEvents, getCurrentSession().orElse(null));
		if (!validation.valid)
		{
			lastMoveEventFailureMessage = validation.message;
			return false;
		}
		HistoryEditSnapshot undoSnapshot = captureHistoryEditSnapshot();
		if (!prepareHistoryMutation())
		{
			lastMoveEventFailureMessage = "History edit was cancelled.";
			return false;
		}
		if (rebuildCurrentThreadFromEvents(retainedEvents))
		{
			pushHistoryEditUndoSnapshot(undoSnapshot);
			saveAfterHistoryTableMutation();
			return true;
		}
		lastMoveEventFailureMessage = "Cannot remove that history row.";
		return false;
	}

	public boolean moveEvent(int fromIndex, int toIndex)
	{
		lastMoveEventFailureMessage = null;
		List<SplitEvent> allEvents = getAllEvents();
		if (fromIndex < 0 || fromIndex >= allEvents.size() || toIndex < 0 || toIndex > allEvents.size())
		{
			return rejectMove("Cannot move that history row.");
		}
		if (toIndex == fromIndex || toIndex == fromIndex + 1)
		{
			return false;
		}

		SplitEvent event = allEvents.get(fromIndex);
		List<SplitEvent> remainingEvents = new ArrayList<>(allEvents);
		remainingEvents.remove(fromIndex);

		int insertionIndex = toIndex;
		if (fromIndex < insertionIndex)
		{
			insertionIndex--;
		}
		insertionIndex = Math.min(insertionIndex, remainingEvents.size());
		List<SplitEvent> reorderedEvents = new ArrayList<>(remainingEvents);
		reorderedEvents.add(insertionIndex, event);
		TimelineValidation validation = validateTimeline(reorderedEvents, getCurrentSession().orElse(null));
		if (!validation.valid)
		{
			return rejectMove(validation.message);
		}
		HistoryEditSnapshot undoSnapshot = captureHistoryEditSnapshot();
		if (!prepareHistoryMutation())
		{
			return rejectMove("History edit was cancelled.");
		}

		if (!rebuildCurrentThreadFromEvents(reorderedEvents))
		{
			return rejectMove("Cannot move that history row.");
		}
		pushHistoryEditUndoSnapshot(undoSnapshot);
		saveAfterHistoryTableMutation();
		return true;
	}

	public boolean updateHistoryLootEventAt(int index, String player, Long amount)
	{
		lastMoveEventFailureMessage = null;
		List<SplitEvent> allEvents = getAllEvents();
		if (index < 0 || index >= allEvents.size())
		{
			return rejectMove("Cannot edit that history row.");
		}

		SplitEvent original = allEvents.get(index);
		if (original == null || !original.isLootEvent())
		{
			return rejectMove("Only loot rows can be edited.");
		}

		String resolvedPlayer = player;
		if (resolvedPlayer != null)
		{
			resolvedPlayer = resolveMainName(resolvedPlayer);
			if (resolvedPlayer == null || resolvedPlayer.isBlank())
			{
				return rejectMove("Player cannot be blank.");
			}
			resolvedPlayer = resolvedPlayer.trim();
		}

		Long resolvedAmount = amount;
		if (resolvedAmount != null && resolvedAmount < 0L)
		{
			return rejectMove("Loot cannot be negative.");
		}

		if ((resolvedPlayer == null || sameNullableString(resolvedPlayer, original.getPlayer()))
			&& (resolvedAmount == null || resolvedAmount.equals(original.getAmount())))
		{
			return false;
		}

		List<SplitEvent> candidateEvents = copyEventsPreservingSessionIds(allEvents);
		SplitEvent candidate = candidateEvents.get(index);
		if (resolvedPlayer != null)
		{
			candidate.setPlayer(resolvedPlayer);
		}
		if (resolvedAmount != null)
		{
			candidate.setAmount(resolvedAmount);
		}

		TimelineValidation validation = validateTimeline(candidateEvents, getCurrentSession().orElse(null));
		if (!validation.valid)
		{
			return rejectMove(validation.message);
		}
		HistoryEditSnapshot undoSnapshot = captureHistoryEditSnapshot();
		if (!prepareHistoryMutation())
		{
			return rejectMove("History edit was cancelled.");
		}

		if (resolvedPlayer != null)
		{
			original.setPlayer(resolvedPlayer);
		}
		if (resolvedAmount != null)
		{
			original.setAmount(resolvedAmount);
		}
		pushHistoryEditUndoSnapshot(undoSnapshot);
		markHistoryTableMutation();
		return true;
	}

	public String getLastMoveEventFailureMessage()
	{
		return lastMoveEventFailureMessage;
	}

	private boolean rejectMove(String message)
	{
		lastMoveEventFailureMessage = message;
		return false;
	}

	private void applyRosterEvent(Set<String> roster, SplitEvent event)
	{
		if (event == null || event.getPlayer() == null)
		{
			return;
		}
		if (SplitEvent.TYPE_JOINED.equalsIgnoreCase(event.getType()))
		{
			addPlayerName(roster, event.getPlayer());
		}
		else if (SplitEvent.TYPE_LEFT.equalsIgnoreCase(event.getType()))
		{
			removePlayerName(roster, event.getPlayer());
		}
	}

	private void addPlayerName(Set<String> roster, String player)
	{
		for (String existing : roster)
		{
			if (existing.equalsIgnoreCase(player))
			{
				return;
			}
		}
		roster.add(player);
	}

	private void removePlayerName(Set<String> roster, String player)
	{
		roster.removeIf(existing -> existing.equalsIgnoreCase(player));
	}

	private SplitEvent copyEventForSession(SplitEvent event, String sessionId)
	{
		SplitEvent copy = new SplitEvent(sessionId, event.getPlayer(), event.getAmount(), event.getAt());
		copy.setType(event.getType());
		return copy;
	}

	private List<SplitEvent> copyEventsPreservingSessionIds(List<SplitEvent> events)
	{
		List<SplitEvent> copies = new ArrayList<>();
		for (SplitEvent event : events)
		{
			if (event != null)
			{
				copies.add(copyEventForSession(event, event.getSessionId()));
			}
		}
		return copies;
	}

	private boolean sameNullableString(String first, String second)
	{
		if (first == null)
		{
			return second == null;
		}
		return first.equals(second);
	}

	public boolean canUndoHistoryEdit()
	{
		return !historyEditUndoStack.isEmpty();
	}

	public boolean canRedoHistoryEdit()
	{
		return !historyEditRedoStack.isEmpty();
	}

	public boolean undoHistoryEdit()
	{
		if (!canUndoHistoryEdit())
		{
			return false;
		}

		HistoryEditSnapshot current = captureHistoryEditSnapshot();
		HistoryEditSnapshot previous = historyEditUndoStack.remove(historyEditUndoStack.size() - 1);
		historyEditRedoStack.add(current);
		restoreHistoryEditSnapshot(previous);
		return true;
	}

	public boolean redoHistoryEdit()
	{
		if (!canRedoHistoryEdit())
		{
			return false;
		}

		HistoryEditSnapshot current = captureHistoryEditSnapshot();
		HistoryEditSnapshot next = historyEditRedoStack.remove(historyEditRedoStack.size() - 1);
		pushHistoryEditUndoSnapshot(current, false);
		restoreHistoryEditSnapshot(next);
		return true;
	}

	public void clearHistoryEditUndo()
	{
		historyEditUndoStack.clear();
		historyEditRedoStack.clear();
	}

	private HistoryEditSnapshot captureHistoryEditSnapshot()
	{
		return new HistoryEditSnapshot(copySessions(sessions), currentSessionId, historyLoaded, historyDirty);
	}

	private void pushHistoryEditUndoSnapshot(HistoryEditSnapshot snapshot)
	{
		pushHistoryEditUndoSnapshot(snapshot, true);
	}

	private void pushHistoryEditUndoSnapshot(HistoryEditSnapshot snapshot, boolean clearRedo)
	{
		if (snapshot == null)
		{
			return;
		}
		historyEditUndoStack.add(snapshot);
		while (historyEditUndoStack.size() > MAX_HISTORY_EDIT_UNDO_STEPS)
		{
			historyEditUndoStack.remove(0);
		}
		if (clearRedo)
		{
			historyEditRedoStack.clear();
		}
	}

	private void restoreHistoryEditSnapshot(HistoryEditSnapshot snapshot)
	{
		if (snapshot == null)
		{
			return;
		}
		sessions.clear();
		sessions.putAll(copySessions(snapshot.sessions));
		currentSessionId = snapshot.currentSessionId;
		historyLoaded = snapshot.historyLoaded;
		historyDirty = snapshot.historyDirty;
		motherEventsCache.clear();
		if (!historyLoaded)
		{
			saveToConfig();
		}
	}

	private LinkedHashMap<String, Session> copySessions(Map<String, Session> source)
	{
		LinkedHashMap<String, Session> copies = new LinkedHashMap<>();
		for (Map.Entry<String, Session> entry : source.entrySet())
		{
			Session session = entry.getValue();
			if (session != null)
			{
				copies.put(entry.getKey(), copySessionSnapshot(session));
			}
		}
		return copies;
	}

	private Session copySessionSnapshot(Session source)
	{
		Session copy = new Session(source.getId(), source.getStart(), source.getMotherId());
		copy.setEnd(source.getEnd());
		copy.setPluginVersion(source.getPluginVersion());
		copy.setSettlementConfigAtStart(copySettlementConfigSnapshot(source.getSettlementConfigAtStart()));
		copy.setSettlementConfigAtEnd(copySettlementConfigSnapshot(source.getSettlementConfigAtEnd()));
		copy.setAltMappingAtStart(copyStringMap(source.getAltMappingAtStart()));
		copy.setAltMappingAtEnd(copyStringMap(source.getAltMappingAtEnd()));
		copy.getPlayers().addAll(source.getPlayers());
		for (SplitEvent event : source.getEvents())
		{
			if (event != null)
			{
				copy.getEvents().add(copyEventForSession(event, source.getId()));
			}
		}
		return copy;
	}

	private SettlementConfigSnapshot copySettlementConfigSnapshot(SettlementConfigSnapshot source)
	{
		if (source == null)
		{
			return null;
		}
		return new SettlementConfigSnapshot(
			source.isAccountForGeTax(),
			source.getGeTaxMinimumValue(),
			source.getGeTaxPercent(),
			source.getGeTaxMaxPerLoot());
	}

	private Map<String, String> copyStringMap(Map<String, String> source)
	{
		return source == null ? null : new LinkedHashMap<>(source);
	}

	private boolean rebuildCurrentThreadFromEvents(List<SplitEvent> orderedEvents)
	{
		Session current = getCurrentSession().orElse(null);
		if (current == null)
		{
			return false;
		}
		String rootId = getRootSessionId(current);
		Session mother = sessions.get(rootId);
		if (mother == null)
		{
			return false;
		}
		List<Session> oldChildren = getChildSessionsForRoot(rootId);
		List<Session> rebuiltChildren = rebuildChildrenForThread(mother, oldChildren, orderedEvents);
		if (rebuiltChildren.isEmpty() && !oldChildren.isEmpty())
		{
			return false;
		}

		for (Session child : oldChildren)
		{
			sessions.remove(child.getId());
		}
		for (Session child : rebuiltChildren)
		{
			sessions.put(child.getId(), child);
		}

		if (!historyLoaded && !rebuiltChildren.isEmpty())
		{
			currentSessionId = rebuiltChildren.get(rebuiltChildren.size() - 1).getId();
		}
		else if (historyLoaded && currentSessionId != null && !sessions.containsKey(currentSessionId))
		{
			currentSessionId = rootId;
		}
		motherEventsCache.remove(rootId);
		return true;
	}

	private List<Session> rebuildChildrenForThread(Session mother, List<Session> oldChildren, List<SplitEvent> orderedEvents)
	{
		List<Session> rebuilt = new ArrayList<>();
		LinkedHashSet<String> roster = initialRosterForTimeline(oldChildren, orderedEvents);
		List<SplitEvent> segmentEvents = new ArrayList<>();
		List<LinkedHashSet<String>> segmentRosters = new ArrayList<>();
		List<List<SplitEvent>> segmentEventGroups = new ArrayList<>();
		List<Instant> segmentStarts = new ArrayList<>();
		Instant currentStart = oldChildren.isEmpty() ? mother.getStart() : oldChildren.get(0).getStart();

		for (SplitEvent event : orderedEvents)
		{
			if (event == null)
			{
				continue;
			}
			if (event.isRosterEvent())
			{
				if (hasLootEvent(segmentEvents))
				{
					segmentRosters.add(new LinkedHashSet<>(roster));
					segmentEventGroups.add(new ArrayList<>(segmentEvents));
					segmentStarts.add(currentStart);
					segmentEvents.clear();
					currentStart = event.getAt() == null ? Instant.now() : event.getAt();
				}
				applyRosterEvent(roster, event);
				segmentEvents.add(event);
				continue;
			}

			if (event.isLootEvent() && event.getPlayer() != null && !containsPlayer(roster, event.getPlayer()))
			{
				addPlayerName(roster, event.getPlayer());
			}
			segmentEvents.add(event);
		}

		if (!segmentEvents.isEmpty() || !roster.isEmpty())
		{
			segmentRosters.add(new LinkedHashSet<>(roster));
			segmentEventGroups.add(new ArrayList<>(segmentEvents));
			segmentStarts.add(currentStart);
		}

		if (segmentEventGroups.isEmpty())
		{
			Session empty = new Session(childIdAt(oldChildren, 0), mother.getStart(), mother.getId());
			empty.getPlayers().addAll(roster);
			empty.setEnd(mother.isActive() ? null : mother.getEnd());
			rebuilt.add(empty);
			return rebuilt;
		}

		for (int i = 0; i < segmentEventGroups.size(); i++)
		{
			String childId = childIdAt(oldChildren, i);
			Session child = new Session(childId, segmentStarts.get(i), mother.getId());
			child.setPluginVersion(Session.CURRENT_PLUGIN_VERSION);
			child.getPlayers().addAll(segmentRosters.get(i));
			for (SplitEvent event : segmentEventGroups.get(i))
			{
				child.getEvents().add(copyEventForSession(event, childId));
			}
			if (i + 1 < segmentStarts.size())
			{
				child.setEnd(segmentStarts.get(i + 1));
			}
			else if (!mother.isActive())
			{
				child.setEnd(mother.getEnd());
			}
			rebuilt.add(child);
		}
		return rebuilt;
	}

	private String childIdAt(List<Session> oldChildren, int index)
	{
		if (index >= 0 && index < oldChildren.size())
		{
			return oldChildren.get(index).getId();
		}
		return newId();
	}

	private boolean hasLootEvent(List<SplitEvent> events)
	{
		for (SplitEvent event : events)
		{
			if (event != null && event.isLootEvent())
			{
				return true;
			}
		}
		return false;
	}

	private LinkedHashSet<String> initialRosterForTimeline(List<Session> oldChildren, List<SplitEvent> orderedEvents)
	{
		LinkedHashSet<String> roster = new LinkedHashSet<>();
		if (oldChildren.isEmpty())
		{
			return roster;
		}
		Set<String> playersWithJoin = orderedEvents.stream()
			.filter(event -> event != null && SplitEvent.TYPE_JOINED.equalsIgnoreCase(event.getType()) && event.getPlayer() != null)
			.map(event -> event.getPlayer().toLowerCase(Locale.ENGLISH))
			.collect(Collectors.toSet());
		for (String player : oldChildren.get(0).getPlayers())
		{
			if (player != null && !playersWithJoin.contains(player.toLowerCase(Locale.ENGLISH)))
			{
				addPlayerName(roster, player);
			}
		}
		return roster;
	}

	private List<Session> getChildSessionsForRoot(String rootId)
	{
		List<Session> children = new ArrayList<>();
		for (Session session : sessions.values())
		{
			if (session != null && rootId.equals(session.getMotherId()))
			{
				children.add(session);
			}
		}
		return children;
	}

	private TimelineValidation validateTimeline(List<SplitEvent> orderedEvents, Session selected)
	{
		if (selected == null)
		{
			return TimelineValidation.valid();
		}
		LinkedHashSet<String> activePlayers = lowerCaseRoster(initialRosterForTimeline(
			getChildSessionsForRoot(getRootSessionId(selected)),
			orderedEvents));
		for (SplitEvent event : orderedEvents)
		{
			if (event == null || event.getPlayer() == null)
			{
				continue;
			}
			String player = event.getPlayer().toLowerCase(Locale.ENGLISH);
			if (SplitEvent.TYPE_JOINED.equalsIgnoreCase(event.getType()))
			{
				if (activePlayers.contains(player))
				{
					return TimelineValidation.invalid("Cannot move a join event into a period where that player is already active.");
				}
				activePlayers.add(player);
			}
			else if (SplitEvent.TYPE_LEFT.equalsIgnoreCase(event.getType()))
			{
				if (!activePlayers.contains(player))
				{
					return TimelineValidation.invalid("Cannot move a left event before that player joined.");
				}
				activePlayers.remove(player);
			}
			else if (event.isLootEvent() && !activePlayers.contains(player))
			{
				return TimelineValidation.invalid("Cannot move loot outside that player's time in the session.");
			}
		}
		return TimelineValidation.valid();
	}

	private LinkedHashSet<String> lowerCaseRoster(Set<String> roster)
	{
		LinkedHashSet<String> lower = new LinkedHashSet<>();
		for (String player : roster)
		{
			if (player != null)
			{
				lower.add(player.toLowerCase(Locale.ENGLISH));
			}
		}
		return lower;
	}

	private boolean containsPlayer(Set<String> roster, String player)
	{
		for (String existing : roster)
		{
			if (existing != null && existing.equalsIgnoreCase(player))
			{
				return true;
			}
		}
		return false;
	}

	private static final class TimelineValidation
	{
		private final boolean valid;
		private final String message;

		private TimelineValidation(boolean valid, String message)
		{
			this.valid = valid;
			this.message = message;
		}

		private static TimelineValidation valid()
		{
			return new TimelineValidation(true, null);
		}

		private static TimelineValidation invalid(String message)
		{
			return new TimelineValidation(false, message);
		}
	}

	private String resolveMainName(String player)
	{
		if (player == null)
		{
			return null;
		}
		String trimmed = player.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		if (historyLoaded)
		{
			return resolveHistoricalMainName(trimmed);
		}
		return playerManager.getMainName(trimmed);
	}

	private String resolveHistoricalMainName(String player)
	{
		Map<String, String> altSnapshot = getCurrentThreadAltMappingSnapshot();
		if (altSnapshot == null)
		{
			return player;
		}
		return resolveNameWithMapping(player, altSnapshot);
	}

	private String resolveNameWithMapping(String player, Map<String, String> altMapping)
	{
		String resolved = player;
		String visited = null;
		for (int i = 0; i < 5; i++)
		{
			String mapped = getMappedMain(resolved, altMapping);
			if (mapped == null || mapped.equalsIgnoreCase(resolved))
			{
				return resolved;
			}
			if (visited != null && visited.equalsIgnoreCase(mapped))
			{
				break;
			}
			visited = resolved;
			resolved = mapped;
		}
		return resolved;
	}

	private String getMappedMain(String alt, Map<String, String> altMapping)
	{
		if (alt == null || altMapping == null)
		{
			return null;
		}
		for (Map.Entry<String, String> entry : altMapping.entrySet())
		{
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(alt))
			{
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Loads persisted session data into the application's runtime structures.
	 * <p>
	 * This method performs the following operations:
	 * <p>
	 * 1. Clears the session map and populates it with sessions retrieved from a
	 * versioned JSON document. Each session is parsed and added to
	 * the map by its ID.
	 * <p>
	 * 2. Updates the current session ID and clears it when the persisted id
	 * does not point to a loaded session.
	 */
	public void loadFromConfig()
	{
		clearHistoryEditUndo();
		SessionStorageData data = loadPersistedData();
		boolean schemaUpgrade = !sessionStorage.isLegacyConfigStore()
			&& sessionStorage.exists()
			&& data.getSchemaVersion() < SessionStorageData.CURRENT_SCHEMA_VERSION;
		if (schemaUpgrade)
		{
			sessionStorage.backupPrimaryIfExists("schema-" + data.getSchemaVersion());
		}
		replaceSessionsFromStorageData(data);
		if (schemaUpgrade)
		{
			saveToConfig();
		}
		sessionStorage.archivePrimaryIfNeeded().ifPresent(this::replaceSessionsFromStorageData);
	}

	private void replaceSessionsFromStorageData(SessionStorageData data)
	{
		sessions.clear();
		List<Session> loadedSessions = !data.getThreads().isEmpty()
			? SplitThreadAdapter.toSessions(data.getThreads())
			: data.getSessions();
		for (Session s : loadedSessions)
		{
			if (s != null && s.getId() != null)
			{
				sessions.put(s.getId(), s);
			}
		}

		// Invalidate any cached mother->events when loading fresh data
		motherEventsCache.clear();

		currentSessionId = emptyToNull(data.getCurrentSessionId());
		if (currentSessionId != null && !sessions.containsKey(currentSessionId))
		{
			log.warn("Persisted current session id {} was not found in persisted sessions", currentSessionId);
			currentSessionId = null;
		}
		Session current = getCurrentSession().orElse(null);
		historyLoaded = current != null && !current.isActive() && data.isHistoryLoaded();
	}

	/**
	 * Persist sessions and current view state to the plugin's versioned JSON store.
	 */
	public void saveToConfig()
	{
		SessionStorageData data = buildStorageData();
		if (!sessionStorage.save(data))
		{
			log.warn("Failed to save sessions to {}", sessionStorage.describeLocation());
		}
	}

	public boolean hasArchivedSessionFiles()
	{
		return sessionStorage.hasArchives();
	}

	private SessionStorageData loadPersistedData()
	{
		if (sessionStorage.exists())
		{
			return sessionStorage.load();
		}

		if (sessionStorage.isLegacyConfigStore() || !sessionStorage.hasLegacyData(config))
		{
			return sessionStorage.load();
		}
		if (!sessionStorage.canMigrateLegacy(config))
		{
			return sessionStorage.load();
		}

		SessionStorageData legacyData = sessionStorage.loadLegacy(config);
		if (sessionStorage.save(legacyData))
		{
			sessionStorage.clearLegacySessionConfig(config);
		}
		else
		{
			log.warn("Legacy session config was not cleared because migration to {} failed", sessionStorage.describeLocation());
		}
		return legacyData;
	}

	private SessionStorageData buildStorageData()
	{
		SessionStorageData data = new SessionStorageData();
		data.setSessions(new ArrayList<>(sessions.values()));
		data.setThreads(SplitThreadAdapter.fromSessions(new ArrayList<>(sessions.values())));
		data.setCurrentSessionId(currentSessionId);
		data.setHistoryLoaded(historyLoaded);
		return data;
	}

	/**
	 * Export all sessions as JSON for sharing or backups.
	 */
	public String exportAllSessionsJson()
	{
		return gson.toJson(sessions.values());
	}

	/**
	 * Export all completed history threads as JSON.
	 */
	public String exportHistorySessionsJson()
	{
		Set<String> historyRootIds = getHistorySessionsNewestFirst().stream()
			.map(Session::getId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		List<Session> historySessions = sessions.values().stream()
			.filter(session -> historyRootIds.contains(getRootSessionId(session)))
			.collect(Collectors.toList());
		return gson.toJson(historySessions);
	}

	/**
	 * Export a single session by id as JSON.
	 *
	 * @return JSON for the specified session, or empty string when not found
	 */
	public String exportSessionJson(String sessionId)
	{
		Session session = sessions.get(sessionId);
		return session == null ? "" : gson.toJson(session);
	}

	/**
	 * Export the selected history thread as JSON, including root and child segments.
	 *
	 * @return JSON for the selected session thread, or empty string when not found
	 */
	public String exportSessionThreadJson(String sessionId)
	{
		Session selected = sessions.get(sessionId);
		if (selected == null)
		{
			return "";
		}
		String rootId = getRootSessionId(selected);
		List<Session> threadSessions = sessions.values().stream()
			.filter(session -> rootId.equals(getRootSessionId(session)))
			.collect(Collectors.toList());
		return gson.toJson(threadSessions);
	}

	/**
	 * Import one or more completed history threads from JSON.
	 * The payload must contain at least one closed mother session and all children must reference
	 * a root session present in the same payload. Imported sessions are remapped to fresh ids.
	 *
	 * @param json JSON payload containing an array of completed sessions
	 * @return number of imported mother sessions, or 0 when the payload is invalid
	 */
	public int importHistorySessionsJson(String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return 0;
		}

		try
		{
			String trimmed = json.trim();
			Session[] arr;
			if (trimmed.startsWith("{"))
			{
				SessionStorageData data = gson.fromJson(trimmed, SessionStorageData.class);
				List<Session> importedSessions = data != null && !data.getThreads().isEmpty()
					? SplitThreadAdapter.toSessions(data.getThreads())
					: (data == null ? Collections.emptyList() : data.getSessions());
				arr = importedSessions.toArray(new Session[0]);
			}
			else
			{
				arr = gson.fromJson(trimmed, Session[].class);
			}
			if (arr == null || arr.length == 0)
			{
				return 0;
			}

			LinkedHashMap<String, Session> importedById = new LinkedHashMap<>();
			for (Session session : arr)
			{
				if (!isImportableHistorySession(session))
				{
					return 0;
				}
				if (importedById.put(session.getId(), session) != null)
				{
					return 0;
				}
			}

			List<Session> roots = importedById.values().stream()
				.filter(session -> session.getMotherId() == null)
				.collect(Collectors.toList());
			if (roots.isEmpty())
			{
				return 0;
			}

			for (Session session : importedById.values())
			{
				if (session.getMotherId() == null)
				{
					continue;
				}
				Session mother = importedById.get(session.getMotherId());
				if (mother == null || mother.getMotherId() != null)
				{
					return 0;
				}
			}

			List<Session> importedSessions = new ArrayList<>();
			for (Session root : roots)
			{
				String rootId = root.getId();
				String newRootId = newId();
				importedSessions.add(copySessionForImport(root, newRootId, null));

				List<Session> children = importedById.values().stream()
					.filter(session -> rootId.equals(session.getMotherId()))
					.sorted(Comparator.comparing(Session::getStart))
					.collect(Collectors.toList());
				for (Session child : children)
				{
					importedSessions.add(copySessionForImport(child, newId(), newRootId));
				}
			}

			for (Session session : importedSessions)
			{
				sessions.put(session.getId(), session);
			}
			motherEventsCache.clear();
			clearHistoryEditUndo();
			saveToConfig();
			return roots.size();
		}
		catch (Exception e)
		{
			log.warn("Failed to import history sessions from JSON", e);
			return 0;
		}
	}

	private String getRootSessionId(Session session)
	{
		return session.getMotherId() == null ? session.getId() : session.getMotherId();
	}

	private boolean isImportableHistorySession(Session session)
	{
		if (session == null || session.getId() == null || session.getStart() == null)
		{
			return false;
		}
		if (session.isActive())
		{
			return false;
		}
		return session.getMotherId() == null || !session.getMotherId().trim().isEmpty();
	}

	private Session copySessionForImport(Session source, String id, String motherId)
	{
		Session copy = new Session(id, source.getStart(), motherId);
		copy.setEnd(source.getEnd());
		copy.setPluginVersion(source.getPluginVersion());
		copy.setSettlementConfigAtStart(source.getSettlementConfigAtStart());
		copy.setSettlementConfigAtEnd(source.getSettlementConfigAtEnd());
		copy.setAltMappingAtStart(source.getAltMappingAtStart());
		copy.setAltMappingAtEnd(source.getAltMappingAtEnd());
		if (source.getPlayers() != null)
		{
			copy.getPlayers().addAll(source.getPlayers());
		}
		if (source.getEvents() != null)
		{
			for (SplitEvent event : source.getEvents())
			{
				if (event == null)
				{
					continue;
				}
				copy.getEvents().add(copyEventForSession(event, id));
			}
		}
		return copy;
	}

	/**
	 * @return unmodifiable set of all known player names (mains and alts).
	 */
	public Set<String> getKnownPlayers()
	{
		Set<String> mains = playerManager.getKnownMains();
		if (mains == null)
		{
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(mains);
	}

	/**
	 * Compute known main players not currently active in the session roster.
	 *
	 * @return set of eligible names to add to the current session
	 */
	public Set<String> getNonActivePlayers()
	{
		Session curr = getCurrentEditableSession().orElse(null);
		Set<String> mains = playerManager.getKnownMains();
		if (mains == null)
		{
			return Collections.emptySet();
		}

		if (curr == null || (!historyLoaded && !curr.isActive()))
		{
			return Collections.unmodifiableSet(mains);
		}

		Set<String> nonActivePlayers = mains.stream()
			.filter(p -> curr.getPlayers().stream().noneMatch(active -> active.equalsIgnoreCase(p)))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return Collections.unmodifiableSet(nonActivePlayers);
	}

	/**
	 * @return Optional of the currently active child session, if any. Empty if no session is active.
	 */
	public Optional<Session> getCurrentSession()
	{
		return Optional.ofNullable(currentSessionId).map(sessions::get);
	}

	/**
	 * @return all sessions (mother and children) sorted by start time descending (newest first).
	 */
	public List<Session> getAllSessionsNewestFirst()
	{
		return sessions.values().stream()
			.sorted(Comparator.comparing(Session::getStart).reversed())
			.collect(Collectors.toList());
	}

	/**
	 * @return completed root sessions sorted by start time descending for the history picker.
	 */
	public List<Session> getHistorySessionsNewestFirst()
	{
		return sessions.values().stream()
			.filter(session -> session.getMotherId() == null)
			.filter(session -> !session.isActive())
			.sorted(Comparator.comparing(Session::getStart).reversed())
			.collect(Collectors.toList());
	}

	/**
	 * Exit history mode and return to live mode.
	 * Persists the flag immediately.
	 */
	public void unloadHistory()
	{
		clearHistoryEditUndo();
		if (historyLoaded)
		{
			currentSessionId = null;
		}
		if (historyDirty)
		{
			restoreOriginalHistorySessions();
		}
		historyLoaded = false;
		historyDirty = false;
		historyEditWarningAccepted = false;
		historyOriginalSessionsJson = null;
		saveToConfig();
	}

	/**
	 * Enter history mode by selecting a completed session to view or edit.
	 * Requires that no active session is running. Persists the flag immediately.
	 *
	 * @param sessionId id of the session (mother or child) to load
	 * @return the loaded session if found and preconditions met; empty otherwise
	 */
	public Optional<Session> loadHistory(String sessionId)
	{
		clearHistoryEditUndo();
		if (hasActiveSession())
		{
			return Optional.empty(); // must stop active first
		}
		Session s = sessions.get(sessionId);
		if (s == null || s.isActive())
		{
			return Optional.empty();
		}
		historyOriginalSessionsJson = gson.toJson(sessions.values());
		historyDirty = false;
		historyEditWarningAccepted = false;
		currentSessionId = s.getId();
		historyLoaded = true;
		saveToConfig();
		return Optional.of(s);
	}

	public boolean saveHistoryChanges()
	{
		if (!historyLoaded)
		{
			return false;
		}
		clearHistoryEditUndo();
		historyDirty = false;
		historyEditWarningAccepted = false;
		historyOriginalSessionsJson = gson.toJson(sessions.values());
		saveToConfig();
		return true;
	}

	public boolean discardHistoryChanges()
	{
		if (!historyLoaded)
		{
			return false;
		}
		clearHistoryEditUndo();
		String selectedSessionId = currentSessionId;
		if (historyDirty)
		{
			restoreOriginalHistorySessions();
		}
		currentSessionId = selectedSessionId;
		historyLoaded = true;
		historyDirty = false;
		historyEditWarningAccepted = false;
		historyOriginalSessionsJson = gson.toJson(sessions.values());
		saveToConfig();
		return true;
	}

	private void restoreOriginalHistorySessions()
	{
		if (historyOriginalSessionsJson == null || historyOriginalSessionsJson.trim().isEmpty())
		{
			loadFromConfig();
			return;
		}
		try
		{
			Session[] arr = gson.fromJson(historyOriginalSessionsJson, Session[].class);
			sessions.clear();
			if (arr != null)
			{
				for (Session session : arr)
				{
					if (session != null && session.getId() != null)
					{
						sessions.put(session.getId(), session);
					}
				}
			}
			motherEventsCache.clear();
		}
		catch (Exception e)
		{
			log.warn("Failed to restore unsaved history edits; reloading persisted sessions", e);
			loadFromConfig();
		}
	}

	/**
	 * @return true if there is a current child session and its end time is null (active).
	 */
	public boolean hasActiveSession()
	{
		return getCurrentSession().map(Session::isActive).orElse(false);
	}

	public Optional<Session> getCurrentEditableSession()
	{
		Session current = getCurrentSession().orElse(null);
		if (current == null)
		{
			return Optional.empty();
		}
		if (!historyLoaded || current.getMotherId() != null)
		{
			return Optional.of(current);
		}
		List<Session> thread = getThreadSessions(current);
		for (int i = thread.size() - 1; i >= 0; i--)
		{
			Session session = thread.get(i);
			if (session != null && session.getMotherId() != null)
			{
				return Optional.of(session);
			}
		}
		return Optional.empty();
	}

	/**
	 * Start a new session thread consisting of a mother session and an initial active child.
	 * Fails if history mode is on or another session is currently active.
	 *
	 * @return the newly created active child session, if started
	 */
	public Optional<Session> startSession()
	{
		clearHistoryEditUndo();
		if (historyLoaded)
		{
			return Optional.empty();
		}
		if (hasActiveSession())
		{
			return Optional.empty();
		}

		// Create mother and an initial child immediately (to mirror sheet)
		Session mother = new Session(newId(), Instant.now(), null);
		mother.setSettlementConfigAtStart(currentSettlementConfigSnapshot());
		mother.setAltMappingAtStart(currentAltMappingSnapshot());
		sessions.put(mother.getId(), mother);
		// initialize empty cache list for this mother thread
		motherEventsCache.put(mother.getId(), new ArrayList<>());

		Session child = new Session(newId(), Instant.now(), mother.getId());
		sessions.put(child.getId(), child);

		currentSessionId = child.getId();
		saveToConfig();
		if (pluginManager != null)
		{
			pluginManager.updateChatWarningStatus();
		}
		return Optional.of(child);
	}

	/**
	 * Stop the currently active child session. If its mother session is still active,
	 * it will be ended as well. No-op in history mode.
	 *
	 * @return true if an active session was stopped
	 */
	public boolean stopSession()
	{
		clearHistoryEditUndo();
		if (historyLoaded)
		{
			return false;
		}

		Session curr = getCurrentSession().orElse(null);
		if (curr == null || !curr.isActive())
		{
			return false;
		}

		curr.setEnd(Instant.now());

		// If child has a mother which is active, end mother too.
		if (curr.getMotherId() != null)
		{
			Session mother = sessions.get(curr.getMotherId());
			if (mother != null && mother.isActive())
			{
				mother.setSettlementConfigAtEnd(currentSettlementConfigSnapshot());
				mother.setAltMappingAtEnd(currentAltMappingSnapshot());
				mother.setEnd(Instant.now());
			}
		}

		currentSessionId = null;
		saveToConfig();
		if (pluginManager != null)
		{
			pluginManager.updateChatWarningStatus();
		}
		return true;
	}

	/**
	 * Add a player to the currently active child session. If the child already has loot events recorded,
	 * a new child session is forked (same mother), roster is copied, the player is added, and the
	 * previous child is ended to preserve historical rosters per split segment.
	 * Alt names are resolved to main before checks. No-op in history mode.
	 *
	 * @param player display name (main or alt)
	 * @return true if the roster changed (player added)
	 */
	public boolean addPlayerToActive(String player)
	{
		Session curr = getCurrentEditableSession().orElse(null);
		if (curr == null || (!historyLoaded && !curr.isActive()))
		{
			return false;
		}

		String mainPlayer = resolveMainName(player);
		if (mainPlayer == null || mainPlayer.isBlank())
		{
			return false;
		}
		final String fMain = mainPlayer;
		if (curr.getPlayers().stream().anyMatch(p -> p.equalsIgnoreCase(fMain)))
		{
			// Player (main) already in session
			return false;
		}
		if (!prepareHistoryMutation())
		{
			return false;
		}

		if (historyLoaded)
		{
			SplitEvent joinEvent = new SplitEvent(curr.getId(), fMain, 0L, Instant.now());
			joinEvent.setType(SplitEvent.TYPE_JOINED);
			List<SplitEvent> events = new ArrayList<>(getAllEvents());
			events.add(joinEvent);
			TimelineValidation validation = validateTimeline(events, curr);
			if (!validation.valid)
			{
				lastMoveEventFailureMessage = validation.message;
				return false;
			}
			if (rebuildCurrentThreadFromEvents(events))
			{
				saveAfterMutation();
				return true;
			}
			return false;
		}

		if (curr.hasLootEvents())
		{
			// Create a new child session, copy players, add this player, end current child
			String motherId = curr.getMotherId() == null ? curr.getId() : curr.getMotherId();
			Session newChild = new Session(newId(), Instant.now(), motherId);
			// copy players
			newChild.getPlayers().addAll(curr.getPlayers());
			// add the new player (main)
			newChild.getPlayers().add(fMain);

			// End current child (but keep events)
			curr.setEnd(Instant.now());

			// Record a JOINED event in the new child
			SplitEvent joinEvent = new SplitEvent(newChild.getId(), fMain, 0L, Instant.now());
			joinEvent.setType(SplitEvent.TYPE_JOINED);
			newChild.getEvents().add(joinEvent);

			// Update mother cache incrementally
			motherEventsCache.computeIfAbsent(motherId, k -> new ArrayList<>()).add(joinEvent);

			// Activate new child
			sessions.put(newChild.getId(), newChild);
			currentSessionId = newChild.getId();
		}
		else
		{
			curr.getPlayers().add(fMain);
			// Record a JOINED event in the current child (no loot events yet)
			SplitEvent joinEvent = new SplitEvent(curr.getId(), fMain, 0L, Instant.now());
			joinEvent.setType(SplitEvent.TYPE_JOINED);
			curr.getEvents().add(joinEvent);

			// Update mother cache incrementally
			String motherId = curr.getMotherId() == null ? curr.getId() : curr.getMotherId();
			motherEventsCache.computeIfAbsent(motherId, k -> new ArrayList<>()).add(joinEvent);
		}
		saveAfterMutation();
		return true;
	}

	/**
	 * Remove a player from the active child session. If the current child already has loot events,
	 * a new child is created (same mother) without this player, and the current child is ended
	 * to keep per-segment rosters intact. No-op in history mode.
	 *
	 * @param player display name (main or alt)
	 * @return true if the roster changed (player removed)
	 */
	public boolean removePlayerFromSession(String player)
	{
		Session curr = getCurrentEditableSession().orElse(null);
		if (curr == null || (!historyLoaded && !curr.isActive()))
		{
			return false;
		}

		if (player == null)
		{
			return false;
		}
		player = resolveMainName(player);
		if (player == null || player.isBlank())
		{
			return false;
		}
		String resolvedPlayer = player;
		if (curr.getPlayers().stream().noneMatch(p -> p.equalsIgnoreCase(resolvedPlayer)))
		{
			return false;
		}
		if (!prepareHistoryMutation())
		{
			return false;
		}

		if (historyLoaded)
		{
			SplitEvent leaveEvent = new SplitEvent(curr.getId(), player, 0L, Instant.now());
			leaveEvent.setType(SplitEvent.TYPE_LEFT);
			List<SplitEvent> events = new ArrayList<>(getAllEvents());
			events.add(leaveEvent);
			TimelineValidation validation = validateTimeline(events, curr);
			if (!validation.valid)
			{
				lastMoveEventFailureMessage = validation.message;
				return false;
			}
			if (rebuildCurrentThreadFromEvents(events))
			{
				saveAfterMutation();
				return true;
			}
			return false;
		}

		if (curr.hasLootEvents())
		{
			// Create a new child without this player, end current child
			String motherId = curr.getMotherId() == null ? curr.getId() : curr.getMotherId();
			Session newChild = new Session(newId(), Instant.now(), motherId);
			String finalPlayer = player;
			newChild.getPlayers().addAll(
				curr.getPlayers().stream().filter(p -> !p.equalsIgnoreCase(finalPlayer)).collect(Collectors.toList())
			);

			// End the current child
			curr.setEnd(Instant.now());

			// Record a LEFT event in the new child
			SplitEvent leaveEvent = new SplitEvent(newChild.getId(), finalPlayer, 0L, Instant.now());
			leaveEvent.setType(SplitEvent.TYPE_LEFT);
			newChild.getEvents().add(leaveEvent);

			// Update mother cache incrementally
			motherEventsCache.computeIfAbsent(motherId, k -> new ArrayList<>()).add(leaveEvent);

			sessions.put(newChild.getId(), newChild);
			currentSessionId = newChild.getId();
		}
		else
		{
			String finalPlayer = player;
			curr.getPlayers().removeIf(p -> p.equalsIgnoreCase(finalPlayer));
			// Record a LEFT event in the current child (no loot events yet)
			SplitEvent leaveEvent = new SplitEvent(curr.getId(), player, 0L, Instant.now());
			leaveEvent.setType(SplitEvent.TYPE_LEFT);
			curr.getEvents().add(leaveEvent);

			// Update mother cache incrementally
			String motherId = curr.getMotherId() == null ? curr.getId() : curr.getMotherId();
			motherEventsCache.computeIfAbsent(motherId, k -> new ArrayList<>()).add(leaveEvent);
		}
		saveAfterMutation();
		return true;
	}

	/**
	 * Record a loot value for a player in the active session. The player is resolved to its main
	 * and must be on the active roster. No-op in history mode.
	 *
	 * @param player display name (main or alt)
	 * @param amount value in coins (may be negative if allowed by config)
	 * @return true if recorded
	 */
	public boolean addLoot(String player, Long amount)
	{
		Session currentSession = getCurrentEditableSession().orElse(null);
		if (currentSession == null || (!historyLoaded && !currentSession.isActive()))
		{
			return false;
		}
		if (amount == null)
		{
			return false;
		}

		String mainPlayer = resolveMainName(player);
		if (mainPlayer == null || mainPlayer.isBlank())
		{
			return false;
		}
		if (currentSession.getPlayers().stream().noneMatch(p -> p.equalsIgnoreCase(mainPlayer)))
		{
			return false;
		}
		if (!prepareHistoryMutation())
		{
			return false;
		}

		SplitEvent newLoot = new SplitEvent(currentSession.getId(), mainPlayer, amount, Instant.now());
		currentSession.getEvents().add(newLoot);

		// Update mother cache incrementally
		String motherId = currentSession.getMotherId() == null ? currentSession.getId() : currentSession.getMotherId();
		motherEventsCache.computeIfAbsent(motherId, k -> new ArrayList<>()).add(newLoot);

		saveAfterMutation();
		return true;
	}

	/**
	 * Read-only view of the queued pending values detected from chat.
	 */
	public List<PendingValue> getPendingValues()
	{
		return Collections.unmodifiableList(pendingValues);
	}

	/**
	 * Queue a new pending value. The suggested player is normalized to its main. If configured,
	 * the value may be auto-applied (when the player is currently in session), in which case this
	 * method records a loot and does not queue. A small cap prevents unbounded growth.
	 *
	 * @param pendingValue pending value payload; null is ignored
	 */
	public void addPendingValue(PendingValue pendingValue)
	{
		if (pendingValue == null || pendingValue.getValue() == null)
		{
			return;
		}

		// Normalize suggestedPlayer player to main for all downstream uses
		String suggestedPlayer = pendingValue.getSuggestedPlayer();
		String resolvedPlayer = resolveMainName(suggestedPlayer);

		pendingValue.setSuggestedPlayer(resolvedPlayer);

		if (resolvedPlayer != null && !resolvedPlayer.isBlank() && !playerManager.isKnownPlayer(resolvedPlayer))
		{
			playerManager.addKnownPlayer(resolvedPlayer);
		}

		// Auto-apply if configured and player already in session
		if (resolvedPlayer != null && config.autoApplyWhenInSession() && hasActiveSession())
		{
			Session currentSession = getCurrentSession().orElse(null);
			if (currentSession != null && currentSession.getPlayers().stream().anyMatch(p -> p.equalsIgnoreCase(resolvedPlayer)))
			{
				addLoot(resolvedPlayer, pendingValue.getValue());
				return; // do not queue
			}
		}

		// Limit size to avoid unbounded growth
		if (pendingValues.size() >= 100)
		{
			pendingValues.remove(0);
		}
		pendingValues.add(pendingValue);
	}

	/**
	 * Remove a pending value by its id.
	 *
	 * @param id unique pending id
	 * @return true if removed
	 */
	public boolean removePendingValueById(String id)
	{
		return pendingValues.removeIf(p -> p.getId().equals(id));
	}

	/**
	 * Apply a pending value to a specific player and remove it from the queue.
	 * The player is resolved to its main; the underlying addLoot() enforces roster rules.
	 *
	 * @param id     pending id
	 * @param player target player (main or alt)
	 * @return true if applied
	 */
	public boolean applyPendingValueToPlayer(String id, String player)
	{
		PendingValue pv = pendingValues.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
		if (pv == null)
		{
			return false;
		}
		String target = resolveMainName(player);
		boolean ok = addLoot(target, pv.getValue());
		if (ok)
		{
			pendingValues.remove(pv);
		}
		return ok;
	}

	/**
	 * Returns true if the given player (main or alt) is present in the roster of the current session.
	 * Alts are resolved to their main before the check.
	 */
	public boolean currentSessionHasPlayer(String player)
	{
		return sessionHasPlayer(player, getCurrentSession().orElse(null));
	}

	/**
	 * Returns true if the given player (main or alt) is present in the roster of the provided session.
	 * Alts are resolved to their main before the check.
	 */
	public boolean sessionHasPlayer(String player, Session session)
	{
		if (session == null)
		{
			return false;
		}

		String mainPlayer = resolveMainName(player);
		if (mainPlayer == null)
		{
			return false;
		}

		return session.getPlayers().stream().anyMatch(e ->
			e.equalsIgnoreCase(mainPlayer));
	}

	public boolean currentThreadContainsPlayerName(String player)
	{
		if (player == null || player.trim().isEmpty())
		{
			return false;
		}
		Session current = getCurrentSession().orElse(null);
		if (current == null)
		{
			return false;
		}
		String target = player.trim();
		for (Session session : getThreadSessions(current))
		{
			if (session == null || session.getMotherId() == null)
			{
				continue;
			}
			if (session.getPlayers().stream().anyMatch(existing -> existing.equalsIgnoreCase(target)))
			{
				return true;
			}
			for (SplitEvent event : session.getEvents())
			{
				if (event != null && event.getPlayer() != null && event.getPlayer().equalsIgnoreCase(target))
				{
					return true;
				}
			}
		}
		return false;
	}

	public void init()
	{
		loadFromConfig();
	}

	public boolean prepareHistoryMutation()
	{
		if (!historyLoaded)
		{
			return true;
		}
		if (isCurrentHistoryEditLocked())
		{
			return false;
		}
		if (!historyEditWarningAccepted)
		{
			boolean accepted = historyEditWarningHandler == null || historyEditWarningHandler.getAsBoolean();
			if (!accepted)
			{
				return false;
			}
			historyEditWarningAccepted = true;
		}
		historyDirty = true;
		return true;
	}

	public boolean isCurrentHistoryEditLocked()
	{
		if (!historyLoaded)
		{
			return false;
		}
		Session current = getCurrentSession().orElse(null);
		return isHistoryEditLocked(current);
	}

	private boolean isHistoryEditLocked(Session session)
	{
		if (session == null)
		{
			return true;
		}
		for (Session threadSession : getThreadSessions(session))
		{
			if (isVersionBefore(threadSession.getPluginVersion(), MIN_HISTORY_EDIT_VERSION))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isVersionBefore(String version, String minimumVersion)
	{
		if (version == null || version.trim().isEmpty())
		{
			return true;
		}
		int[] parsedVersion = parseVersion(version);
		int[] parsedMinimum = parseVersion(minimumVersion);
		for (int i = 0; i < parsedMinimum.length; i++)
		{
			if (parsedVersion[i] != parsedMinimum[i])
			{
				return parsedVersion[i] < parsedMinimum[i];
			}
		}
		return false;
	}

	private static int[] parseVersion(String version)
	{
		int[] parts = new int[]{0, 0, 0};
		String[] tokens = version.split("\\.");
		for (int i = 0; i < parts.length && i < tokens.length; i++)
		{
			try
			{
				parts[i] = Integer.parseInt(tokens[i].replaceAll("[^0-9].*$", ""));
			}
			catch (NumberFormatException e)
			{
				parts[i] = 0;
			}
		}
		return parts;
	}

	public void markHistoryMutation()
	{
		clearHistoryEditUndo();
		markHistoryTableMutation();
	}

	private void markHistoryTableMutation()
	{
		if (historyLoaded)
		{
			historyDirty = true;
			invalidateEventsCache();
			return;
		}
		saveToConfig();
	}

	private void saveAfterMutation()
	{
		clearHistoryEditUndo();
		saveAfterHistoryTableMutation();
	}

	private void saveAfterHistoryTableMutation()
	{
		if (historyLoaded)
		{
			historyDirty = true;
			return;
		}
		saveToConfig();
	}

	/**
	 * Compute metrics for the given session's thread (mother + children) including only currently active players.
	 *
	 * @param s a session within the thread to compute against
	 * @return list of PlayerMetrics rows (non-zero totals only)
	 */
	public List<PlayerMetrics> computeMetricsFor(Session s)
	{
		return computeMetricsFor(s, false);
	}

	/**
	 * Compute metrics for the given session's thread (mother + children).
	 * When includeNonActivePlayers is true, any player appearing in the thread or known list may be included.
	 * Otherwise, only players on the provided session's current roster are considered for output.
	 * Players with zero total and zero split are omitted.
	 *
	 * @param s                       a session within the thread to compute against
	 * @param includeNonActivePlayers whether to include players outside the current roster
	 * @return list of PlayerMetrics rows
	 */
	public List<PlayerMetrics> computeMetricsFor(Session s, boolean includeNonActivePlayers)
	{
		if (s == null)
		{
			return List.of();
		}

		return splitCalculator.compute(
			s,
			getThreadSessions(s),
			playerManager.getKnownPlayers(),
			includeNonActivePlayers,
			buildGeTaxSettings(s));
	}

	public List<PlayerMetrics> computeMetricsFor(Session s,
	                                             boolean includeNonActivePlayers,
	                                             SettlementConfigSnapshot overrideSnapshot)
	{
		if (s == null)
		{
			return List.of();
		}

		return splitCalculator.compute(
			s,
			getThreadSessions(s),
			playerManager.getKnownPlayers(),
			includeNonActivePlayers,
			buildGeTaxSettings(overrideSnapshot));
	}

	private SplitCalculator.GeTaxSettings buildGeTaxSettings(Session session)
	{
		SettlementConfigSnapshot snapshot = getHistoricalSettlementConfigSnapshot(session);
		if (snapshot != null)
		{
			return buildGeTaxSettings(snapshot);
		}
		return buildGeTaxSettings(currentSettlementConfigSnapshot());
	}

	private SplitCalculator.GeTaxSettings buildGeTaxSettings(SettlementConfigSnapshot snapshot)
	{
		if (snapshot == null || !snapshot.isAccountForGeTax())
		{
			return SplitCalculator.GeTaxSettings.disabled();
		}

		return new SplitCalculator.GeTaxSettings(
			true,
			parseGeTaxMinimumValue(snapshot.getGeTaxMinimumValue()),
			sanitizeGeTaxPercent(snapshot.getGeTaxPercent()),
			parseGeTaxMaxPerLoot(snapshot.getGeTaxMaxPerLoot()));
	}

	public SplitCalculator.GeTaxSettings getGeTaxSettingsFor(SettlementConfigSnapshot snapshot)
	{
		return buildGeTaxSettings(snapshot);
	}

	public SettlementConfigSnapshot getSettlementConfigSnapshotFor(Session session)
	{
		SettlementConfigSnapshot historicalSnapshot = getHistoricalSettlementConfigSnapshot(session);
		return historicalSnapshot == null ? currentSettlementConfigSnapshot() : historicalSnapshot;
	}

	public boolean updateSettlementConfigSnapshotFor(Session session, SettlementConfigSnapshot snapshot)
	{
		if (session == null || snapshot == null)
		{
			return false;
		}
		Session mother = sessions.get(getRootSessionId(session));
		if (mother == null || mother.isActive())
		{
			return false;
		}
		if (!prepareHistoryMutation())
		{
			return false;
		}
		mother.setSettlementConfigAtEnd(snapshot);
		if (mother.getSettlementConfigAtStart() == null)
		{
			mother.setSettlementConfigAtStart(snapshot);
		}
		saveAfterMutation();
		return true;
	}

	private SettlementConfigSnapshot getHistoricalSettlementConfigSnapshot(Session session)
	{
		if (session == null)
		{
			return null;
		}
		if (session.isActive())
		{
			return null;
		}
		Session mother = sessions.get(getRootSessionId(session));
		if (mother == null || mother.isActive())
		{
			return null;
		}
		if (mother.getSettlementConfigAtEnd() != null)
		{
			return mother.getSettlementConfigAtEnd();
		}
		return mother.getSettlementConfigAtStart();
	}

	private SettlementConfigSnapshot currentSettlementConfigSnapshot()
	{
		if (config == null)
		{
			return new SettlementConfigSnapshot(false,
				PluginConfig.DEFAULT_GE_TAX_MINIMUM_VALUE,
				PluginConfig.DEFAULT_GE_TAX_PERCENT,
				PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT_VALUE);
		}
		return new SettlementConfigSnapshot(
			config.accountForGeTax(),
			defaultString(config.geTaxMinimumValue(), PluginConfig.DEFAULT_GE_TAX_MINIMUM_VALUE),
			config.geTaxPercent(),
			defaultString(config.geTaxMaxPerLoot(), PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT_VALUE));
	}

	private Map<String, String> currentAltMappingSnapshot()
	{
		Map<String, String> mapping = playerManager == null ? null : playerManager.getAltMainMapping();
		return mapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mapping);
	}

	private Map<String, String> getCurrentThreadAltMappingSnapshot()
	{
		Session current = getCurrentSession().orElse(null);
		if (current == null)
		{
			return null;
		}
		Session mother = sessions.get(getRootSessionId(current));
		if (mother == null)
		{
			return null;
		}
		Map<String, String> endSnapshot = mother.getAltMappingAtEnd();
		if (endSnapshot != null)
		{
			return endSnapshot;
		}
		return mother.getAltMappingAtStart();
	}

	private String defaultString(String value, String defaultValue)
	{
		return value == null || value.trim().isEmpty() ? defaultValue : value;
	}

	private long parseGeTaxMinimumValue(String configuredValue)
	{
		String valueToParse = configuredValue == null || configuredValue.trim().isEmpty()
			? PluginConfig.DEFAULT_GE_TAX_MINIMUM_VALUE
			: configuredValue.trim();
		try
		{
			return Formats.OsrsAmountFormatter.stringAmountToLongAmount(valueToParse, null);
		}
		catch (ParseException e)
		{
			log.warn("Failed to parse GE tax minimum value {}; using default {}", valueToParse, PluginConfig.DEFAULT_GE_TAX_MINIMUM_VALUE, e);
			return defaultGeTaxMinimumValue();
		}
	}

	private long defaultGeTaxMinimumValue()
	{
		try
		{
			return Formats.OsrsAmountFormatter.stringAmountToLongAmount(PluginConfig.DEFAULT_GE_TAX_MINIMUM_VALUE, null);
		}
		catch (ParseException e)
		{
			log.warn("Failed to parse built-in GE tax minimum {}; disabling GE tax threshold", PluginConfig.DEFAULT_GE_TAX_MINIMUM_VALUE, e);
			return 0L;
		}
	}

	private long parseGeTaxMaxPerLoot(String configuredValue)
	{
		String valueToParse = configuredValue == null || configuredValue.trim().isEmpty()
			? PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT_VALUE
			: configuredValue.trim();
		try
		{
			return Formats.OsrsAmountFormatter.stringAmountToLongAmount(valueToParse, null);
		}
		catch (ParseException e)
		{
			log.warn("Failed to parse GE tax max per loot {}; using default {}", valueToParse, PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT_VALUE, e);
			return defaultGeTaxMaxPerLoot();
		}
	}

	private long defaultGeTaxMaxPerLoot()
	{
		try
		{
			return Formats.OsrsAmountFormatter.stringAmountToLongAmount(PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT_VALUE, null);
		}
		catch (ParseException e)
		{
			log.warn("Failed to parse built-in GE tax max per loot {}; using default {}", PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT_VALUE, PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT, e);
			return PluginConfig.DEFAULT_GE_TAX_MAX_PER_LOOT;
		}
	}

	private double sanitizeGeTaxPercent(double configuredPercent)
	{
		if (Double.isNaN(configuredPercent) || Double.isInfinite(configuredPercent) || configuredPercent < 0.0d)
		{
			log.warn("Invalid GE tax percent {}; using default {}", configuredPercent, PluginConfig.DEFAULT_GE_TAX_PERCENT);
			return PluginConfig.DEFAULT_GE_TAX_PERCENT;
		}
		return configuredPercent;
	}

	private List<Session> getThreadSessions(Session s)
	{
		String rootId = (s.getMotherId() == null) ? s.getId() : s.getMotherId();
		List<Session> thread = new ArrayList<>();
		Session mother = sessions.get(rootId);
		if (mother != null)
		{
			thread.add(mother);
		}
		for (Session candidate : sessions.values())
		{
			if (rootId.equals(candidate.getMotherId()))
			{
				thread.add(candidate);
			}
		}
		return thread;
	}


	/**
	 * Get all events from all sessions that share the same mother session as the current session.
	 * Uses a cached list per mother to avoid recomputing on every UI update.
	 *
	 * @return a list containing all event records from sessions with the same mother
	 */
	public List<SplitEvent> getAllEvents()
	{
		Session curr = getCurrentSession().orElse(null);
		if (curr == null)
		{
			return new ArrayList<>();
		}
		// Determine the mother id for this thread
		String motherId = (curr.getMotherId() == null) ? curr.getId() : curr.getMotherId();
		// If cached, return it
		List<SplitEvent> cached = motherEventsCache.get(motherId);
		if (cached != null)
		{
			return Collections.unmodifiableList(cached);
		}
		// Build once in persisted session/list order. The edit-history UI can reorder
		// events, so sorting by timestamp here would discard those edits on refresh.
		List<SplitEvent> built = new ArrayList<>();
		for (Session session : sessions.values())
		{
			if (motherId.equals(session.getId()) || motherId.equals(session.getMotherId()))
			{
				built.addAll(session.getEvents());
			}
		}
		motherEventsCache.put(motherId, built);
		return Collections.unmodifiableList(built);
	}


	/**
	 * Generate a random unique id for sessions.
	 */
	private String newId()
	{
		return UUID.randomUUID().toString();
	}

	private static final class HistoryEditSnapshot
	{
		private final LinkedHashMap<String, Session> sessions;
		private final String currentSessionId;
		private final boolean historyLoaded;
		private final boolean historyDirty;

		private HistoryEditSnapshot(LinkedHashMap<String, Session> sessions,
		                            String currentSessionId,
		                            boolean historyLoaded,
		                            boolean historyDirty)
		{
			this.sessions = sessions;
			this.currentSessionId = currentSessionId;
			this.historyLoaded = historyLoaded;
			this.historyDirty = historyDirty;
		}
	}

}
