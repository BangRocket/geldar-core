package dev.geldar.core.maintenance;

import dev.geldar.core.Host;
import dev.geldar.core.Settings;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/**
 * Wires the maintenance broadcast. {@code maintenance.broadcast} (default true) is the switch: the mod re-reads
 * its settings every 5 s and {@link #reload} applies it, so it can be turned off without a restart. The NATS subscriber
 * and its connection exist either way; with the switch off no line is said.
 */
public final class MaintenanceSetup {
    private MaintenanceSetup() {}

    public static boolean enabled(Settings file) {
        return file.getBoolean("maintenance.broadcast", true);
    }

    /** The settings reload hook: applies {@code maintenance.broadcast} to the running broadcaster. Server thread. */
    public static void reload(MaintenanceNotices notices, Settings file) {
        notices.setEnabled(enabled(file));
    }

    public static MaintenanceNotices register(
            Host host, Settings file, String natsUrl, String shard, Function<String, String> env) {
        MaintenanceNotices notices = new MaintenanceNotices(
                enabled(file),
                text -> host.server().getPlayerList().broadcastSystemMessage(Component.literal(text), false),
                host::run,
                System::currentTimeMillis,
                host.log());
        new NatsMaintenanceSubscriber(natsUrl, shard, env, host.log(), notices);
        host.log().info("Maintenance notices enabled: broadcast " + enabled(file));
        return notices;
    }
}
