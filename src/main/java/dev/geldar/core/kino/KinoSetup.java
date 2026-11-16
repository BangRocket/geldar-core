package dev.geldar.core.kino;

import dev.geldar.core.Host;
import dev.geldar.core.Settings;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;

/**
 * Wires the Kino feed. {@link #enabled} is the kill switch: with {@code kino.enabled} false (the default) the
 * feed is registered but killed: it publishes nothing and starts no target, while the NATS connections, the
 * listeners and the timers still exist (finding 12 of round 1: registering only on first enable is left open).
 * Targets are started and stopped through the returned feed (the relay of K02 does
 * that); this class adds only read-only listeners and a read-only tick.
 */
public final class KinoSetup {
    private KinoSetup() {}

    public static boolean enabled(Settings file) {
        return file.getBoolean("kino.enabled", false);
    }

    public static int radius(Settings file) {
        return Math.max(1, Math.min(8, file.getInt("kino.radius_chunks", KinoFeed.DEFAULT_RADIUS_CHUNKS)));
    }

    /** The settings reload hook: applies {@code kino.enabled} to a running feed. */
    public static void reload(KinoFeed feed, Settings file) {
        feed.setEnabled(enabled(file));
    }

    public static KinoFeed register(
            Host host, Settings file, String natsUrl, String shard, Function<String, String> env) {
        NatsKinoSink sink = new NatsKinoSink(natsUrl, shard, env, host.log());
        KinoFeed[] holder = new KinoFeed[1];
        ServerLevel overworld = host.server().overworld();
        KinoFeed feed = new KinoFeed(new LevelKinoWorld(overworld, host.worldName(overworld),
                name -> holder[0] != null && holder[0].isTarget(name)), sink, radius(file));
        holder[0] = feed;
        host.listen(new KinoBlockListener(host, feed));
        host.every(20, feed::tick);
        host.every(1, feed::pump);
        // Glass asks for targets over NATS; the commands are applied here on the server thread.
        KinoControl control = new KinoControl(feed, System::currentTimeMillis);
        new NatsKinoControl(natsUrl, shard, env, host.log(), control);
        host.every(10, control::drain);
        host.log().info("Kino feed enabled: radius " + radius(file) + " chunks");
        return feed;
    }
}
