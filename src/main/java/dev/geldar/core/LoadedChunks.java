package dev.geldar.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Which chunks are loaded now. The game does not hand out that list, so it is kept from the load and unload events.
 * The gravestone backfill walks it.
 */
public final class LoadedChunks {
    private final Map<ServerLevel, Set<Long>> loaded = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            loaded.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(key(event.getChunk()));
        }
    }

    @SubscribeEvent
    public void onUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Set<Long> chunks = loaded.get(level);
            if (chunks != null) {
                chunks.remove(key(event.getChunk()));
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            loaded.remove(level);
        }
    }

    /** The loaded chunks of every world. Server thread only. */
    public List<LevelChunk> all() {
        List<LevelChunk> chunks = new ArrayList<>();
        loaded.forEach((level, keys) -> {
            for (long key : keys) {
                LevelChunk chunk = level.getChunkSource().getChunkNow((int) key, (int) (key >> 32));
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        });
        return chunks;
    }

    private static long key(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return (pos.x() & 0xFFFFFFFFL) | ((long) pos.z() << 32);
    }
}
