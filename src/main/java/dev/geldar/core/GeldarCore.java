package dev.geldar.core;

import dev.geldar.core.blockowner.BlockOwnerSetup;
import dev.geldar.core.blockowner.OwnerLedger;
import dev.geldar.core.kino.KinoFeed;
import dev.geldar.core.kino.KinoSetup;
import dev.geldar.core.lakitu.LakituFeed;
import dev.geldar.core.maintenance.MaintenanceNotices;
import dev.geldar.core.maintenance.MaintenanceSetup;
import dev.geldar.core.proximity.ChatHearing;
import dev.geldar.core.proximity.HearingNoticeSink;
import dev.geldar.core.proximity.LevelBlockReader;
import dev.geldar.core.proximity.MuffleConfig;
import dev.geldar.core.proximity.MuffleSettings;
import dev.geldar.core.proximity.NatsHearingSink;
import dev.geldar.core.proximity.ProximityChatListener;
import dev.geldar.core.proximity.ProximityDeathListener;
import dev.geldar.core.skins.AgentSkinSetup;
import dev.geldar.core.speech.SpeechSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Geldar-Core as a NeoForge mod: wires the settings, the NATS publisher, the exporter and the listeners when the server
 * has started, and releases them when it stops. It is the Paper plugin of the same name carried over; the code that
 * holds no game types is the plugin's own.
 */
@Mod(GeldarCore.ID)
public final class GeldarCore {
    public static final String ID = "geldar_core";
    /** Written while the mod is enabled; the server's healthcheck reads it. Linux only, as the healthcheck is. */
    static final Path READY_FILE = Path.of("/tmp/geldar-core.ready");
    static final Path PROC_STAT = Path.of("/proc/self/stat");
    /** The settings file is read again this often, in ticks (5 s). */
    static final int RELOAD_TICKS = 100;
    /** The console command that imports a Paper shard's CoreProtect history. */
    static final String IMPORT_OWNERS = "geldar-import-owners";

    /**
     * Makes the event publisher. The game tests put one that keeps its events in its place, before the server starts;
     * nothing else sets it.
     */
    static BiFunction<CoreConfig, Logger, EventPublisher> publishers =
            (config, log) -> new NatsEventPublisher(config.natsUrl(), config.queueCapacity(), log);
    /** Told each muffled notice as well as NATS is, when set. The game tests set it; nothing else does. */
    static volatile HearingNoticeSink hearingTap;
    private static volatile GeldarCore instance;

    private final Logger log = Log.create();
    private final LoadedChunks loaded = new LoadedChunks();
    private volatile Running running;

    /** The block-owner feature of the running mod, or null while the server is not running. For the game tests. */
    static BlockOwnerSetup blockOwners() {
        GeldarCore mod = instance;
        Running now = mod == null ? null : mod.running;
        return now == null ? null : now.blockOwner;
    }

    public GeldarCore(IEventBus modBus) {
        instance = this;
        OwnerLedger.register(modBus);
        NeoForge.EVENT_BUS.register(this);
        // Chunks load before the server has started, so the list is kept from the first one.
        NeoForge.EVENT_BUS.register(loaded);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        enableOrStop(() -> {
            Running starting = new Running(server);
            running = starting;
            starting.enable();
        }, this::disable, () -> server.halt(false), log);
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        disable();
    }

    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        Running now = running;
        if (now != null) {
            now.tick();
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        // geldar-import-owners <file>: owners exported from a Paper shard's CoreProtect database, read from a path
        // relative to the server's folder. Console or RCON only, as the backfill is.
        event.getDispatcher().register(Commands.literal(IMPORT_OWNERS).requires(Compat.consoleOnly())
                .then(Commands.argument("file", StringArgumentType.greedyString()).executes(context -> {
                    Running now = running;
                    String file = StringArgumentType.getString(context, "file");
                    String reply = now == null || now.blockOwner == null
                            ? "Geldar-Core is not running."
                            : now.blockOwner.importOwners(now.server.getServerDirectory().resolve(file));
                    context.getSource().sendSuccess(() -> Component.literal(reply), false);
                    return 1;
                })));
        event.getDispatcher().register(Commands.literal(GraveBackfillCommand.NAME).requires(Compat.consoleOnly())
                .executes(context -> {
                    Running now = running;
                    if (now == null || now.backfill == null) {
                        context.getSource().sendSuccess(() -> Component.literal("Geldar-Core is not running."), false);
                    } else {
                        now.backfill.run(context.getSource());
                    }
                    return 1;
                }));
    }

    private void disable() {
        Running stopping = running;
        running = null;
        if (stopping != null) {
            stopping.disable();
        }
    }

