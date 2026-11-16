package dev.geldar.core.lakitu;

import dev.geldar.core.Compat;
import dev.geldar.core.Host;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Queues block changes for the feed. It runs last and is read-only: it only reads the event and never
 * changes it (invariant I2). A cancelled event did not change the world, so it is not queued.
 */
public final class BlockUpdateListener {
    private final Host host;
    private final UpdateQueue queue;
    private final LongSupplier tick;

    public BlockUpdateListener(Host host, UpdateQueue queue, LongSupplier tick) {
        this.host = host;
        this.queue = queue;
        this.tick = tick;
        host.listen(Compat.blockBreaks((level, pos) -> record(level, pos, "minecraft:air")));
    }

    /** A cancelled place is not delivered. A bed or a door places several blocks in one event. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot placed : multi.getReplacedBlockSnapshots()) {
                record(level, placed.getPos(), Compat.blockId(level.getBlockState(placed.getPos())));
            }
        } else {
            record(level, event.getPos(), Compat.blockId(event.getPlacedBlock()));
        }
    }

    private void record(ServerLevel level, BlockPos at, String state) {
        queue.offer(new UpdateQueue.BlockUpdate(
                tick.getAsLong(), host.worldName(level), at.getX(), at.getY(), at.getZ(), state));
    }
}
