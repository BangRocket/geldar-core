package dev.geldar.core;

import dev.geldar.core.blockowner.PlacementRecorder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Places a gravestone (a stone-brick block with a standing sign) near where an agent character dies.
 * It holds no items and touches no drops, XP, inventory or respawn (decision 0147). Ordinary blocks:
 * players can break them. Config: {@code gravestones.enabled}, {@code gravestones.max_per_chunk}.
 * Each gravestone's two blocks are recorded as owned by the system, so the Body does not dig them (decision 0195).
 */
public final class GravestoneListener {
    static final String AGENT_PREFIX = "ag_";
    /** The material names of the two blocks, as the owner record takes them. */
    static final String STONE_BRICKS = "STONE_BRICKS";
    static final String OAK_SIGN = "OAK_SIGN";
    /** Gravestones stand only in the overworld. */
    static final String OVERWORLD = "minecraft:overworld";

    private final boolean enabled;
    private final int maxPerChunk;
    private final Logger log;
    private final PlacementRecorder placements;
    private final Function<net.minecraft.world.level.Level, String> worldName;
    /** Gravestones placed this run per chunk; not persisted, so a restart only makes the cap looser. */
    private final Map<String, Integer> perChunk = new HashMap<>();

    public GravestoneListener(boolean enabled, int maxPerChunk, Logger log, PlacementRecorder placements,
            Function<net.minecraft.world.level.Level, String> worldName) {
        this.enabled = enabled;
        this.maxPerChunk = maxPerChunk;
        this.log = log;
        this.placements = placements;
        this.worldName = worldName;
    }

    /**
     * Records a gravestone whose stone block is at (x, y, z) and whose sign is above it, as owned by the system.
     * Shared by the death placement and the console backfill, so both record the same two blocks.
     */
    static void recordGrave(PlacementRecorder placements, String world, int x, int y, int z) {
        placements.placedBySystem(world, x, y, z, STONE_BRICKS);
        placements.placedBySystem(world, x, y + 1, z, OAK_SIGN);
    }

    /** Runs last; a death another mod cancelled did not happen and is not seen here. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!enabled) {
            log.fine("gravestone skipped: gravestones are disabled");
            return;
        }
        if (!Compat.name(player).startsWith(AGENT_PREFIX)) {
            return;
        }
        try {
            place(player, WorldEvents.cause(event.getSource()));
        } catch (RuntimeException e) {
            // A gravestone must never disturb a death.
            log.log(Level.WARNING, "gravestone not placed", e);
        }
    }

    private void place(ServerPlayer player, String cause) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        String name = Compat.name(player);
        String dimension = Compat.dimension(level);
        if (!OVERWORLD.equals(dimension)) {
            log.fine("gravestone skipped: " + name + " died in " + dimension + ", not the overworld");
            return;
        }
        String world = worldName.apply(level);
        BlockPos at = player.blockPosition();
        Optional<Gravestone.Spot> spot = Gravestone.findSpot(new WorldGrid(level), at.getX(), at.getY(), at.getZ());
        if (spot.isEmpty()) {
            log.fine("gravestone skipped: no safe spot within " + Gravestone.RADIUS + " blocks of " + at.getX() + ","
                    + at.getY() + "," + at.getZ() + " in " + world + " for " + name);
            return;
        }
        Gravestone.Spot s = spot.get();
        String chunk = world + ":" + (s.x() >> 4) + ":" + (s.z() >> 4);
        if (perChunk.getOrDefault(chunk, 0) >= maxPerChunk) {
            log.fine("gravestone skipped: chunk " + chunk + " is at its cap");
            return;
        }
        String[] lines = Gravestone.lines(name, Gravestone.dayOf(Compat.fullTime(level)), Gravestone.causeWord(cause));
        BlockPos stone = new BlockPos(s.x(), s.y(), s.z());
        BlockPos signAt = stone.above();
        level.setBlockAndUpdate(stone, Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(signAt, Blocks.OAK_SIGN.defaultBlockState());
        if (level.getBlockEntity(signAt) instanceof SignBlockEntity sign) {
            sign.updateText(text -> {
                SignText written = text;
                for (int i = 0; i < lines.length; i++) {
                    written = written.setMessage(i, Component.literal(lines[i]));
                }
                return written;
            }, true);
        }
        recordGrave(placements, world, s.x(), s.y(), s.z());
        perChunk.merge(chunk, 1, Integer::sum);
    }

    /** The live world seen through the pure search's grid. */
    private record WorldGrid(ServerLevel level) implements Gravestone.Grid {
        @Override
        public boolean isAir(int x, int y, int z) {
            return y >= Compat.minY(level) && y < Compat.maxY(level) && level.getBlockState(new BlockPos(x, y, z)).isAir();
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return y >= Compat.minY(level) && level.getBlockState(new BlockPos(x, y, z)).isSolid();
        }

        @Override
        public boolean isLiquid(int x, int y, int z) {
            return y >= Compat.minY(level) && !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
        }
    }
}
