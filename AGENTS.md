# RuneLite Plugin Development — Agent Guidelines

Source: https://github.com/runelite/example-plugin/blob/master/AGENTS.md

## Logging

- Use `log.debug()` for developer/diagnostic logging.
- Do not use `log.info` for per-frame or per-event logging - RuneLite runs at INFO level in production, so high-frequency info logs will pollute user logs. `log.info()` is fine for one-time startup/shutdown messages or infrequent events.

## Threading & Concurrency

- Never use `Thread.sleep()`.
- Never block on `shutDown()` or `startUp()` - don't call `executor.awaitTermination()` in shutdown, just use `shutdownNow()`.
- Never do blocking network IO or disk IO on the client thread. The OkHttp thread pool can be used for blocking network requests.
  If you need to call back into `client` from the okhttp threadpool, such as from the response queued with `enqueue()`, use `clientThread.invoke()`
- Explicitly cancel scheduled tasks (e.g. `ScheduledFuture`) on shutdown, in addition to shutting down the executor.
- For batching async work, use `CompletableFuture.allOf()` - not `CountDownLatch`.
- If you must use `Process.waitFor()`, always pass a reasonable timeout.

## Performance

- Don't scan the entire scene every tick or frame. Use events such as object and npc (de)spawn to track what you care about and maintain your own collection.
- Keep the computations in Overlays, which are run each frame, to a minimum.

## API Usage

- Use `net.runelite.api.gameval` package constants - `ItemID`, `InterfaceID`, `ObjectID`, etc. Never hardcode magic numbers when gameval constants can be used instead.
- Use `LinkBrowser` to open URLs, not `java.awt.Desktop`
- When looking up Widgets, pass the component ID from gamevals (eg `client.getWidget(InterfaceID.DomEndLevelUi.LOOT_VALUE)`) - do not manually combine interface + component child IDs.
- Use of Java reflection is forbidden.

## HTTP & JSON

- Use OkHttp for all HTTP requests. `@Inject OkHttpClient` to get the HTTP client. Do not use `HttpURLConnection`, `java.net.http.HttpClient`, or Apache HttpClient.
- Use `@Inject Gson` to get a Gson instead, never create your own from scratch. You can use `.newBuilder()` to create one derived from the base `Gson.`
- Do not add transitive dependencies from `runelite-client` directly to `build.gradle`, such as gson, guice, or okhttp.
- Never execute okhttp calls on the client thread. Prefer using `enqueue()` which places the request on the okhttp threadpool.

## File I/O

- Only read/write files inside the `.runelite` directory. Create a subdirectory for your plugin (e.g. `.runelite/your-plugin-name/`) if you need to store data on disk.
- Use `RuneLite.RUNELITE_DIR` to get the path.
- Alternatively, use `JFileChooser` for user-initiated file operations.

## Config

- Config group names must be specific - e.g. `"deadman-prices"`, not `"deadman"`.
- Never rename a config key or config group without providing a migration. Renaming silently resets users' saved settings.
- If you add a `@ConfigItem` that toggles a feature involving a third-party server, it must:
  - Be **disabled by default** (opt-in)
  - Have a `warning` field set to: `"This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"`

## Plugin Setup & Packaging

- Rename everything from the template. Do not leave `com.example`, `ExamplePlugin`, `ExampleConfig`, or `example` as the config group. Rename the package path, class names, config group, `build.gradle` group, `settings.gradle` project name, and `runelite-plugin.properties`.
- Do not include a `META-INF/services/net.runelite.client.plugins.Plugin` file.
- Do not commit build artifacts - no `.class` files, `out/` directories, or `.tmp` directories.
- `build.gradle` must target Java 11 and match the structure of the example-plugin template.
- Retain a permissive license, such as BSD-2.

## Resources & Assets

- Optimize icon PNGs. Java loads images at full resolution in memory (`width x height x 4` bytes), so a seemingly small file can use significant memory.
- Ensure PNGs are actually PNGs - do not rename JPEGs or ICOs to `.png`.

## Cleanup

- Remove unused config classes, fields, and imports.
- Clean up subscriptions, listeners, and overlays in `shutDown()`.
- Do not mix code reformatting with feature changes in the same commit - it makes diffs unreadable for reviewers.

## Testing

You cannot verify plugin behavior yourself. Even if you have screen-capture or computer-use tools available, **do not use them to interact with RuneScape** - automating game input violates Jagex's third-party client guidelines and will get the user's account banned. Only the user can confirm a plugin works in-game.

After completing a task, do not declare it done. Instead:

