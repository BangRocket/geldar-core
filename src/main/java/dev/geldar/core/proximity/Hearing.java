package dev.geldar.core.proximity;

import net.minecraft.world.entity.Entity;

/** Applies {@link ProximityRule} to two entities: the game side of the hearing rule. */
final class Hearing {
    private Hearing() {
    }

    /** True when {@code listener} hears a line spoken where {@code source} is. */
    static boolean between(Entity source, Entity listener) {
        boolean sameWorld = source.level() == listener.level();
        return ProximityRule.heard(
                sameWorld,
                listener.getX() - source.getX(),
                listener.getY() - source.getY(),
                listener.getZ() - source.getZ());
    }
}
