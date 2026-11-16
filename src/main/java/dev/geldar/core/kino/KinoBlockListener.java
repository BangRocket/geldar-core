package dev.geldar.core.kino;

import dev.geldar.core.Compat;
import dev.geldar.core.Host;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Reports block changes to the feed. It runs last and is read-only: it never changes an event. */
public final class KinoBlockListener {
    private final Host host;
    private final KinoFeed feed;

    public KinoBlockListener(Host host, KinoFeed feed) {
        this.host = host;
        this.feed = feed;
        host.listen(Compat.blockBreaks((level, pos) -> changed(level, pos, "minecraft:air")));
    }

    /** A cancelled place is not delivered. A bed or a door places several blocks in one event. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot placed : multi.getReplacedBlockSnapshots()) {
                changed(level, placed.getPos(), BlockStateParser.serialize(level.getBlockState(placed.getPos())));
            }
        } else {
            changed(level, event.getPos(), BlockStateParser.serialize(event.getPlacedBlock()));
        }
    }

    private void changed(ServerLevel level, BlockPos at, String state) {
        feed.blockChanged(host.worldName(level), at.getX(), at.getY(), at.getZ(), state);
    }
}
