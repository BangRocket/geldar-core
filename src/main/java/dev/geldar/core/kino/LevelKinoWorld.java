package dev.geldar.core.kino;

import dev.geldar.core.Compat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** {@link KinoWorld} over a server level. It calls getters only, on the server thread. */
public final class LevelKinoWorld implements KinoWorld {
    private final ServerLevel level;
    private final String name;
    private final Predicate<String> isAgent;

    /**
     * @param name the world's name as Paper would give it
     * @param isAgent says whether a player name is an agent; other players carry no agent id
     */
    public LevelKinoWorld(ServerLevel level, String name, Predicate<String> isAgent) {
        this.level = level;
        this.name = name;
        this.isAgent = isAgent;
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
    public String blockState(int x, int y, int z) {
        return BlockStateParser.serialize(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public Optional<Position> agentPosition(String agentId) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayerByName(agentId);
        if (p == null || p.level() != level) {
            return Optional.empty();
        }
        return Optional.of(new Position(p.getX(), p.getY(), p.getZ()));
    }

    @Override
    public ChunkEntities entitiesInChunk(int chunkX, int chunkZ, int from, int max) {
        if (max <= 0 || !level.hasChunk(chunkX, chunkZ)) {
            return new ChunkEntities(0, List.of());
        }
        // The chunk is loaded (checked above); a box query reads only entities that exist, it loads nothing. The
        // box also returns entities that straddle the border, so keep only those whose position is in this chunk.
        AABB box = new AABB(chunkX * 16.0, Compat.minY(level), chunkZ * 16.0,
                chunkX * 16.0 + 16.0, Compat.maxY(level), chunkZ * 16.0 + 16.0);
        List<Entity> eligible = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, any -> true)) {
            if (Math.floorDiv(e.getBlockX(), 16) != chunkX || Math.floorDiv(e.getBlockZ(), 16) != chunkZ) {
                continue;
            }
            String player = e instanceof ServerPlayer p ? Compat.name(p) : null;
            if (KinoEntityPolicy.include(Compat.entityId(e), player, isAgent)) {
                eligible.add(e);
            }
        }
        // Sorted by entity id so a page offset means the same entities on the next call.
        List<Entity> ordered = KinoEntityPolicy.orderedDistinct(eligible, Entity::getId);
        int end = Math.min(ordered.size(), Math.max(0, from) + max);
        List<EntityView> out = new ArrayList<>();
        // Positions are read only for the page (at most max), never for the whole chunk.
        for (int i = Math.max(0, from); i < end; i++) {
            out.add(view(ordered.get(i)));
        }
        return new ChunkEntities(ordered.size(), out);
    }

    private EntityView view(Entity e) {
        String agent = e instanceof ServerPlayer p && isAgent.test(Compat.name(p)) ? Compat.name(p) : null;
        return new EntityView(e.getId(), Compat.entityId(e), agent, e.getX(), e.getY(), e.getZ(),
                e.getYRot(), e.getXRot());
    }
}