    /**
     * Runs {@code enable}; when it fails, releases what it started and stops the server. A server that runs
     * on without the command block and the export is worse than one that stops.
     */
    static void enableOrStop(Runnable enable, Runnable disable, Runnable shutdown, Logger log) {
        try {
            enable.run();
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Geldar-Core failed to enable; stopping the server", e);
            disable.run();
            shutdown.run();
        }
    }

    /** One run of the mod, from server start to server stop. */
    private final class Running implements Host {
        private record Timer(int ticks, Runnable task) {}

        private final MinecraftServer server;
        private final Path settingsFile;
        private final List<Timer> timers = new ArrayList<>();
        private final List<Object> listeners = new ArrayList<>();
        private final TpsMeter tps = new TpsMeter();
        private volatile Settings settings;
        private long ticks;
        private EventPublisher publisher;
        private volatile EventExporter exporter;
        private ScheduledExecutorService tpsTimer;
        private ReadyMarker readyMarker;
        private volatile BlockOwnerSetup blockOwner;
        private NatsHearingSink hearingSink;
        private SpeechSetup speech;
        private GraveBackfillCommand backfill;

        Running(MinecraftServer server) {
            this.server = server;
            this.settingsFile = server.getServerDirectory().resolve("config").resolve("geldar-core.toml");
        }

        @Override
        public MinecraftServer server() {
            return server;
        }

        @Override
        public Logger log() {
            return log;
        }

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void every(int ticks, Runnable task) {
            timers.add(new Timer(Math.max(1, ticks), task));
        }

        @Override
        public void run(Runnable task) {
            server.execute(task);
        }

        @Override
        public void listen(Object listener) {
            listeners.add(listener);
            NeoForge.EVENT_BUS.register(listener);
        }

        @Override
        public String worldName(net.minecraft.world.level.Level level) {
            return Worlds.name(Compat.levelName(server), Compat.dimension(level));
        }

        void tick() {
            ticks++;
            tps.tick(System.nanoTime());
            // By index: a task may not add a timer, and none does after enable.
            for (int i = 0; i < timers.size(); i++) {
                Timer timer = timers.get(i);
                if (ticks % timer.ticks() == 0) {
                    try {
                        timer.task().run();
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "a timed task failed", e);
                    }
                }
            }
        }

