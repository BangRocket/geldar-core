package dev.geldar.core.lakitu;

import dev.geldar.core.Compat;
import dev.geldar.core.Host;

/** Wires the feed when {@code lakitu.enabled} is true. It registers read-only listeners and nothing else. */
public final class LakituFeed {
    private LakituFeed() {}

    /** Registers the block-update listener and returns the queue the frame builder drains. */
    public static UpdateQueue register(Host host, LakituConfig config) {
        UpdateQueue queue = new UpdateQueue(config.maxUpdatesPerFrame());
        host.listen(new BlockUpdateListener(host, queue,
                () -> Math.max(0, Compat.fullTime(host.server().overworld()))));
        host.log().info("Lakitu feed enabled: radius " + config.radiusChunks() + " chunks, "
                + config.maxPanesPerSession() + " panes per session, " + config.frameHz() + " Hz");
        return queue;
    }
}
