package dev.geldar.core;

import dev.geldar.core.GraveBackfill.ChunkRef;
import dev.geldar.core.GraveBackfill.SignSite;
import dev.geldar.core.GraveBackfill.Tally;
import dev.geldar.core.blockowner.OwnerSource;
import dev.geldar.core.blockowner.PlacementRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code /geldar-backfill-graves}: records the gravestones already standing in loaded chunks as owned by the system
 * (decision 0195), for those placed before the recording existed, such as the ones in a world brought over from Paper.
 * Console or RCON only: a player (even an operator) cannot run it, so no human acts in the world through it. Unloaded
 * chunks are not scanned; that is an offline region scan, out of scope for decision 0195.
 *
 * <p>The signs are read on the server thread. The owner lookups run off it, one per chunk, at most
 * {@link GraveBackfill#MAX_CHUNKS} chunks. The recording and the reply run back on the server thread. A run that is still
 * going refuses a second one, so two runs cannot both see a block as unowned.
 */
final class GraveBackfillCommand {
    static final String NAME = "geldar-backfill-graves";

    private final Host host;
    private final LoadedChunks loaded;
    private final PlacementRecorder placements;
    private final OwnerSource owners;
    private final AtomicBoolean running = new AtomicBoolean();

    GraveBackfillCommand(Host host, LoadedChunks loaded, PlacementRecorder placements, OwnerSource owners) {
        this.host = host;
        this.loaded = loaded;
        this.placements = placements;
        this.owners = owners;
    }

    /** Runs the backfill for {@code sender}; {@link GeldarCore} registers the command and calls this. */
    void run(CommandSourceStack sender) {
        if (!running.compareAndSet(false, true)) {
            reply(sender, "geldar-backfill-graves is already running; wait for its reply.");
            return;
        }
        List<SignSite> sites = new ArrayList<>();
        for (LevelChunk chunk : loaded.all()) {
            for (BlockEntity entity : chunk.getBlockEntities().values()) {
                if (entity instanceof SignBlockEntity sign) {
                    sites.add(site(sign));
                }
            }
        }
        List<ChunkRef> chunks = GraveBackfill.chunksOf(sites);
        int unchecked = Math.max(0, chunks.size() - GraveBackfill.MAX_CHUNKS);
        List<ChunkRef> checked = chunks.subList(0, chunks.size() - unchecked);
        CompletableFuture.supplyAsync(() -> GraveBackfill.lookUp(owners, checked, host.log()))
                .whenComplete((answered, failure) -> host.run(
                        () -> finish(sender, sites, answered, failure, unchecked)));
    }

    /** Back on the server thread: records the gravestones and sends the reply. */
    private void finish(CommandSourceStack sender, List<SignSite> sites, Map<ChunkRef, OwnerSource.Owners> answered,
            Throwable failure, int unchecked) {
        running.set(false);
        if (failure != null) {
            reply(sender, "geldar-backfill-graves failed: owner lookup did not finish; nothing was recorded.");
            return;
        }
        Tally tally = GraveBackfill.run(sites, placements, GraveBackfill.fromChunks(answered), unchecked);
        reply(sender, GraveBackfill.reply(tally));
    }

    private static void reply(CommandSourceStack sender, String text) {
        sender.sendSuccess(() -> Component.literal(text), false);
    }

    private SignSite site(SignBlockEntity sign) {
        BlockPos at = sign.getBlockPos();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(sign.getFrontText().getMessage(i, false).getString());
        }
        var under = sign.getLevel().getBlockState(at.below()).getBlock();
        String below = BuiltInRegistries.BLOCK.getKey(under).getPath().toUpperCase(Locale.ROOT);
        return new SignSite(host.worldName(sign.getLevel()), at.getX(), at.getY(), at.getZ(), below, lines);
    }
}
