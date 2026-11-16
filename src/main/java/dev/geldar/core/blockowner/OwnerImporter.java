package dev.geldar.core.blockowner;

import dev.geldar.core.Host;
import dev.geldar.core.blockowner.OwnerCache.ChunkKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Brings a Paper shard's ownership history across: the owners exported from its CoreProtect database are laid into the
 * ledger, each chunk's share once, when the chunk is loaded. Nothing is loaded to do it.
 *
 * <p>{@code geldar-import-owners <file>} reads the export and keeps it with the world, as {@link #STORE}, until every
 * chunk it names has taken its share; a restart picks it up again. A position that already has an owner on this server
 * keeps it, as a newer change, and a position whose cell is empty takes nothing. A chunk takes imported history once:
 * a second import does not reach a chunk that took the first.
 */
public final class OwnerImporter {
    /** Where the export is kept with the world until every chunk has taken its share. */
    static final String STORE = "geldar-core/owner-import.csv";

    private final Host host;
    private final OwnerLedger ledger;
    private final Logger log;
    private final Map<ChunkKey, List<OwnerImport.Row>> pending = new ConcurrentHashMap<>();
    private final Queue<ChunkKey> loaded = new ConcurrentLinkedQueue<>();

    OwnerImporter(Host host, OwnerLedger ledger) {
        this.host = host;
        this.ledger = ledger;
        this.log = host.log();
    }

    private Path store() {
        return host.server().getWorldPath(LevelResource.ROOT).resolve(STORE);
    }

    /** Picks up an export kept from before a restart and lays it into the chunks already loaded. Server thread. */
    void start() {
        Path store = store();
        if (!Files.exists(store)) {
            return;
        }
        try {
            OwnerImport.Parsed kept = OwnerImport.read(store);
            pending.putAll(kept.chunks());
            Applied applied = applyLoaded();
            log.info("Owner import: " + kept.rows() + " owned block(s) in " + kept.chunks().size()
                    + " chunk(s) wait to be laid in; " + applied.blocks() + " were laid into " + applied.chunks()
                    + " loaded chunk(s)");
        } catch (IOException | RuntimeException e) {
            log.warning("Owner import: " + store + " could not be read, so its owners are not laid in: " + e.getMessage());
        }
    }

    /** Keeps what is left for the next start, or removes the file once nothing is left. Server thread. */
    void stop() {
        Path store = store();
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(store);
            } else {
                OwnerImport.write(store, pending);
            }
        } catch (IOException e) {
            log.warning("Owner import: " + store + " could not be updated: " + e.getMessage());
        }
    }

    /** A chunk the export names has loaded; its share is laid in at the end of the tick. Any thread. */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (pending.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var pos = event.getChunk().getPos();
        ChunkKey key = new ChunkKey(host.worldName(level), pos.x(), pos.z());
        if (pending.containsKey(key)) {
            loaded.add(key);
        }
    }

    /** Lays in the shares of the chunks loaded since the last tick. Server thread. */
    void applyQueued() {
        for (ChunkKey key = loaded.poll(); key != null; key = loaded.poll()) {
            apply(key);
        }
    }

    /** What one laying-in did: blocks added and chunks that took them. */
    record Applied(int blocks, int chunks) {}

    /** Lays in one chunk's share if the chunk is loaded; the share waits otherwise. Server thread. */
    private int apply(ChunkKey key) {
        ServerLevel level = ledger.levelOrNull(key.world());
        LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
        if (chunk == null) {
            return -1;
        }
        List<OwnerImport.Row> rows = pending.remove(key);
        return rows == null ? -1 : Math.max(0, ledger.importInto(chunk, rows));
    }

    private Applied applyLoaded() {
        int blocks = 0;
        int chunks = 0;
        for (ChunkKey key : new ArrayList<>(pending.keySet())) {
            int added = apply(key);
            if (added >= 0) {
                blocks += added;
                chunks++;
            }
        }
        return new Applied(blocks, chunks);
    }

    /**
     * Imports an export: keeps it with the world in place of any earlier one, and lays it into the chunks that are
     * loaded now. Returns the line the command answers with. Server thread.
     */
    String importFile(Path file) {
        OwnerImport.Parsed parsed;
        try {
            parsed = OwnerImport.read(file);
        } catch (IOException e) {
            return "geldar-import-owners: " + file + " was not imported: " + e.getMessage();
        }
        pending.clear();
        loaded.clear();
        pending.putAll(parsed.chunks());
        try {
            OwnerImport.write(store(), pending);
        } catch (IOException e) {
            return "geldar-import-owners: the owners could not be kept with the world (" + e.getMessage()
                    + "); nothing was imported.";
        }
        long unknownWorlds = pending.keySet().stream().map(ChunkKey::world).distinct()
                .filter(world -> ledger.levelOrNull(world) == null).count();
        Applied applied = applyLoaded();
        StringBuilder reply = new StringBuilder("Imported " + parsed.rows() + " owned block(s) in "
                + parsed.chunks().size() + " chunk(s). " + applied.blocks() + " were laid into " + applied.chunks()
                + " loaded chunk(s); the rest go in as their chunks load.");
        if (unknownWorlds > 0) {
            reply.append(" ").append(unknownWorlds).append(" world name(s) match no world here; their rows wait.");
        }
        if (parsed.skipped() > 0) {
            reply.append(" Skipped ").append(parsed.skipped()).append(" line(s): ")
                    .append(String.join("; ", parsed.problems())).append(".");
        }
        log.info(reply.toString());
        return reply.toString();
    }
}
