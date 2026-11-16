package dev.geldar.core.speech;

import de.bluecolored.bluemap.api.BlueMapAPI;
import dev.geldar.core.Compat;
import dev.geldar.core.EventEnvelope;
import dev.geldar.core.Host;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.neoforged.fml.ModList;

/**
 * Turns on the live chat feed (decision 0310) when BlueMap is installed: the periodic writer of the feed file into
 * the BlueMap web root, off the server thread. It replaces the speech bubbles (decision 0197). Without BlueMap
 * {@link #register} gives null. It never throws; a missing BlueMap class or any other failure leaves the feature off
 * and logs why.
 */
public final class SpeechSetup implements AutoCloseable {
    /** How often the feed file is written if it changed, and the clock sampled, in ticks (one second). */
    static final int FLUSH_TICKS = 20;
    static final String BLUEMAP = "bluemap";

    private final ChatFeedWriter feed;
    private final ScheduledExecutorService writer;
    private final Logger log;
    private final AtomicBoolean warned = new AtomicBoolean();

    private SpeechSetup(ChatFeedWriter feed, ScheduledExecutorService writer, Logger log) {
        this.feed = feed;
        this.writer = writer;
        this.log = log;
    }

    /** The running feed, or null when BlueMap is absent or the feed could not start. */
    public static SpeechSetup register(Host host) {
        Logger log = host.log();
        try {
            if (!ModList.get().isLoaded(BLUEMAP)) {
                log.info("BlueMap is not installed; the chat feed is off");
                return null;
            }
            ChatFeedWriter feed = new ChatFeedWriter(log);
            BlueMapAPI.onEnable(feed::attach);
            BlueMapAPI.onDisable(api -> feed.detach());
            ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "geldar-chat-feed");
                t.setDaemon(true);
                return t;
            });
            writer.scheduleWithFixedDelay(feed::flushIfDirty, 1, 1, TimeUnit.SECONDS);
            // Sampled on the server thread, so the writer thread never reads the game.
            host.every(FLUSH_TICKS, () -> feed.sampleClock(
                    Compat.fullTime(host.server().overworld()), System.currentTimeMillis()));
            return new SpeechSetup(feed, writer, log);
        } catch (LinkageError e) {
            log.warning("BlueMap API is not on the classpath; the chat feed is off");
        } catch (RuntimeException e) {
            log.warning("chat feed is off: " + e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Passes one chat line that stood (a ghost's is dropped before this) to the feed. Players that are not agents are
     * ignored. The feed is thread-safe and the line is recorded at once. This never throws: a failure is logged once.
     */
    public void said(String player, UUID id, String text) {
        try {
            if (EventEnvelope.isLifeId(player)) {
                feed.say(id, text, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            if (warned.compareAndSet(false, true)) {
                log.log(Level.FINE, "chat feed line was not recorded; later failures are not logged", e);
            }
        }
    }

    @Override
    public void close() {
        writer.shutdownNow();
    }
}
