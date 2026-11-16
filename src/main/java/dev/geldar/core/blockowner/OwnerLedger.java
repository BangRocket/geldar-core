package dev.geldar.core.blockowner;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.geldar.core.Compat;
import dev.geldar.core.Host;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Who placed the blocks that stand now, kept by the mod itself. On Paper the answer came from CoreProtect's log; there
 * is no CoreProtect for this server, so the ledger stands in its place behind the same {@link CoreProtectPort}, and the
 * resolver, the cache and the query service above it are unchanged.
 *
 * <p>The ledger keeps what CoreProtect's newest row at each position would say, as Geldar configures CoreProtect
 * (block place and break, natural breaks, block movement, burning and explosions logged; growth, decay, fluids,
 * buckets and interactions not). One entry stands for each position whose newest change was a place by a player (or
 * the system): who placed it, and the block that was placed. It is stored with its chunk and saved and loaded with it.
 *
 * <p>An entry ends when a change CoreProtect logs replaces it: a player breaks the block, anything else places one
 * there, a piston moves it or an explosion takes it (see {@link BlockChangeListener}). A change CoreProtect does not
 * log leaves it standing and still naming the block that was placed: farmland that decays to dirt, copper that
 * weathers, a sapling that grows into a tree. A cell found empty (air, a fluid or fire) when the entry is read has lost
 * its block to something that took it away, so the entry is dropped then.
 *
 * <p>Only a loaded chunk is read; a chunk is never loaded to answer. A world brought over from Paper starts with an
 * empty ledger until CoreProtect's history is imported ({@link OwnerImporter}).
 */
public final class OwnerLedger implements CoreProtectPort {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "geldar_core");

    /** One placed block: its position packed as the game packs it, who placed it and the block's name. */
    record Entry(long pos, String owner, String block) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.fieldOf("owner").forGetter(Entry::owner),
                Codec.STRING.fieldOf("block").forGetter(Entry::block)).apply(i, Entry::new));
    }

    /** The placed blocks of one chunk, and whether imported history has been laid into it. Server thread only. */
    static final class ChunkOwners {
        static final MapCodec<ChunkOwners> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Entry.CODEC.listOf().fieldOf("placed").forGetter(owners -> List.copyOf(owners.byPos.values())),
                Codec.BOOL.optionalFieldOf("imported", false).forGetter(owners -> owners.imported))
                .apply(i, ChunkOwners::new));

        final Map<Long, Entry> byPos = new HashMap<>();
        boolean imported;

        ChunkOwners() {}

        ChunkOwners(List<Entry> entries, boolean imported) {
            for (Entry entry : entries) {
                byPos.put(entry.pos(), entry);
            }
            this.imported = imported;
        }
    }

    private static final Supplier<AttachmentType<ChunkOwners>> OWNERS = TYPES.register("block_owners",
            () -> AttachmentType.builder(() -> new ChunkOwners())
                    .serialize(ChunkOwners.CODEC, owners -> owners.imported || !owners.byPos.isEmpty())
                    .build());

    private final Host host;

    public OwnerLedger(Host host) {
        this.host = host;
    }

    /** Registers the chunk record with the game; call once, when the mod is constructed. */
    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** A player (or the system) placed a block. Server thread only. */
    public void placed(ServerLevel level, BlockPos pos, String owner, String block) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        chunk.getData(OWNERS).byPos.put(pos.asLong(), new Entry(pos.asLong(), owner, block));
        chunk.markUnsaved();
    }

    /** A change CoreProtect logs took the position from its placer: it has no owner now. Server thread only. */
    public void cleared(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.hasData(OWNERS)) {
            return;
        }
        if (chunk.getData(OWNERS).byPos.remove(pos.asLong()) != null) {
            chunk.markUnsaved();
        }
    }

    /**
     * Lays imported history into a chunk, once: each row becomes an entry unless the position already has one (a change
     * made on this server is newer) or its cell is empty. Returns how many entries were added, or -1 when the chunk had
     * already been given its history. Server thread only.
     */
    int importInto(LevelChunk chunk, List<OwnerImport.Row> rows) {
        ChunkOwners owners = chunk.getData(OWNERS);
        if (owners.imported) {
            return -1;
        }
        int added = 0;
        for (OwnerImport.Row row : rows) {
            BlockPos pos = new BlockPos(row.x(), row.y(), row.z());
            if (owners.byPos.containsKey(pos.asLong()) || isEmpty(chunk.getBlockState(pos))) {
                continue;
            }
            owners.byPos.put(pos.asLong(), new Entry(pos.asLong(), row.owner(), row.block()));
            added++;
        }
        owners.imported = true;
        chunk.markUnsaved();
        return added;
    }

    /**
     * The placed blocks of one chunk, as place rows. Called from the lookup thread, so it hops to the server thread to
     * read the chunk and waits for the answer; the query service above bounds the wait.
     *
     * @throws IllegalStateException when the world is unknown or the chunk is not loaded
     */
    @Override
    public List<LogRow> rows(String world, int chunkX, int chunkZ) {
        MinecraftServer server = host.server();
        if (server.isSameThread()) {
            return read(world, chunkX, chunkZ);
        }
        return server.submit(() -> read(world, chunkX, chunkZ)).join();
    }

    private List<LogRow> read(String world, int chunkX, int chunkZ) {
        ServerLevel level = level(world);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("chunk " + chunkX + "," + chunkZ + " in " + world + " is not loaded");
        }
        List<LogRow> rows = new ArrayList<>();
        if (!chunk.hasData(OWNERS)) {
            return rows;
        }
        boolean dropped = false;
        Iterator<Entry> entries = chunk.getData(OWNERS).byPos.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            BlockPos pos = BlockPos.of(entry.pos());
            if (isEmpty(level.getBlockState(pos))) {
                entries.remove();
                dropped = true;
                continue;
            }
            String block = entry.block().isEmpty() ? null : entry.block();
            rows.add(new LogRow(world, pos.getX(), pos.getY(), pos.getZ(), true, entry.owner(), false, block));
        }
        if (dropped) {
            chunk.markUnsaved();
        }
        return rows;
    }

    /** A cell whose block is gone: air, a fluid or fire. */
    static boolean isEmpty(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock || state.getBlock() instanceof BaseFireBlock;
    }

    /** Records one placement by {@code user}; {@code material} is a block name in either case. Server thread only. */
    @Override
    public void logPlacement(String user, String world, int x, int y, int z, String material) {
        placed(level(world), new BlockPos(x, y, z), user, material.toLowerCase(Locale.ROOT));
    }

    /** The block's name without its namespace, such as {@code stone_bricks}; a mod's block keeps its namespace. */
    public static String blockName(ServerLevel level, BlockPos pos) {
        return OwnerImport.blockName(Compat.blockId(level.getBlockState(pos)));
    }

    /** The loaded world of this Paper name, or null. */
    ServerLevel levelOrNull(String world) {
        for (ServerLevel level : host.server().getAllLevels()) {
            if (host.worldName(level).equals(world)) {
                return level;
            }
        }
        return null;
    }

    private ServerLevel level(String world) {
        ServerLevel level = levelOrNull(world);
        if (level == null) {
            throw new IllegalStateException("world is not loaded: " + world);
        }
        return level;
    }
}
