package dev.geldar.core.proximity;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Death is announced only to the players within {@link ProximityRule#HEARING_RADIUS_BLOCKS} of the deceased (spec
 * 5.9). The line goes to those players, and to the console for the log. The death itself is not touched, so the death
 * export and the gravestone listener are unchanged.
 *
 * <p>Paper lets a handler clear the server-wide broadcast of one death. This server has no such hook, so the mod turns
 * the {@code show_death_messages} game rule off when it starts, and this listener is then the only one that announces.
 */
public final class ProximityDeathListener {
    private final Supplier<Collection<ServerPlayer>> online;
    private final Consumer<Component> console;
    private final Logger log;

    /**
     * @param online the players on the server now
     * @param console receives the line for the server log
     */
    public ProximityDeathListener(
            Supplier<Collection<ServerPlayer>> online, Consumer<Component> console, Logger log) {
        this.online = online;
        this.console = console;
        this.log = log;
    }

    /** Runs last; a death another mod cancelled did not happen and is not seen here. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer deceased)) {
            return;
        }
        Component message;
        try {
            message = deceased.getCombatTracker().getDeathMessage();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "death message not built", e);
            return;
        }
        for (ServerPlayer player : online.get()) {
            if (Hearing.between(deceased, player)) {
                send(player, message);
            }
        }
        try {
            console.accept(message);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "death message not logged to the console", e);
        }
    }

    private void send(ServerPlayer player, Component message) {
        try {
            player.sendSystemMessage(message);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "death message not delivered to a nearby player", e);
        }
    }
}
