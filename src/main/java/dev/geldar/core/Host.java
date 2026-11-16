package dev.geldar.core;

import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** What a feature needs from the running mod to wire itself: the server, the log, the settings and the timers. */
public interface Host {
    MinecraftServer server();

    Logger log();

    /** The settings as last read from the file; the file is read again every five seconds. */
    Settings settings();

    /** Runs {@code task} on the server thread every {@code ticks} ticks, until the server stops. */
    void every(int ticks, Runnable task);

    /** Runs {@code task} on the server thread soon. Safe from any thread. */
    void run(Runnable task);

    /** Registers an object's event handlers until the server stops. */
    void listen(Object listener);

    /** The world's name as Paper would give it; see {@link Worlds}. */
    String worldName(Level level);
}
