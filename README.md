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
  record, saved with each chunk. It holds what CoreProtect's newest row at each position would say, as Geldar
  configures CoreProtect (Geldar's `docs/runbooks/block-owner.md`): block place and break, natural breaks, block
  movement, burning and explosions are logged; growth, decay, fluids, buckets and interactions are not. So:
  - A block a player places is theirs, and so is a block an item they use on a block makes: tilled farmland, a
    stripped log, a path, fire from flint and steel. What bone meal grows is no one's.
  - A player's break, a place by anything else, a piston's push and an explosion take a block from its owner.
  - A change nothing logs leaves the record standing, still naming the block that was placed: farmland that decays to
    dirt (the case Geldar's decision 0219 handles), copper that weathers, a sapling that grows. A record whose cell is
    found empty (air, a fluid or fire) has lost its block and is dropped.
  - Only a loaded chunk can be asked about; another is answered as unknown, which the Body treats as protected.

  A world brought over from Paper keeps its owners only if CoreProtect's history is imported; see below.
- **Chat** is cancelled and sent again to the speaker and to those who hear it clearly, because this server cannot take
  listeners off a line. It goes out as the same kind of player chat message, unsigned, as chat on an offline-mode
  server already is. A server that enforces signed chat would show these lines as not secure.
- **Death messages:** the mod sets the `show_death_messages` game rule to false when it starts, and announces each death
  to nearby players itself. Paper let the plugin clear one death's broadcast; this server has no such hook.
- **The `geldar:ghost` plugin message is not read.** On Paper it was a second way to mark a ghost. NeoForge disconnects
  a connection that sends a registered channel it did not negotiate, and discards an unregistered one, so the message is
  discarded and the forwarded profile decides alone. That was already the path that fails closed.
- **The damage cause** in `damage` and `death` events is still a Bukkit `DamageCause` name, found from the game's damage
  type by a table (`DamageCauses.java`). A sweeping attack reads as `ENTITY_ATTACK` and poison as `MAGIC`, where Bukkit
  says `ENTITY_SWEEP_ATTACK` and `POISON`; a mod's own damage type reads as `CUSTOM`. The `amount` is the health lost
  after armour, enchantments and absorption. A blow that costs no health, such as one a shield takes whole, is still
  sent, with an amount of 0.
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

## Bringing a Paper shard's owners across

CoreProtect's database holds who placed every block on the Paper shard. Without it, everything built there would read
as no one's on this server, and a Body would dig through it. To bring it across:

1. Stop the Paper server and copy `plugins/CoreProtect/database.db`.
2. Export the owners with [`tools/coreprotect-owners.sql`](tools/coreprotect-owners.sql), using SQLite 3.25 or newer:

   ```
   sqlite3 -header -csv database.db < tools/coreprotect-owners.sql > owners.csv
   ```

   The tables carry CoreProtect's default prefix, `co_`; edit the file if the shard set another `table-prefix`.
3. Put `owners.csv` in this server's folder and run, from the console or RCON:

   ```
   geldar-import-owners owners.csv
   ```

The export keeps a position when its newest CoreProtect row, rolled-back rows passed over, is a place by a player,
which is the rule the plugin applied to every query. The gravestones the plugin logged as `geldar-system` come
with it. The command answers with what it read and skipped. It keeps the export with the world, as
`geldar-core/owner-import.csv` in the world folder, and lays each chunk's share in when that chunk loads, so nothing is
loaded to do it; the file is gone once every chunk has taken its share. A position that already has an owner on this
server keeps it, and a position whose cell is empty takes nothing. Each chunk takes imported history once, so a
second import does not reach the chunks the first one did. World names must be Paper's (`world`, `world_nether`,
`world_the_end`, after this server's `level-name`); rows for a world this server does not have wait in the file.

The SQL follows CoreProtect 24.1's SQLite layout and is tested against CoreProtect's own table definitions; it has not
been run on a real shard's database.

## Tests

Every push and pull request is built by GitHub Actions, which runs both kinds of test below and keeps the jar.

`gradlew test` runs the plugin's own tests for everything that holds no game types (481 of them, with the ones added
here), and checks the events against Geldar's contract schemas, copied into `src/test/resources/schemas`. It also runs
the CoreProtect export against tables made with CoreProtect 24.1's own definitions. The plugin's listener tests used
hand-written Bukkit doubles and are not carried over.

`gradlew runGameTestServer` runs the game tests (`src/gametest`) in their place: a NeoForge server starts a world, and
players with no client behind them join it, speak, build, type commands and die. A failed test fails the task. They
are part of the mod for that run only and are not in the jar. NATS is not there, so the events the mod would publish
are kept in memory and read back.

| Game test | Shows |
|---|---|
| `ghost_is_held_in_spectator` | A joiner without `geldar_live` is put in spectator and cannot leave it; one with it is left alone |
| `player_commands_are_blocked` | A player's command does nothing even with every permission; the console's still runs |
| `placed_blocks_have_an_owner` | A placed block is owned by its placer, a broken one by nobody, and a record whose cell is emptied is dropped |
| `agent_death_leaves_a_gravestone` | The stone and the sign with its four lines, both owned by the system; the `damage` and `death` events with cause, place, drops and respawn point; no marker for a player that is not an agent |
| `deaths_are_told_nearby_only` | A player 5 blocks from a death is told of it; one 60 blocks away is not |
| `chat_reaches_by_distance` | A line reaches the speaker and listeners in earshot; one under a roof at 30 blocks gets a muffled notice and no words; one at 36 gets nothing; a ghost's line reaches nobody |
| `backfill_records_old_gravestones` | `geldar-backfill-graves` finds a gravestone with no owner and records it as the system's |
| `tilling_is_the_tillers` | Farmland a hoe makes is the tiller's, and still names farmland after it decays to dirt |
| `unlogged_changes_keep_the_owner` | Weathered copper keeps its placer and names the block placed; an emptied cell does not |
| `pistons_take_ownership` | Every cell a piston push touches has no owner, even where a stone stands again |
| `explosions_take_ownership` | A cell an explosion emptied has no owner, even once something fills it |
| `bone_meal_growth_is_no_ones` | A tree bone meal grows is no one's; the planted cell keeps its record, naming the sapling |
| `coreprotect_history_is_imported` | `geldar-import-owners` lays owners into a loaded chunk, skips empty cells and keeps newer owners |

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
| `.../blockowner/OwnerLedger.java`, `BlockChangeListener.java` | The mod's own record of who placed what, in place of CoreProtect, and the changes that write it |
| `.../blockowner/OwnerImport.java`, `OwnerImporter.java` | Reading CoreProtect's exported history, and laying it into each chunk as it loads |
| `tools/coreprotect-owners.sql` | The export of a Paper shard's owners from CoreProtect's database |
| `.../proximity/`, `kino/`, `lakitu/`, `speech/`, `skins/`, `maintenance/` | The plugin's packages; in each, only the listener and the world reader are new |
| `src/gametest/java/dev/geldar/core/GameTests.java` | The game tests, and the client-less players they use |
| `versions/26.1/.../Compat.java` | The game calls that differ between Minecraft versions |
| `versions/26.1/version.properties` | The NeoForge and Java versions this Minecraft version builds with |

Another Minecraft version is a new `versions/<mc>` folder with its own `Compat.java`, built with `-Pmc=<mc>`.

## Licence

MIT, as Geldar is; see [`LICENSE`](LICENSE). The code carried over from the plugin is SandboxServers'.
