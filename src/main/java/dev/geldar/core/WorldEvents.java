package dev.geldar.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Reads the game's damage, death and sleep events for the {@link EventExporter}. Every handler runs last
 * ({@code LOWEST}) and changes nothing. A handler never throws into the server thread.
 *
 * <p>Paper tells a death and its drops in one event. Here the death comes first and the drops follow in the same tick,
 * so a death is held until its drops arrive; one whose drops never come (a spectator drops nothing) goes out when the
 * tick ends.
 */
public final class WorldEvents {
    private final EventExporter exporter;
    private final Logger log;
    private final Map<UUID, Death> pending = new HashMap<>();

    public WorldEvents(EventExporter exporter, Logger log) {
        this.exporter = exporter;
        this.log = log;
    }

    private record Death(String player, String cause, EventExporter.Place at, String dimension,
            EventExporter.Place respawn) {}

    /** The Bukkit cause name of a damage source. */
    static String cause(DamageSource source) {
        return DamageCauses.name(Compat.damageType(source), source.getEntity() != null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            exporter.damage(Compat.name(player), cause(event.getSource()), event.getHealthDamage());
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not export damage event", e);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String name = Compat.name(player);
        String cause = null;
        EventExporter.Place at = null;
        String dimension = null;
        EventExporter.Place respawn = null;
        try {
            cause = cause(event.getSource());
        } catch (RuntimeException e) {
            // The cause reads as UNKNOWN; a listener must never drop the death event.
        }
        try {
            BlockPos pos = player.blockPosition();
            at = new EventExporter.Place(pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException e) {
            // Coordinates are optional.
        }
        try {
            dimension = Worlds.word(Compat.dimension(player.level()));
        } catch (RuntimeException e) {
            // The dimension is optional, as the coordinates are.
        }
        try {
            BlockPos spawn = Compat.respawn(player);
            respawn = new EventExporter.Place(spawn.getX(), spawn.getY(), spawn.getZ());
        } catch (RuntimeException e) {
            // The respawn point is optional, as the death coordinates are.
        }
        pending.put(player.getUUID(), new Death(name, cause, at, dimension, respawn));
    }

    /** The drops of a death held above. A cancelled drop leaves nothing on the ground, so it tells no drops. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Death death = pending.remove(player.getUUID());
        if (death == null) {
            return;
        }
        List<EventExporter.Drop> drops = new ArrayList<>();
        try {
            if (!event.isCanceled()) {
                for (ItemEntity entity : event.getDrops()) {
                    ItemStack stack = entity.getItem();
                    if (!stack.isEmpty()) {
                        drops.add(new EventExporter.Drop(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount()));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not read the drops of a death", e);
        }
        send(death, drops);
    }

    @SubscribeEvent
    public void onTickEnd(ServerTickEvent.Post event) {
        if (pending.isEmpty()) {
            return;
        }
        List<Death> left = new ArrayList<>(pending.values());
        pending.clear();
        for (Death death : left) {
            send(death, List.of());
        }
    }

    private void send(Death death, List<EventExporter.Drop> drops) {
        exporter.death(death.player(), death.cause(), death.at(), death.dimension(), drops, death.respawn());
    }

    /** A player got into a bed: nothing refused it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSleep(CanPlayerSleepEvent event) {
        if (event.getProblem() == null) {
            exporter.sleep(Compat.name(event.getEntity()));
        }
    }
}