        void enable() {
            try {
                settings = Settings.load(settingsFile);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("cannot read " + settingsFile, e);
            }
            CoreConfig config = CoreConfig.load(settings, System::getenv, log::warning);
            publisher = publishers.apply(config, log);
            exporter = new EventExporter(publisher, config.shard(), this::overworldTick, Instant::now, log);

            listen(new WorldEvents(exporter, log));
            if (config.grantRecipesOnJoin()) {
                listen(new RecipeGrantListener(log));
            }
            listen(new CommandBlockListener(log));
            GhostListener ghosts = new GhostListener(log);
            listen(ghosts);

            if (config.lakitu().enabled()) {
                LakituFeed.register(this, config.lakitu());
            }
            // Kino is registered always and applied from kino.enabled (default false: the feed starts killed, so it
            // publishes nothing). The settings are re-read every 5 s, so flipping kino.enabled needs no restart.
            KinoFeed kino = KinoSetup.register(this, settings, config.natsUrl(), config.shard(), System::getenv);
            KinoSetup.reload(kino, settings);
            // The maintenance notice broadcast (CD-1c): maintenance.broadcast, applied by the same 5 s reload.
            MaintenanceNotices maintenance = MaintenanceSetup.register(
                    this, settings, config.natsUrl(), config.shard(), System::getenv);
            // Block ownership (the mod's own ledger): blockowner.* is applied by the same 5 s reload.
            BlockOwnerSetup owners = BlockOwnerSetup.register(this, config.natsUrl(), config.shard(), System::getenv);
            blockOwner = owners;
            // Gravestones (no loot): a marker block and sign where an agent character dies. Read once at enable.
            // Registered after block ownership, because each gravestone is recorded as owned by the system.
            listen(new GravestoneListener(
                    settings.getBoolean("gravestones.enabled", true),
                    Math.max(1, settings.getInt("gravestones.max_per_chunk", 4)), log, owners.placements(),
                    this::worldName));
            backfill = new GraveBackfillCommand(this, loaded, owners.placements(), owners.source());
            // The live chat feed for the BlueMap panel (decision 0310): agents' chat only, off without BlueMap.
            try {
                speech = SpeechSetup.register(this);
            } catch (LinkageError | RuntimeException e) {
                log.warning("chat feed is off: " + e.getClass().getSimpleName());
            }
            // Proximity (spec 5.8, decision 0199): chat is gated on effective distance; a muffled voice sends a notice.
            MuffleSettings muffle = MuffleSettings.clamped(
                    settings.getDouble("chat.muffle.margin", MuffleConfig.MARGIN),
                    settings.getDouble("chat.muffle.sealed_penalty", MuffleConfig.SEALED_PENALTY),
                    settings.getInt("chat.muffle.per_line_cap", MuffleConfig.PER_LINE_READ_CAP),
                    settings.getInt("chat.muffle.per_ray_cap", MuffleConfig.PER_RAY_READ_CAP),
                    settings.getBoolean("chat.muffle.bench_log", false),
                    log::warning);
            hearingSink = new NatsHearingSink(config.natsUrl(), config.shard(), System::getenv, log);
            SpeechSetup feed = speech;
            listen(new ProximityChatListener(
                    () -> ticks,
                    LevelBlockReader::new,
                    new ChatHearing(muffle, log::info),
                    (lifeId, direction, band) -> {
                        hearingSink.muffled(lifeId, direction, band);
                        HearingNoticeSink tap = hearingTap;
                        if (tap != null) {
                            tap.muffled(lifeId, direction, band);
                        }
                    },
                    ghosts::isGhost,
                    (speaker, text) -> {
                        if (feed != null) {
                            feed.said(Compat.name(speaker), speaker.getUUID(), text);
                        }
                    },
                    log));
            // Deaths are told to those nearby only (spec 5.9), so the game's own server-wide line is turned off.
            if (Compat.announcesDeaths(server)) {
                Compat.stopAnnouncingDeaths(server);
                log.info("The show_death_messages game rule is now false: deaths are announced to nearby players only");
            }
            listen(new ProximityDeathListener(
                    () -> server.getPlayerList().getPlayers(), server::sendSystemMessage, log));
            // BlueMap skin provider (GX-07): never returns an empty skin, so BlueMap keeps the agents' head files.
            // The class is not touched without BlueMap: loading it needs BlueMap's own classes.
            if (ModList.get().isLoaded("bluemap")) {
                try {
                    AgentSkinSetup.register(this, System::getenv);
                } catch (LinkageError | RuntimeException e) {
                    log.warning("agent skins are not set: " + e.getClass().getSimpleName());
                }
            } else {
                log.info("BlueMap is not installed; agent skins are not set");
            }
            every(RELOAD_TICKS, () -> {
                try {
                    settings = Settings.load(settingsFile);
                } catch (IOException | RuntimeException e) {
                    // A file caught half-written, or one with a mistake in it: the last good settings stand.
                    log.fine("settings not re-read: " + e.getMessage());
                    return;
                }
                KinoSetup.reload(kino, settings);
                MaintenanceSetup.reload(maintenance, settings);
                owners.reload(settings);
            });

            // A wall-clock timer: a tick-based task thins out when the server lags, which is when the figure matters.
            // The game is read back on the server thread; the timer thread touches no server state.
            tpsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "geldar-tps-timer");
                t.setDaemon(true);
                return t;
            });
            tpsTimer.scheduleAtFixedRate(this::requestTpsExport, config.tpsIntervalSeconds(),
                    config.tpsIntervalSeconds(), TimeUnit.SECONDS);
            // Last of the enable steps: the healthcheck passes only once the command block and export are wired.
            writeReadyMarker();
            log.info("Geldar-Core enabled for shard " + config.shard());
        }

        /** The marker holds this process's start time from /proc, so it is written on Linux only. */
        private void writeReadyMarker() {
            if (!Files.isReadable(PROC_STAT)) {
                log.info("Readiness marker not written: " + PROC_STAT + " is not readable (not Linux)");
                return;
            }
            ReadyMarker marker = ReadyMarker.forThisProcess(READY_FILE);
            try {
                marker.write();
            } catch (IOException e) {
                throw new IllegalStateException("cannot write " + READY_FILE, e);
            }
            readyMarker = marker;
        }

        private void requestTpsExport() {
            server.execute(() -> {
                EventExporter current = exporter;
                if (current != null && running == this) {
                    current.exportTps(tps.tps(System.nanoTime()), server.getPlayerList().getPlayers().size());
                }
            });
        }

        private long overworldTick() {
            return Math.max(0, Compat.fullTime(server.overworld()));
        }

        void disable() {
            for (Object listener : listeners) {
                NeoForge.EVENT_BUS.unregister(listener);
            }
            listeners.clear();
            timers.clear();
            if (tpsTimer != null) {
                tpsTimer.shutdownNow();
            }
            if (speech != null) {
                speech.close();
            }
            if (blockOwner != null) {
                blockOwner.close();
            }
            if (hearingSink != null) {
                hearingSink.close();
            }
            if (publisher != null) {
                publisher.close();
            }
            if (readyMarker != null) {
                try {
                    readyMarker.remove();
                } catch (IOException e) {
                    log.warning("cannot remove " + READY_FILE + ": " + e.getMessage());
                }
            }
        }
    }
}
