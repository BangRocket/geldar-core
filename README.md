# Geldar-Core for NeoForge

[Geldar](https://github.com/SandboxServers/geldar)'s world plugin, Geldar-Core, carried over from Paper to a NeoForge
mod for Minecraft 26.1. It does on a NeoForge server what the plugin does on Paper, and speaks to the rest of Geldar
(the Body, the event sink, Glass) over the same NATS subjects with the same messages, so nothing else in a shard has to
change to run on it.

Its build is laid out as [Goldar](https://github.com/BangRocket/goldar)'s is: one Gradle project, with the game calls
that differ between Minecraft versions under `versions/<mc>`.

## Run it

Needs a JDK that can run Gradle; Gradle downloads the JDK the game needs.

```
gradlew build               (the unit tests, and the jar in build/26.1/libs)
gradlew runGameTestServer   (starts a world with the mod and runs the game tests in it; needs no EULA)
gradlew runServer           (a server to join; the first start stops until you accept the EULA in run/eula.txt)
```

For a real server, put `geldar-core-<version>+mc26.1.jar` in the server's `mods` folder. The jar carries the NATS
client. The mod is needed on the server only: a client without it, such as a Body, can join.

## What it does

Each part is the Paper plugin's, and the plugin's own README describes it in full.

| Part | Does |
|---|---|
| Event export | `server_tps` every 20 s, and `damage`, `death` and `sleep`, to `geldar.<shard>.<type>` in the `GELDAR_EVENTS` stream |
| Command block | Cancels and logs every command a player types; the console and RCON are unaffected |
| Ghosts | A joiner whose forwarded profile lacks `geldar_live` is held in spectator, and its chat is dropped |
| Gravestones | A stone-brick block and a sign where an agent character dies; no loot is touched |
| Muffled hearing | Chat reaches a listener by effective distance: clear, muffled (a `heard_muffled` notice) or silent |
| Nearby deaths | A death is announced to the players within 32 blocks only |
| Block ownership | Answers who placed the blocks of a chunk, and tells each change |
| Kino, Lakitu | The camera data feeds, off by default |
| Maintenance notices | Says the lines sent on `geldar.<shard>.maintenance` to the players online |
| BlueMap | The agents' skins and the live chat feed, when BlueMap is installed |
| Recipes | Nothing is granted on join unless `recipes.grant_on_join` is set |

## Where it differs from the Paper plugin

- **Settings** are in `config/geldar-core.toml`, with the keys of the plugin's `config.yml`. `GELDAR_NATS_URL` and
  `GELDAR_SHARD` in the environment still override the file.
- **Block ownership has no CoreProtect behind it.** There is no CoreProtect for NeoForge, so the mod keeps its own
  record: one entry for each standing block a player placed, saved with its chunk. A record whose block is gone (an
  explosion, a piston) is dropped when the chunk is next asked about. Only a loaded chunk can be asked about. A world
  brought over from Paper starts with an empty record: what CoreProtect knew does not come with it. Run
  `geldar-backfill-graves` from the console to record the gravestones already standing.
- **Chat** is cancelled and sent again to the speaker and to those who hear it clearly, because this server cannot take
  listeners off a line. It goes out as the same kind of player chat message, unsigned, as chat on an offline-mode
  server already is. A server that enforces signed chat would show these lines as not secure.
- **Death messages:** the mod sets the `show_death_messages` game rule to false when it starts, and announces each death
  to nearby players itself. Paper let the plugin clear one death's broadcast; this server has no such hook.
- **The `geldar:ghost` plugin message is not read.** On Paper it was a second way to mark a ghost. NeoForge disconnects
  a connection that sends a registered channel it did not negotiate, and discards an unregistered one, so the message is
  discarded and the forwarded profile decides alone. That was already the path that fails closed.
- **The damage cause** in `damage` and `death` events is still a Bukkit `DamageCause` name, found from the game's damage
  type by a table (`DamageCauses.java`). A sweeping attack reads as `ENTITY_ATTACK`, and a mod's own damage type as
  `CUSTOM`. The `amount` is the health lost; a blow that cost no health sends no event.
- **World names** in block-owner and Kino messages are still Paper's (`world`, `world_nether`, `world_the_end`), built
  from the server's `level-name`.
- **Server TPS** is measured by the mod over the last minute, as Paper's first TPS figure is.
- **The readiness marker** (`/tmp/geldar-core.ready`) is written on Linux only, where the healthcheck that reads it
  runs.

## Before it replaces Paper in a shard

These are outside the mod and have not been tried:

- **Velocity forwarding.** NeoForge does not take Velocity's modern forwarding by itself; the server needs a
  forwarding mod, and it must pass the profile's properties on, since the ghost gate reads `geldar_live` from them.
  Without that property every joiner is a ghost.
- **A Body joining.** No real client has been connected to a server running this mod. The game tests drive the
  handlers with players that have no client behind them, which shows what the server does and sends; what a Body makes
  of it (above all, whether it hears the re-sent chat as it hears Paper's) is still to be seen.
- **The jar on a real server.** The tests run the mod from its class folders. The jar, with the NATS client nested in
  it, has not been loaded by an installed server.
- **The world.** Paper keeps the Nether and the End in folders of their own; they have to be moved into the layout this
  server expects.
- **The other plugins.** Chunky and BlueMap have NeoForge builds. CoreProtect does not; see block ownership above.

## Tests

`gradlew test` runs the plugin's own tests for everything that holds no game types (467 of them, with the ones added
here), and checks the events against Geldar's contract schemas, copied into `src/test/resources/schemas`. The plugin's
listener tests used hand-written Bukkit doubles and are not carried over.

`gradlew runGameTestServer` runs the game tests (`src/gametest`) in their place: a NeoForge server starts a world, and
players with no client behind them join it, speak, build, type commands and die. A failed test fails the task. They
are part of the mod for that run only and are not in the jar. NATS is not there, so the events the mod would publish
are kept in memory and read back.

| Game test | Shows |
|---|---|
| `ghost_is_held_in_spectator` | A joiner without `geldar_live` is put in spectator and cannot leave it; one with it is left alone |
| `player_commands_are_blocked` | A player's command does nothing even with every permission; the console's still runs |
| `placed_blocks_have_an_owner` | A placed block is owned by its placer, a broken one by nobody, and a record whose block is gone is dropped |
| `agent_death_leaves_a_gravestone` | The stone and the sign with its four lines, both owned by the system; the `damage` and `death` events with cause, place, drops and respawn point; no marker for a player that is not an agent |
| `deaths_are_told_nearby_only` | A player 5 blocks from a death is told of it; one 60 blocks away is not |
| `chat_reaches_by_distance` | A line reaches the speaker and listeners in earshot; one under a roof at 30 blocks gets a muffled notice and no words; one at 36 gets nothing; a ghost's line reaches nobody |
| `backfill_records_old_gravestones` | `geldar-backfill-graves` finds a gravestone with no owner and records it as the system's |

Not covered by either kind of test: the `sleep` event and the `server_tps` timer as the game raises them, the Kino and
Lakitu feeds reading a live world, the maintenance broadcast, and everything that needs BlueMap or NATS itself.

## Layout

The packages and class names are the plugin's, so a change upstream can be carried over file by file. It was taken
from Geldar at commit `6cfdd1b`. Of the plugin's 87 source files, 58 are here unchanged, 22 are ported and 7 (the
CoreProtect, Bukkit and chat-thread adapters) are replaced.

| Path | Holds |
|---|---|
| `src/main/java/dev/geldar/core/GeldarCore.java` | Mod entry: wires everything when the server has started, releases it when it stops |
| `.../Settings.java`, `Log.java`, `Host.java` | The settings file, the logger, and what a feature needs from the running mod |
| `.../Worlds.java`, `DamageCauses.java`, `TpsMeter.java` | Paper's world names, Bukkit's damage cause names and the TPS figure |
| `.../WorldEvents.java`, `EventExporter.java` | Reads the game's damage, death and sleep events; builds the events from plain values |
| `.../blockowner/OwnerLedger.java` | The mod's own record of who placed what, in place of CoreProtect |
| `.../proximity/`, `kino/`, `lakitu/`, `speech/`, `skins/`, `maintenance/` | The plugin's packages; in each, only the listener and the world reader are new |
| `src/gametest/java/dev/geldar/core/GameTests.java` | The game tests, and the client-less players they use |
| `versions/26.1/.../Compat.java` | The game calls that differ between Minecraft versions |
| `versions/26.1/version.properties` | The NeoForge and Java versions this Minecraft version builds with |

Another Minecraft version is a new `versions/<mc>` folder with its own `Compat.java`, built with `-Pmc=<mc>`.

## Licence

MIT, as Geldar is; see [`LICENSE`](LICENSE). The code carried over from the plugin is SandboxServers'.
