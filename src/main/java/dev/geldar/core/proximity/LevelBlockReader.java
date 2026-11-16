package dev.geldar.core.proximity;

import dev.geldar.core.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The game side of {@link BlockReader}. It is used only on the server thread. It reads the block already in a loaded
 * chunk and never loads one: an unloaded chunk throws {@link UnloadedBlock}. Solid is a block one collides with where it
 * stands; open is the open state of a door, trapdoor or fence gate. Cells outside the world read as air.
 */
public final class LevelBlockReader implements BlockReader {
    private static final BlockProbe AIR = new BlockProbe("minecraft:air", false, false);

    private final ServerLevel level;

    public LevelBlockReader(ServerLevel level) {
        this.level = level;
    }

    @Override
    public BlockProbe at(int x, int y, int z) {
        if (y < Compat.minY(level) || y >= Compat.maxY(level)) {
            return AIR;
        }
        if (!level.hasChunk(x >> 4, z >> 4)) {
            throw new UnloadedBlock();
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        boolean solid = !state.getCollisionShape(level, pos).isEmpty();
        return new BlockProbe(Compat.blockId(state), solid, open);
    }

    @Override
    public int buildLimit() {
        return Compat.maxY(level);
    }
}
