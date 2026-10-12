# RuneLite Party Data Service experiment

Reviewed on 2026-10-12: [RuneLite PR #20544](https://github.com/runelite/runelite/pull/20544)
is open and unmerged. This experiment uses commit
`6395be9978662e4f093ca9a42c829f8486d042b8` (RuneLite `1.13.2-SNAPSHOT`).

## What it means for Community Lootshare

The proposed service shares tracking and transmission of inventory, equipment,
prayers, stats, rune-pouch contents, and quiver ammunition between subscribing
plugins. Plugins register the data types they need and consume `PartyDataEvent`.
The service batches updates, packs some fields, and reduces update frequency for
larger Parties. Inventory changes currently send a full slot snapshot; stats and
prayer updates are separate. Registering the same data type in multiple plugins
shares one outgoing stream.

Community Lootshare does not currently transmit those data types. It sends loot
occurrences, immutable captured prices, signed host decisions, policy, eligibility,
and history. The proposed service cannot replace those messages or their
authorization/recovery rules, and does not reduce their bandwidth. An inventory
gain can come from trading, banking, or picking up existing items, so it cannot
stand in for a loot event. Keep `ServerNpcLoot` and `LootReceived` capture.

If we later add Party inventory or equipment displays, use this service once its
API is released instead of building another transport for the same player data.
The proposal does not provide member names or login status, so our current Party
status/name handling also remains relevant. It adds a `UserSync` listener; our
host recovery uses the same request, making coexistence worth exercising.

There is no released dependency we can enable for this API yet. The experiment
uses Gradle's composite build to compile the actual PR source and substitute its
client, API, and jshell artifacts. Nothing is installed into Maven local, and our
normal `latest.release` dependency remains the default.

Validation on 2026-10-12: all 171 existing tests pass against both the released
dependency and the PR source. The optional suite passes 174 tests (171 existing
plus three service checks), with zero failures or skipped tests. The normal
`build` and coverage gate pass, both development client jars build, and the
experiment classes are absent from the plugin and shadow jars. The manual
launcher task graph was checked without starting RuneLite; live validation is
still pending.

## Prepare and run automated checks

Clone outside this repository and pin the reviewed revision:

```sh
git clone --no-checkout https://github.com/TheStonedTurtle/runelite.git /tmp/runelite-party-data-source
git -C /tmp/runelite-party-data-source checkout --detach 6395be9978662e4f093ca9a42c829f8486d042b8
```

From the Community Lootshare root:

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew \
  -Dorg.gradle.jvmargs=-Xmx2g \
  --include-build /tmp/runelite-party-data-source \
  -PpartyDataExperiment testPartyDataExperiment patchedRuneliteClientJar
```

The heap override avoids a compiler out-of-memory failure when compiling the
large upstream API source tree. The command runs all existing plugin unit tests
plus three checks using the real `PartyDataService` with mocked game/transport
boundaries: shared inventory subscription and final unregistration, remote
inventory delivery through the EventBus, and inventory-only full synchronization.
It also builds the client jar used by our development launcher. Artifact identity
is used to find that jar, supporting both cached releases and composite builds.

Use the ordinary command to check the released dependency:

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew build
```

The probe and compatibility sources are compiled only with `-PpartyDataExperiment`.
They are excluded from the production plugin and `shadowJar`. The experiment
flag requires the PR checkout; it will not compile against a release lacking the
proposed API.

## Try the service manually

```sh
env JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew \
  -Dorg.gradle.jvmargs=-Xmx2g \
  --include-build /tmp/runelite-party-data-source \
  -PpartyDataExperiment runPartyDataExperiment
```

This launches Community Lootshare alongside **Community Lootshare Party Data
Experiment**, a disabled-by-default development plugin. Enable the experiment
explicitly on both development clients. It subscribes only to inventory updates
and logs `Party Data inventory snapshot received: N slots` at DEBUG. It does not
display or log member identities or inventory contents, persist snapshots, or
feed them into the loot ledger. Disabling it unregisters all its subscriptions.

Follow RuneLite's [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
instructions to log into the development client. Perform all game actions manually.

```text
User manually changes inventory
  -> OSRS/Jagex updates game state
  -> RuneLite emits ItemContainerChanged / GameTick
  -> PartyDataService batches the registered inventory snapshot
  -> RuneLite Party delivers it to the other development client
  -> PartyDataEvent reaches the probe -> DEBUG slot-count log

User manually obtains loot
  -> OSRS/Jagex -> RuneLite ServerNpcLoot / LootReceived
  -> Lootshare capture + host policy -> signed decision / frozen roster
  -> Party transport -> both Lootshare sidebars show matching settlements
```

1. Create a Party with two development clients and enable the probe on both.
   Manually change one inventory and check the other client's DEBUG output for
   a snapshot. Banking/trading changes must not create new Lootshare records.
2. Approve both members in Lootshare, obtain an enabled loot drop, and add manual
   GP. Both sidebars should agree and the drop should be counted once.
3. Transfer host, restart the host plugin, and leave/rejoin as a guest. Inventory
   synchronization and Lootshare recovery should coexist; accepted loot, frozen
   rosters, and balances should survive.
4. Disable the probe on both clients, then change inventory again. Probe logs
   should stop while Community Lootshare remains operational. Other subscribing
   plugins may legitimately keep the upstream inventory stream enabled.

Automated checks do not validate the live Party server, login, rendering, or
real game-event ordering. Manual confirmation remains pending.
