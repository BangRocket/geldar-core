package dev.geldar.core.blockowner;

import dev.geldar.core.Compat;
import dev.geldar.core.Host;
import dev.geldar.core.blockowner.OwnerCache.Pos;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Turns a place and a player's break into an owner change: the ledger keeps it, the cache learns it at once, and the
 * change goes out on blockowner.changed. Only changes that were not cancelled count. A block an entity that is not a
 * player placed has no owner. Changes no event tells (pistons, explosions, fluids) are not seen here; the ledger drops
 * their records when it is next read.
 */
public final class BlockChangeListener {
    private final Host host;
    private final OwnerLedger ledger;
    private final OwnerCache cache;
    private final BlockOwnerPublisher publisher;
    private final BooleanSupplier enabled;

    public BlockChangeListener(Host host, OwnerLedger ledger, OwnerCache cache, BlockOwnerPublisher publisher,
            BooleanSupplier enabled) {
        this.host = host;
        this.ledger = ledger;
        this.cache = cache;
        this.publisher = publisher;
        this.enabled = enabled;
        host.listen(Compat.blockBreaks((level, pos) -> change(level, pos, null)));
    }

    /** Runs last; a cancelled place is not delivered. A bed or a door places several blocks in one event. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        String owner = event.getEntity() instanceof ServerPlayer player ? Compat.name(player) : null;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot placed : multi.getReplacedBlockSnapshots()) {
                change(level, placed.getPos(), owner);
            }
        } else {
            change(level, event.getPos(), owner);
        }
    }

    /** Server thread only. The ledger keeps every change; the cache and the event follow the switch. */
    private void change(ServerLevel level, BlockPos at, String owner) {
        if (owner == null) {
            ledger.cleared(level, at);
        } else {
            ledger.placed(level, at, owner, OwnerLedger.blockName(level, at));
        }
        record(new Pos(host.worldName(level), at.getX(), at.getY(), at.getZ()), owner);
    }

    void record(Pos pos, String owner) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        cache.recordChange(pos, owner);
        publisher.changed(pos, owner);
    }
}
