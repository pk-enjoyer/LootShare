<!--
Created with assistance from:
https://github.com/pk-enjoyer/runelite-plugin-developer-marketplace
-->

# Community Lootshare

Community Lootshare 4.0 is a RuneLite plugin for host-managed, party-shared
loot tracking. Its active sidebar can create, join, rejoin, and leave RuneLite
Parties, control member eligibility, inspect per-member loot, and calculate the
payments needed to settle a shared session.

## Current Status

The active Community Lootshare implementation provides:

- a loadable `LootsharePlugin` entry point and the stable
  `community-lootshare` config group;
- host-filtered NPC-drop capture from RuneLite's `ServerNpcLoot` event while the
  local player is in a RuneLite Party, plus `LootReceived` capture for RuneLite's
  other tracked loot sources, with identical same-tick event suppression;
- a locally saved hosted-party policy covering NPC, activity, player,
  pickpocket, and unknown loot sources; Grand Exchange or high-alchemy
  valuation; the minimum value included in split calculations; and whether
  logged-out Party members join frozen split rosters;
- capture and Party transmission of every enabled drop regardless of its value;
  the host's minimum only filters split calculations and never hides or blocks
  an otherwise enabled drop;
- revisioned host-policy synchronization: guests use the host's effective
  policy without overwriting their own saved preferences and pause new capture
  while waiting for a valid host snapshot;
- immutable item and pricing IDs, quantities, captured unit prices, capture
  timestamps, and overflow-checked event totals;
- bounded, versioned Party proposal and decision messages whose sender identity
  comes from RuneLite Party rather than the message payload;
- host-controlled member eligibility: the host is always approved, new members
  start pending, and the host can approve or exclude them from their member card;
- automatic host decisions for every capture: approved members' loot is accepted
  with the currently approved roster frozen into it, while pending or excluded
  members' loot is rejected and hidden;
- Party-scoped sessions, duplicate/conflict handling, and late-join state sync;
- exact integer split calculation, deterministic remainder assignment, and
  payer-to-receiver settlement transfers;
- asynchronous, atomic, schema-versioned JSON persistence under
  `~/.runelite/community-lootshare/`, scoped to the active RuneLite profile;
- an active Community Lootshare sidebar with Party controls, including rejoining
  the previous Party, first-member host election, right-click host transfer, a
  collapsible effective-host-settings summary, eligibility badges/actions,
  per-member loot summaries, and an auto-opening settlement section;
- exact balance and direct-payment tables plus a reusable settlement dashboard
  with GP/hr, highest-earnings, and settlement-balance graphs; and
- a developer-mode-only, in-memory simulation menu for adding fake Party
  members and deterministic sample loot without sending or persisting it.

Eligibility changes only affect future decisions. Every accepted proposal keeps
its frozen roster, so excluding a member later does not rewrite earlier balances.

## Current UI Direction

The sidebar now covers Party setup, the effective host policy, member eligibility,
per-member loot inspection, balances, and settlement payments. The graph button
opens one reusable settlement dashboard window and focuses it if it is already
open. A history view, overlay, and notifications are not implemented yet.

## Developer Simulation

`./gradlew run` already starts RuneLite with `--developer-mode`. In that client,
open the Community Lootshare sidebar and use **Developer simulation** to start a
local scenario, add or remove fake Party members, approve them from their member
cards, choose a recipient, and add
either a custom-value coin stack or a Tombs of Amascut reward preset: Osmumten's
fang, Lightbearer, Masori body, or Tumeken's shadow. Item presets capture their
current RuneLite item price automatically. Reset returns to a clean simulated
Party; **Return to live Party** restores the real Party view.

The simulation uses a separate in-memory state engine. Fake members and sample
loot are never inserted into RuneLite's `PartyService`, sent over Party
messages, or written to Community Lootshare profile history. The controls are
not available in a normal non-developer RuneLite launch.

## Manual GP

The Party host can add a manual GP contribution to any approved member. The
host may enable **Allow member manual GP** to let approved members add GP only
to themselves. These contributions use the same Party-synchronised proposal,
settlement, and persisted-history paths as captured loot; OSRS chat commands
and chat value parsing are not supported.

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
loads `com.communitylootshare.LootsharePlugin`, and exercises the
active event/Party/persistence/UI boundary. It does not automate login or
gameplay.
