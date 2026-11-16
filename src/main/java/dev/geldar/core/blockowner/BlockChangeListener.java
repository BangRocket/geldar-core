package dev.geldar.core.blockowner;

import dev.geldar.core.Compat;
import dev.geldar.core.Host;
import dev.geldar.core.blockowner.OwnerCache.Pos;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Keeps the ledger to what CoreProtect, as Geldar configures it, would have logged, and tells the Body of the changes
 * a player makes. A place and a player's break become an owner change: the ledger keeps it, the cache learns it at
 * once, and the change goes out on blockowner.changed. Only changes that were not cancelled count. A block an entity
 * that is not a player placed has no owner.
 *
 * <p>The game counts every block an item changes when used on a block as placed by the player who used it: tilled
 * farmland, a stripped log, a path, fire from flint and steel. CoreProtect records those the same way. What bone meal
 * grows is the exception: CoreProtect logs growth only with tree growth on, and Geldar keeps it off, so growth from bone
 * meal is left out and the ledger stays as it was.
 *
 * <p>A piston's move and an explosion take blocks from their placers, as CoreProtect logs them under a non-player
 * cause: those positions are cleared in the ledger. Like Paper's plugin, these are not sent as changes; the Body's
 * next read sees them.
 */
public final class BlockChangeListener {
    private final Host host;
    private final OwnerLedger ledger;
    private final OwnerCache cache;
    private final BlockOwnerPublisher publisher;
    private final BooleanSupplier enabled;
    /** The player whose bone meal is growing things in this tick, and the tick; the place event that follows is growth. */
    private UUID growing;
    private long growingTick = -1;

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
        if (event.getEntity() instanceof ServerPlayer player && isGrowth(player, level)) {
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

    /** A player's bone meal is about to grow something; the place event the game sends after it is that growth. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBonemeal(BonemealEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && event.getLevel() instanceof ServerLevel level) {
            growing = player.getUUID();
            growingTick = level.getGameTime();
        }
    }

    /** True once for the place event that follows this player's bone meal in the same tick. */
    private boolean isGrowth(ServerPlayer player, ServerLevel level) {
        boolean growth = player.getUUID().equals(growing) && growingTick == level.getGameTime();
        if (growth) {
            growing = null;
            growingTick = -1;
        }
        return growth;
    }

    /** Before a piston moves blocks: where each comes from and where it goes, and what it breaks, lose their owner. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PistonStructureResolver structure = event.getStructureHelper();
        if (!structure.resolve()) {
            return;
        }
        Direction direction = structure.getPushDirection();
        for (BlockPos moved : structure.getToPush()) {
            ledger.cleared(level, moved);
            ledger.cleared(level, moved.relative(direction));
        }
        for (BlockPos broken : structure.getToDestroy()) {
            ledger.cleared(level, broken);
        }
    }

    /** The blocks an explosion takes lose their owner. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            for (BlockPos blown : event.getAffectedBlocks()) {
                ledger.cleared(level, blown);
            }
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
