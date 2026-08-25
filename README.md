# Community Lootshare

> **Early development:** this plugin is not yet a complete end-user workflow.

Community Lootshare is a RuneLite external plugin that records Party loot, lets the Party host decide what is included, and calculates exact equal-share settlement payments.

## Technical concepts

- **Event-driven RuneLite plugin:** `LootsharePlugin` subscribes to RuneLite loot, Party, profile, and game events. It keeps RuneLite's `LootManager` active so NPC loot can be captured even when Loot Tracker is disabled.
- **Immutable loot snapshots:** each capture normalizes item variants, records quantity and the unit price at capture time, and creates a bounded `SharedLootEvent`. This keeps historical calculations stable if GE prices later change.
- **Duplicate suppression:** the capture service fingerprints identical loot captured in the same game tick, allowing the low-level NPC and higher-level loot events to coexist without double-counting.
- **Host-authoritative Party protocol:** custom RuneLite Party messages carry loot proposals, host policy, member identity, and accept/reject decisions. The first Party member is the host; host state is revisioned so guests use the same policy rather than local settings.
- **Proposal and roster lifecycle:** the engine validates incoming messages, keeps bounded session history, and freezes the eligible roster for each accepted proposal. Later joins, leaves, or eligibility changes cannot rewrite an earlier split.
- **Exact integer split math:** values are split in GP using integer arithmetic. Any indivisible-coin remainder is allocated deterministically, balances are checked to be zero-sum, and the result is expressed as direct transfers through the host.
- **Profile-scoped persistence:** session state is saved as schema-versioned JSON below RuneLite's configuration directory. Writes use a temporary file and atomic replacement; malformed, oversized, or newer-schema files become read-only rather than being overwritten.
- **Separation of concerns:** the controller coordinates RuneLite and Party boundaries, the engine owns state and validation, the calculator owns settlement math, storage owns persistence, and the Swing sidebar renders the current state.

## Development

The project targets Java 11 bytecode. On this workstation, use Java 21 to run Gradle:

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew test
```

To load it in RuneLite's development client:

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew run
```

Create or join a RuneLite Party, then ensure all participants have the plugin enabled.