1. Offer to launch RuneLite for the user by running `./gradlew run` from the plugin's root directory.
2. Instruct the user to follow the "Using Jagex Accounts" instructions found at https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts to login to the development client.
3. Tell the user *what to test* - the specific behavior you changed, the golden path, and any edge cases worth exercising.
4. Wait for the user to confirm the feature works in-game before considering the task complete. A clean JVM start is not a passing test.

---

# Plugin Rules & Restrictions

Features that are **forbidden or restricted** in RuneLite hub plugins.
Sourced from [Jagex's Third-Party Client Guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1) and RuneLite's [Rejected or Rolled-Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features).

**If your plugin does any of the things listed below, it will be rejected.**

## Forbidden Language Features

- All code must be Java 11 compatible
- No use of reflection
- No use of JNI or JNA
- No direct access to native memory access via Unsafe or LWJGL
- No executing external processes, including with Process or ProcessBuilder
- No downloading or dynamic loading of code, including classloading
- No runtime generation of code
- No use of Java (de)serialization

## Boss & Combat Restrictions

Applies to all bosses, Raids sub-bosses, Slayer bosses, Demi-bosses, and wave-based minigames (Fight Caves, Inferno, etc.):

- No next-attack prediction (timing or attack style)
- No projectile target/landing indicators
- No prayer switching indicators
- No attack counters
- No automatic indicators showing where to stand or not stand (manual tile marking is allowed)
- No additional visual or audio indicators of a boss mechanic, unless it is a manually triggered external helper
- No advance warning of future hazards (highlighting currently active hazards is OK)
- No "flinch" timing helpers
- No combat prayer recommendations
- No NPC focus identification (which player the NPC is targeting)
- No content simulation (e.g. boss fight simulators)

New high-end PvM boss plugins are not accepted as a blanket policy.

## PvP Restrictions

- No removing or deprioritising attack/cast options in PvP
- No opponent freeze duration indicators
- No PvP clan opponent identification
- No PvP loot drop previews
- No identifying an opponent's opponent
- No PvP target scouting information
- No player group summaries (attackable counts, prayer usage, etc.)
- No level-based PvP player indicators (highlighting attackable players or those within level range)
- No spell targeting simplification (removing menu options to make targeting easier)

## Menu Restrictions

- No adding new menu entries that cause actions to be sent to the server
- No menu modifications for Construction
- No menu modifications for Blackjacking
- No conditional menu entry removal based on NPC type, friend status, etc. (can be overpowered)

## Interface Restrictions

- No unhiding hidden interface components (special attack bar, minimap)
- No moving or resizing click zones for 3D components
- No moving or resizing click zones for combat options, inventory, equipment, or spellbook
- No resizing prayer book click zones
- No resizing spellbook components
- No removing inventory pane background or making it click-through
- No detached camera world interaction (interacting with the game world from a camera position that isn't the player's)

## Input Restrictions

- No injecting input events, including mouse and keyboard events
- No autotyping - plugins must not programmatically insert text into the chatbox input (includes pasting, shorthand expansion)
- No modifying outgoing chat messages after the user sends them

## Data & Privacy Restrictions

- No exposing player information over HTTP
- No crowdsourcing data about other players (locations, gear, names, etc.)
- No credential manager plugins that stores account credentials

## Content Restrictions

- No adult or overtly sexual content
- No plugins that use player-provided IDs for their entire functionality (causes moderation issues)

---

# Community Lootshare 4.0 Notes

## Project Overview

This is a RuneLite external plugin named **Community Lootshare**. The active `com.communitylootshare` code captures RuneLite loot events, exchanges host-authorized decisions over RuneLite Party, persists profile-scoped sessions, computes exact splits and settlement transfers, and exposes Party, member-loot, eligibility, and settlement UI.

The active plugin entry point is `com.communitylootshare.LootsharePlugin`, declared in `runelite-plugin.properties` and loaded by the development launcher.


## Build And Test

- `./gradlew test` runs the JUnit 4 test suite.
- `./gradlew build` compiles and runs tests.
- `./gradlew shadowJar` builds a runnable RuneLite development-client jar using `com.communitylootshare.LootsharePluginTest`.
- `./gradlew run` loads `LootsharePlugin` in RuneLite developer/debug mode with assertions enabled.
- The project targets Java 11 in Gradle, even if local IDE/Qodana uses a newer JDK.
- On this workstation, use Java 21 for Gradle because Lombok `1.18.30` does not compile under Java 25:
  `env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew test`
- RuneLite dependencies use `latest.release`, so dependency behavior can drift over time.

## Java 11 Practices

Uphold these during normal changes:

- Keep source compatible with Java 11. Avoid records, text blocks, switch expressions, pattern matching, sealed classes, and APIs introduced after Java 11.
- Use a real JDK, not only a JRE. Local and CI environments need `javac` available for Gradle compilation.
- Prefer constructor injection and `private final` fields for service/controller dependencies.
- Use clear null handling. Validate inputs early, return before mutation when preconditions fail, and avoid passing null into methods that do not explicitly allow it.
- Do not silently swallow exceptions. Log useful context for config parsing, JSON persistence, amount parsing, and UI edit failures.
- Use parameterized logging, for example `log.debug("Failed to parse value {}", value, e);`.
- Keep model/business logic independent from Swing and RuneLite event classes where practical. UI confirmation and display should live in view/controller code, not core state classes.
- Use structured serializers/parsers for structured data. Prefer Gson for JSON rather than manual string concatenation.
- Keep dependencies conservative. Prefer Java/RuneLite APIs for small utilities unless a new dependency clearly reduces risk or complexity.

## Java Source Licensing

- Every newly created Java source or test file must begin with the project BSD-2 notice:
  `Copyright (c) 2025, pk-enjoyer` and `SPDX-License-Identifier: BSD-2-Clause`.
- Do not add or remove headers from pre-existing Java files solely for formatting consistency.

## Active Community Lootshare Architecture

- `LootsharePlugin` keeps RuneLite's `LootManager` active, captures its direct `ServerNpcLoot` events, registers the custom Party protocol, delegates RuneLite events to `LootshareController`, and unregisters its messages on shutdown.
- `LootshareConfig` establishes the stable `community-lootshare` config group and stores hosted-party preferences for loot sources, GE/high-alchemy valuation, the minimum split value, logged-out roster inclusion, and guest self-attributed manual GP. Every enabled drop is captured and shared regardless of value; the minimum filters only split calculations. A guest's local preferences are not effective or overwritten while another member hosts.
- `LootCaptureService` converts RuneLite item-stack collections into canonical, immutable price snapshots using the synchronized host valuation basis and suppresses identical captures from the same game tick. Direct `ServerNpcLoot` and higher-level `LootReceived` delivery can therefore coexist without duplicate proposals.
- `SharedLootItem` stores item/pricing IDs, quantity, and a captured unit price, validating IDs, quantity, price, and multiplication overflow.
- `SharedLootEvent` stores bounded proposal metadata, a capture timestamp, and up to 128 item records, and computes an overflow-checked total.
- `LootProposal` enforces pending/final state, authoritative owner identity, owner inclusion in accepted rosters, and decision chronology.
- `LootshareEngine` owns bounded proposal and Party-session state, revisioned host/member eligibility, host-only decisions, duplicate/conflict handling, snapshot/restore, and history.
- `ProposalMessage` defines the bounded version-2 capture/manual-GP payload, `DecisionMessage` defines version-2 host decisions, and `HostMessage` defines the version-4 host-policy and member-eligibility snapshot. The receiving `PartyMemberMessage.memberId` is authoritative; sender IDs are not serialized in plugin payloads.
- `LootshareController` coordinates asynchronous profile loading/saving, Party lifecycle and late-join sync, local capture, host/guest manual-GP validation, remote validation, automatic host decisions, eligibility changes, and calculation/history queries.
- The first RuneLite Party member becomes Community Lootshare host. Host authority and the complete capture/split policy are revisioned Party state; the host can transfer authority from the member-card right-click menu. Guests pause capture until the host snapshot is available and never fall back to their own preferences.
- `LootshareStorage` writes schema-versioned profile files beneath `RuneLite.RUNELITE_DIR/community-lootshare` through an atomic temporary-file replacement. Malformed, oversized, or future-schema files become read-only instead of being overwritten.
- `LootshareCalculator` freezes entitlement per accepted proposal roster, assigns indivisible-coin remainders deterministically by Party member ID, and emits zero-sum balances plus direct settlement transfers.
- `ui/lootshare/Panel` and `UiController` provide create/join/rejoin/leave Party controls, a collapsible effective-host-settings section, host-managed member eligibility, manual-GP actions, collapsible per-member loot summaries, a settlement section, and a reusable settlement graph popout. Rejoin uses RuneLite Party's existing hidden `previousPartyId` setting.
- `DebugSession` and the sidebar's developer simulation controls provide isolated in-memory fake members plus coin and Tombs of Amascut loot presets only when RuneLite developer mode is active. Coin value is user-provided; item presets capture `ItemManager` prices resolved in the controller's client-thread snapshot. Debug state never mutates `PartyService`, sends Party messages, or persists.
- No Community Lootshare overlay, notification, or history view is implemented yet. Do not present the sidebar as a user-complete workflow.

## Testing Guidance

Existing tests cover:

- initial Community Lootshare proposal/item domain behavior in `SharedLootEventTest`
- host policy, Party payload decoding, state lifecycle, split/settlement math, atomic persistence, and the active controller boundary

When changing behavior, add or update focused JUnit 4 tests near the affected class. For split math changes, include multi-segment roster-change scenarios because most regressions hide there.

## Unit Test Conventions

Use `src/test/java` for automated unit tests and keep the full RuneLite client launcher separate from those tests.

- Test names should follow `<ClassUnderTest>Test`, matching the current JUnit 4 style.
- Use JUnit 4 assertions and `@Test`; do not introduce JUnit 5 unless the project is deliberately migrated.
- Use Mockito for RuneLite boundaries such as `Client`, `LootshareConfig`, `ClientToolbar`, `PartyService`, and `LootsharePlugin`.
- Prefer real domain objects and mocked RuneLite/client objects. Unit tests should not start RuneLite, log into OSRS, hit the network, require graphics, or depend on the user's RuneLite config directory.
- Stub config values explicitly. Mockito will not reliably exercise interface default methods unless configured to call real methods.
- Use a real `Gson` configured with `InstantTypeAdapter` when testing Community Lootshare persistence or session JSON.
- For persistence tests, prefer `LootshareStorage` with `TemporaryFolder` so tests do not touch the user's RuneLite profile directory.
- Keep tests deterministic: avoid assertions based on wall-clock timestamps, random UUID values, Swing focus, table row selection side effects, or map ordering unless the ordering is part of the contract.
- Assert raw amounts in coins. Use formatted strings only when testing formatter/table output.
- For split math, assert accepted totals, frozen rosters, balances, and transfers. Negative net value means payer/owes; positive net value means receiver/is owed.
- For manual GP, test host attribution, guest self-attribution policy, Party payload validation, and rejection of unauthorised or ineligible targets.
- Avoid unit tests that invoke `JOptionPane`, clipboard access, or actual `NavigationButton`/toolbar registration. If behavior needs testing, move the decision into controller/domain code and leave the UI call as a thin shell.
- If Swing behavior must be tested, run UI mutation code on the EDT with `SwingUtilities.invokeAndWait`, and keep it headless-safe.

RuneLite-specific testing layers:

- **Community Lootshare backend tests:** focused tests cover immutable event/proposal/session validation, capture/deduplication, Party payload decoding, state lifecycle, split/settlement math, atomic persistence, and the active controller boundary.
- **Manual smoke test:** `com.communitylootshare.LootsharePluginTest` loads `LootsharePlugin` via `ExternalPluginManager.loadBuiltin(...)` and launches RuneLite. Treat this as a manual development-client path, not an automated unit test.

Best setup for this RuneLite external plugin:

- Keep `testImplementation 'junit:junit:4.12'`, Mockito, `net.runelite:client`, and `net.runelite:jshell`.
- Keep automated verification on `./gradlew test`.
- The `run` Gradle task launches `com.communitylootshare.LootsharePluginTest`, enables assertions, and passes `--developer-mode`/`--debug`.
- Production sources compile to Java 11 bytecode. On this workstation, invoke Gradle with the documented Java 21 JDK; a JRE-only install is not enough because Gradle needs `javac`.

Research references:

- RuneLite Plugin Hub README: Java 11 is the expected development setup, and local plugin launch is done through the Gradle `run` task.
- RuneLite `example-plugin` `build.gradle`: uses JUnit 4 plus `net.runelite:client` and `net.runelite:jshell` in `testImplementation`, and defines `run` as a test-runtime `JavaExec`.
- RuneLite `example-plugin` launcher: `ExamplePluginTest` uses `ExternalPluginManager.loadBuiltin(...)` before calling `RuneLite.main(args)`.
- RuneLite Developer Guide: plugin architecture centers on the plugin class/config/overlays/event subscribers/Swing panels, which should shape test boundaries.

## Current Cautions

- The repository may contain local uncommitted changes; check `git status --short` before editing and do not revert unrelated user work.
- The developer simulation is intentionally isolated: it must never mutate `PartyService`, send Party messages, or persist history.
