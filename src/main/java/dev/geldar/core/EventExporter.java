package dev.geldar.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns Phase 0 world events (damage, death, sleep, server_tps) into envelopes and hands them to the
 * publisher. Nothing here may throw into the server thread: every failure is logged and swallowed. It holds no game
 * types: {@link WorldEvents} reads the game's events and calls in with plain values.
 */
public final class EventExporter {
    private final EventPublisher publisher;
    private final String shard;
    private final LongSupplier worldTick;
    private final Supplier<Instant> clock;
    private final Logger log;

    /** Creates the exporter; {@code worldTick} and {@code clock} are injected so tests need no server. */
    public EventExporter(
            EventPublisher publisher, String shard, LongSupplier worldTick, Supplier<Instant> clock, Logger log) {
        this.publisher = publisher;
        this.shard = shard;
        this.worldTick = worldTick;
        this.clock = clock;
        this.log = log;
    }

    /** Builds and publishes one event. {@code playerName} becomes {@code agent_id} when it is a life id. */
    public void emit(String type, String playerName, JsonObject payload) {
        try {
            if (playerName != null) {
                payload.addProperty("player", playerName);
            }
            String agentId = EventEnvelope.isLifeId(playerName) ? playerName : null;
            publisher.publish(EventEnvelope.create(clock.get(), shard, worldTick.getAsLong(), type, agentId, payload));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not export " + type + " event", e);
        }
    }

    /**
     * Called by the timer every 20 s with the one-minute-window TPS figure. The payload also tells
     * how many events the publisher has dropped and whether it is connected, so a silent outage shows.
     */
    public void exportTps(double tps, int players) {
        JsonObject p = new JsonObject();
        p.addProperty("tps", tps);
        p.addProperty("players", players);
        p.addProperty("dropped", publisher.dropped());
        p.addProperty("connected", publisher.connected());
        emit("server_tps", null, p);
    }

    /** Exports damage a player took: the Bukkit cause name and the health it cost. */
    public void damage(String player, String cause, double amount) {
        JsonObject p = new JsonObject();
        p.addProperty("cause", cause);
        p.addProperty("amount", amount);
        emit("damage", player, p);
    }

    /** One block position. */
    public record Place(int x, int y, int z) {}

    /** One dropped stack: its item name (the id without the namespace) and its count. */
    public record Drop(String item, int count) {}

    /**
     * Exports a player death: the last damage cause, the place and dimension, each dropped stack, the respawn point.
     * {@code cause} null reads as UNKNOWN; {@code at}, {@code dimension} and {@code respawn} are optional and left out
     * when null.
     */
    public void death(String player, String cause, Place at, String dimension, List<Drop> drops, Place respawn) {
        JsonObject p = new JsonObject();
        p.addProperty("cause", cause == null ? "UNKNOWN" : cause);
        if (at != null) {
            p.addProperty("x", at.x());
            p.addProperty("y", at.y());
            p.addProperty("z", at.z());
        }
        if (dimension != null) {
            p.addProperty("dimension", dimension);
        }
        addDrops(p, drops, at);
        if (respawn != null) {
            p.addProperty("respawn_x", respawn.x());
            p.addProperty("respawn_y", respawn.y());
            p.addProperty("respawn_z", respawn.z());
        }
        emit("death", player, p);
    }

    /**
     * One {@code drop_items} record per dropped stack, each with the death block where the stack falls; {@code drops}
     * is the total count. Nothing is added when no item dropped.
     */
    static void addDrops(JsonObject p, List<Drop> drops, Place at) {
        JsonArray items = new JsonArray();
        int total = 0;
        for (Drop drop : drops) {
            if (drop.count() <= 0) {
                continue;
            }
            total += drop.count();
            JsonObject d = new JsonObject();
            d.addProperty("item", drop.item());
            d.addProperty("count", drop.count());
            if (at != null) {
                d.addProperty("x", at.x());
                d.addProperty("y", at.y());
                d.addProperty("z", at.z());
            }
            items.add(d);
        }
        if (total > 0) {
            p.addProperty("drops", total);
            p.add("drop_items", items);
        }
    }

    /** Exports a player getting into a bed; refused attempts (monsters near, wrong time) are not sleep. */
    public void sleep(String player) {
        emit("sleep", player, new JsonObject());
    }
}
