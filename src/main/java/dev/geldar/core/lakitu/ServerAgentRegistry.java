package dev.geldar.core.lakitu;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Reads an online agent's position by name through getters only. An offline agent has no position. */
public final class ServerAgentRegistry implements AgentRegistry {
    private final MinecraftServer server;

    public ServerAgentRegistry(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Optional<AgentPosition> position(String agentId) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(agentId);
        if (player == null) {
            return Optional.empty();
        }
        return Optional.of(new AgentPosition(agentId, player.getX(), player.getY(), player.getZ(), player.getYRot(),
                Math.max(-90f, Math.min(90f, player.getXRot())), player.onGround()));
    }
}
