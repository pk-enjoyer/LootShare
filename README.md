<!--
Created with assistance from:
https://github.com/pk-enjoyer/runelite-plugin-developer-marketplace
-->

# Community Lootshare

Community Lootshare 4.0 is a RuneLite plugin backend for owner-approved,
party-shared loot tracking. The non-UI workflow is implemented, but the plugin
does not yet expose the approval and history controls needed to complete that
workflow from RuneLite.

## Current Status

The active Community Lootshare implementation provides:

- a loadable `CommunityLootsharePlugin` entry point and the stable
  `community-lootshare` config group;
- capture from RuneLite's `LootReceived` event while the local player is in a
  RuneLite Party, with identical same-tick event suppression and a fixed
  100,000-coin minimum bundle value;
- immutable item and pricing IDs, quantities, captured unit prices, capture
  timestamps, and overflow-checked event totals;
- bounded, versioned Party proposal and decision messages whose sender identity
  comes from RuneLite Party rather than the message payload;
- owner-only acceptance or rejection, with the logged-in Party roster frozen
  into each accepted proposal;
- Party-scoped sessions, duplicate/conflict handling, and late-join state sync;
- exact integer split calculation, deterministic remainder assignment, and
  payer-to-receiver settlement transfers; and
- asynchronous, atomic, schema-versioned JSON persistence under
  `~/.runelite/community-lootshare/`, scoped to the active RuneLite profile.

Captured events are stored and shared as pending proposals. Approval/rejection,
calculated balances, settlement transfers, and history are available through
the backend controller for the future view layer.

## Remaining UI Direction

The plugin still has no Community Lootshare sidebar, overlay, notification, or
user-facing settings. Most importantly, there is currently no in-client control
for an owner to accept or reject a pending proposal, so the workflow is not yet
user-completable despite the backend and protocol being implemented.

## Retained Migration Code

The repository still contains the earlier Auto Split Manager implementation
inside the `com.communitylootshare` namespace. Its legacy class names are
retained as migration/reference code and its headless tests still run, but it
is not the plugin declared in `runelite-plugin.properties` and is not loaded by
`./gradlew run`.

`./gradlew uiPreview` likewise opens only the retained Auto Split Manager stub
UI. It is not a preview of Community Lootshare.

## Development

The sources target Java 11. On the documented workstation, run Gradle with the
Java 21 JDK because the pinned Lombok version does not compile under Java 25:

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew test
```

Other useful commands are:

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew build
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew shadowJar
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew run
```

`./gradlew run` starts RuneLite in developer/debug mode with assertions enabled,
loads `com.communitylootshare.CommunityLootsharePlugin`, and exercises the
active event/Party/persistence boundary. It does not automate login or gameplay.
