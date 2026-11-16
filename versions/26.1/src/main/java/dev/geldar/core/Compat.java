package dev.geldar.core;

import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/** The game calls that differ between Minecraft versions, as Minecraft 26.1 spells them. */
public final class Compat {
    private Compat() {}

    /** The player's name: for an agent character, its life id. */
    public static String name(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    /** True when the profile the proxy forwarded carries a property of this name. */
    public static boolean hasProperty(ServerPlayer player, String name) {
        return player.getGameProfile().properties().containsKey(name);
    }

    /** The dimension's id, such as {@code minecraft:the_nether}. */
    public static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    /** The name the save was created under ({@code level-name} in server.properties). */
    public static String levelName(MinecraftServer server) {
        return server.getWorldData().getLevelName();
    }

    /** The lowest block height of the world. */
    public static int minY(Level level) {
        return level.getMinY();
    }

    /** One above the highest block height of the world. */
    public static int maxY(Level level) {
        return level.getMaxY() + 1;
    }

    /** The block's id, such as {@code minecraft:oak_log}. */
    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** The entity type's id, such as {@code minecraft:zombie}. */
    public static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /** The damage type's id without its namespace, such as {@code mob_attack}; a mod's own type keeps its namespace. */
    public static String damageType(DamageSource source) {
        return source.typeHolder().unwrapKey().map(key -> {
            var id = key.identifier();
            return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
        }).orElse("generic");
    }

    /** Where the player comes back after a death: its own respawn point, else the world's. */
    public static BlockPos respawn(ServerPlayer player) {
        ServerPlayer.RespawnConfig own = player.getRespawnConfig();
        if (own != null) {
            return own.respawnData().pos();
        }
        return ((ServerLevel) player.level()).getRespawnData().pos();
    }

    /** The senders that hold the owner's permissions and are not a player: the server console and RCON. */
    public static Predicate<CommandSourceStack> consoleOnly() {
        Predicate<CommandSourceStack> owner = Commands.hasPermission(Commands.LEVEL_OWNERS);
        return source -> owner.test(source) && source.getEntity() == null;
    }

    /** True when the game announces a death to every player on its own. */
    public static boolean announcesDeaths(MinecraftServer server) {
        return server.overworld().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES);
    }

    /** Stops the game from announcing a death to every player. */
    public static void stopAnnouncingDeaths(MinecraftServer server) {
        server.overworld().getGameRules().set(GameRules.SHOW_DEATH_MESSAGES, false, server);
    }

    /** The world's time in ticks since it began, days included: what Bukkit calls the full time. */
    public static long fullTime(Level level) {
        return level.getDefaultClockTime();
    }

    /**
     * A listener that calls {@code seen} for each block a player broke, after every other handler has had its say; a
     * break that was cancelled is not reported. The event has another class on older versions, so it is named here.
     */
    public static Object blockBreaks(BiConsumer<ServerLevel, BlockPos> seen) {
        return new BlockBreaks(seen);
    }

    /** See {@link #blockBreaks}. */
    public static final class BlockBreaks {
        private final BiConsumer<ServerLevel, BlockPos> seen;

        private BlockBreaks(BiConsumer<ServerLevel, BlockPos> seen) {
            this.seen = seen;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onBreak(BreakBlockEvent event) {
            if (event.getLevel() instanceof ServerLevel level) {
                seen.accept(level, event.getPos());
            }
        }
    }
}
