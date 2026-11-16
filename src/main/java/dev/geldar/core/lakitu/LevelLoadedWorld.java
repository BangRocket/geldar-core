package dev.geldar.core.lakitu;

import dev.geldar.core.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** {@link LoadedWorld} over a server level. It calls getters only. */
public final class LevelLoadedWorld implements LoadedWorld {
    private final ServerLevel level;
    private final String name;

    /** @param name the world's name as Paper would give it */
    public LevelLoadedWorld(ServerLevel level, String name) {
        this.level = level;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return level.hasChunk(chunkX, chunkZ);
    }

    @Override
    public int minSectionY() {
        return Math.floorDiv(Compat.minY(level), 16);
    }

    @Override
    public int maxSectionY() {
        return Math.floorDiv(Compat.maxY(level) - 1, 16);
    }

    @Override
    public String blockName(int x, int y, int z) {
        return Compat.blockId(level.getBlockState(new BlockPos(x, y, z)));
    }
}
