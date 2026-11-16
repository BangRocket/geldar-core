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
 * <p>The record is state, not history: one entry for each standing block a player (or the system) placed, stored with
 * its chunk and saved and loaded with it. An entry names the block that was placed. When it is read and another block
 * stands there (an explosion, a piston, a fluid: changes no player event tells), the entry is dropped, so a record can
 * outlive its block only until the next read.
 *
 * <p>Only a loaded chunk is read; a chunk is never loaded to answer. A world brought over from Paper starts with an
 * empty ledger: what CoreProtect knew does not come with it.
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

    /** The placed blocks of one chunk. Server thread only. */
    static final class ChunkOwners {
        static final MapCodec<ChunkOwners> CODEC = Entry.CODEC.listOf().fieldOf("placed")
                .xmap(ChunkOwners::new, owners -> List.copyOf(owners.byPos.values()));

        final Map<Long, Entry> byPos = new HashMap<>();

        ChunkOwners() {}

        ChunkOwners(List<Entry> entries) {
            for (Entry entry : entries) {
                byPos.put(entry.pos(), entry);
            }
        }
    }

    private static final Supplier<AttachmentType<ChunkOwners>> OWNERS = TYPES.register("block_owners",
            () -> AttachmentType.builder(() -> new ChunkOwners())
                    .serialize(ChunkOwners.CODEC, owners -> !owners.byPos.isEmpty())
                    .build());

    private final Host host;

    public OwnerLedger(Host host) {
        this.host = host;
    }

    /** Registers the chunk record with the game; call once, when the mod is constructed. */
    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** A player placed a block. Server thread only. */
    public void placed(ServerLevel level, BlockPos pos, String owner, String block) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        chunk.getData(OWNERS).byPos.put(pos.asLong(), new Entry(pos.asLong(), owner, block));
        chunk.markUnsaved();
    }

    /** The block at a position is gone, or was placed by no player. Server thread only. */
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
     * The standing placed blocks of one chunk, as place rows. Called from the lookup thread, so it hops to the server
     * thread to read the chunk and waits for the answer; the query service above bounds the wait.
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
            if (!entry.block().equals(blockName(level, pos))) {
                entries.remove();
                dropped = true;
                continue;
            }
            rows.add(new LogRow(world, pos.getX(), pos.getY(), pos.getZ(), true, entry.owner(), false, entry.block()));
        }
        if (dropped) {
            chunk.markUnsaved();
        }
        return rows;
    }

    /** Records one placement by {@code user}; {@code material} is a block name in either case. Server thread only. */
    @Override
    public void logPlacement(String user, String world, int x, int y, int z, String material) {
        placed(level(world), new BlockPos(x, y, z), user, material.toLowerCase(Locale.ROOT));
    }

    /** The block's name without its namespace, such as {@code stone_bricks}; a mod's block keeps its namespace. */
    public static String blockName(ServerLevel level, BlockPos pos) {
        String id = Compat.blockId(level.getBlockState(pos));
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private ServerLevel level(String world) {
        for (ServerLevel level : host.server().getAllLevels()) {
            if (host.worldName(level).equals(world)) {
                return level;
            }
        }
        throw new IllegalStateException("world is not loaded: " + world);
    }
}
