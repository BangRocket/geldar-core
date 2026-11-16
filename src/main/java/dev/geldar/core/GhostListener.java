package dev.geldar.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Ghost enforcement (spec 8.4, decisions 0092 and 0137). The server fails closed: a joiner whose forwarded
 * profile lacks {@link #LIVE_PROPERTY} (set by the bot-ingress Velocity only for a live life, inside the
 * signed forwarding data) is a ghost and is held in spectator at the first join event, before any other
 * handler runs. A ghost is held in spectator on join, respawn and world change, any game-mode change to something
 * else is cancelled, and its chat is dropped (see {@link GeldarCore}), so no agent can ever hear a watcher (decisions
 * 0059, 0068). If spectator cannot be applied the player is kicked.
 *
 * <p>The Paper plugin also read Velocity's message on the {@code geldar:ghost} channel, a second path that could only
 * add a ghost. It is not read here: NeoForge disconnects a connection that sends a registered channel it did not
 * negotiate, and discards one that is not registered, so the message is discarded. The profile property decides alone.
 */
public final class GhostListener {
    /** The forwarded profile property that marks a live login. Must equal IdentityPlugin.LIVE_PROPERTY. */
    public static final String LIVE_PROPERTY = "geldar_live";
    /** Shown on a kick; says nothing about why (Principle 1). */
    static final String KICK_MESSAGE = "This connection is not admitted.";

    private final Set<UUID> ghosts = ConcurrentHashMap.newKeySet();
    private final Logger log;

    public GhostListener(Logger log) {
        this.log = log;
    }

    public boolean isGhost(ServerPlayer player) {
        return ghosts.contains(player.getUUID());
    }

    /** First handler to run on a join: a joiner without the live property is a ghost from this moment. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!hasLiveProperty(player)) {
            ghosts.add(player.getUUID());
        }
        if (isGhost(player)) {
            hold(player);
        }
    }

    @SubscribeEvent
    public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        ghosts.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isGhost(player)) {
            hold(player);
        }
    }

    @SubscribeEvent
    public void onWorldChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isGhost(player)) {
            hold(player);
        }
    }

    /** Runs last and also sees a change another mod cancelled, so nothing can let a ghost out of spectator after it. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isGhost(player)
                && event.getNewGameMode() != GameType.SPECTATOR) {
            event.setCanceled(true);
        }
    }

    /** Absent, unreadable or any other value counts as "not live". */
    private boolean hasLiveProperty(ServerPlayer player) {
        try {
            return Compat.hasProperty(player, LIVE_PROPERTY);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the forwarded profile; treating the player as a ghost", e);
            return false;
        }
    }

    private void hold(ServerPlayer player) {
        try {
            if (player.gameMode() != GameType.SPECTATOR && !player.setGameMode(GameType.SPECTATOR)) {
                throw new IllegalStateException("the game refused spectator");
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not hold a ghost in spectator; kicking", e);
            try {
                player.connection.disconnect(Component.literal(KICK_MESSAGE));
            } catch (RuntimeException kickFailure) {
                log.log(Level.SEVERE, "could not kick a ghost that is not in spectator", kickFailure);
            }
        }
    }
}
