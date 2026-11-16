package dev.geldar.core;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Unlocks every registered recipe on join (decision 0032). Off by default since decision 0048: recipes
 * unlock through play, and {@code recipes.grant_on_join} registers this listener again.
 */
public final class RecipeGrantListener {
    private final Logger log;

    public RecipeGrantListener(Logger log) {
        this.log = log;
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // Never throws into the server thread: a failed grant leaves the player with the vanilla recipe book.
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                player.awardRecipes(player.level().getServer().getRecipeManager().getRecipes());
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "recipe grant on join failed", e);
        }
    }
}
