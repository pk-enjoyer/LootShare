<!--
Created with assistance from:
https://github.com/pk-enjoyer/runelite-plugin-developer-marketplace
-->

# Community Lootshare

Community Lootshare 4.0 is an early RuneLite plugin foundation for a future
owner-approved, party-shared loot workflow. It is not yet a functional loot
tracker or split manager.

## Current Status

The current Community Lootshare implementation provides:

- a loadable `CommunityLootsharePlugin` entry point and the stable
  `community-lootshare` config group;
- Java 11-compatible Gradle, Plugin Hub, and development-launch metadata;
- immutable-valued `SharedLootItem` objects containing item and pricing IDs,
  quantity, and a captured unit price, with arithmetic overflow validation;
- `SharedLootEvent` proposal data containing a proposal ID, recipient, source
  label, up to 128 item records, and an overflow-checked total; and
- focused unit tests for the initial domain model.

The plugin entry point currently has no event subscribers, toolbar panel,
overlay, persistence, or user-visible settings. Loading it in RuneLite only
validates that the foundation is wired correctly.

## Planned Direction

The intended next phase is to connect this foundation to RuneLite Party and
Loot Tracker, exchange loot proposals, require the relevant owner's approval,
and retain the captured item prices when an event is accepted. These are
design goals, not implemented features.

In particular, the current code does **not** yet provide:

- Party messaging or Loot Tracker capture;
- proposal transport, approval, rejection, or duplicate handling;
- a Community Lootshare sidebar, overlay, or notification flow;
- saved Community Lootshare sessions or history; or
- active split calculation and settlement guidance.

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

`./gradlew run` starts RuneLite in developer/debug mode with assertions enabled
and loads `com.communitylootshare.CommunityLootsharePlugin`. It does not
automate login or gameplay.
