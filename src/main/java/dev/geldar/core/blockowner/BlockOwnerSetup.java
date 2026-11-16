package dev.geldar.core.blockowner;

import dev.geldar.core.Host;
import dev.geldar.core.Settings;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Wires block ownership: the {@link OwnerLedger} as the owner source, the lookup thread, the chunk cache, the change
 * listener, the batched change publisher and the NATS query link. Settings: {@code blockowner.enabled} (default true;
 * false stops the change events and the replies, while the ledger goes on recording) and
 * {@code blockowner.lookup_timeout_ms} (default 2000).
 */
public final class BlockOwnerSetup implements AutoCloseable {
    static final int LOOKUP_QUEUE = 32;
    static final long DEFAULT_TIMEOUT_MS = 2_000L;
    static final long MIN_TIMEOUT_MS = 100L;
    static final long MAX_TIMEOUT_MS = 60_000L;

    private final ExecutorService lookups;
    private final NatsBlockOwnerLink link;
    private final OwnerQueryService service;
    private final LookupDebugLog debug;
    private final PlacementRecorder placements;
    private final OwnerSource source;
    private volatile boolean enabled = true;

    private BlockOwnerSetup(ExecutorService lookups, NatsBlockOwnerLink link, OwnerQueryService service,
            LookupDebugLog debug, PlacementRecorder placements, OwnerSource source) {
        this.lookups = lookups;
        this.link = link;
        this.service = service;
        this.debug = debug;
        this.placements = placements;
        this.source = source;
    }

    /** Records the blocks the mod places itself (gravestones) as owned by the system (decision 0195). */
    public PlacementRecorder placements() {
        return placements;
    }

    /** The owner source the Body's queries use; the backfill reads owners through it, off the server thread. */
    public OwnerSource source() {
        return source;
    }

    /** Starts the feature; call when the server has started. The settings are read again by {@link #reload}. */
    public static BlockOwnerSetup register(Host host, String natsUrl, String shard, Function<String, String> env) {
        Logger log = host.log();
        LookupDebugLog debug = new LookupDebugLog(log, System::currentTimeMillis);
        OwnerLedger ledger = new OwnerLedger(host);
        OwnerSource source = new CoreProtectOwnerSource(ledger, debug);
        ExecutorService lookups = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(LOOKUP_QUEUE), r -> {
                    Thread t = new Thread(r, "geldar-blockowner-lookup");
                    t.setDaemon(true);
                    return t;
                });
        OwnerCache cache = new OwnerCache(System::currentTimeMillis);
        OwnerQueryService service = new OwnerQueryService(source, cache, lookups, DEFAULT_TIMEOUT_MS, log);
        service.attachDebug(debug);
        NatsBlockOwnerLink link = new NatsBlockOwnerLink(natsUrl, shard, env, log);
        BlockOwnerPublisher publisher = new BlockOwnerPublisher(link::changed, log);
        PlacementRecorder placements = new PlacementRecorder(cache, publisher, Optional.of(ledger), log);
        BlockOwnerSetup setup = new BlockOwnerSetup(lookups, link, service, debug, placements, source);
        BlockOwnerResponder responder = new BlockOwnerResponder(service, link, () -> setup.enabled);
        link.start(responder::receive);
        host.listen(new BlockChangeListener(host, ledger, cache, publisher, () -> setup.enabled));
        host.every(1, publisher::flush);
        setup.reload(host.settings());
        return setup;
    }

    /** Applies {@code blockowner.*} from the settings; the mod calls it with the 5 s reload. */
    public void reload(Settings config) {
        enabled = config.getBoolean("blockowner.enabled", true);
        long timeout = config.getLong("blockowner.lookup_timeout_ms", DEFAULT_TIMEOUT_MS);
        service.setTimeoutMs(Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeout)));
        debug.setEnabled(config.getBoolean("blockowner.debug", false));
    }

    @Override
    public void close() {
        lookups.shutdownNow();
        link.close();
    }
}
