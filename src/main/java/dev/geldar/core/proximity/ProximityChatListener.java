package dev.geldar.core.proximity;

import dev.geldar.core.Compat;
import dev.geldar.core.EventEnvelope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Speech reaches the players whose effective distance is within the clear tier (decision 0199, spec 5.8). Only the
 * raw 32-block gate is evaluated first. The muffled tier removes the words and sends a heard_muffled notice to the
 * listener's agent (its player name is its life id). The speaker keeps their line; the console keeps it too.
 *
 * <p>Paper lets a handler take listeners off a chat line. This server sends a line to everyone or to no one, so the
 * line is cancelled here and sent again, as the same kind of player chat message, to the speaker and to each player who
 * hears it clearly. The message is unsigned, as chat on an offline-mode server already is. The chat event runs on the
 * server thread, so the judge reads blocks directly. A judge that fails silences every other player, which is the safe
 * side.
 *
 * <p>The same handler drops the chat of a silenced player (a ghost) and passes each line that stands to {@code said}
 * (the live chat feed), because cancelling the event hides the line from any later handler.
 */
public final class ProximityChatListener {
    private final LongSupplier ticks;
    private final Function<ServerLevel, BlockReader> readers;
    private final ChatHearing hearing;
    private final HearingNoticeSink sink;
    private final Predicate<ServerPlayer> silenced;
    private final BiConsumer<ServerPlayer, String> said;
    private final Logger log;

    public ProximityChatListener(LongSupplier ticks, Function<ServerLevel, BlockReader> readers, ChatHearing hearing,
            HearingNoticeSink sink, Predicate<ServerPlayer> silenced, BiConsumer<ServerPlayer, String> said,
            Logger log) {
        this.ticks = ticks;
        this.readers = readers;
        this.hearing = hearing;
        this.sink = sink;
        this.silenced = silenced;
        this.said = said;
        this.log = log;
    }

    /** Runs after the other mods' handlers; a line one of them cancelled is not delivered here. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onChat(ServerChatEvent event) {
        ServerPlayer speaker = event.getPlayer();
        event.setCanceled(true);
        if (silenced.test(speaker)) {
            return;
        }
        try {
            said.accept(speaker, event.getRawText());
        } catch (RuntimeException e) {
            log.log(Level.FINE, "chat line was not passed on", e);
        }
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer listener : speaker.level().getServer().getPlayerList().getPlayers()) {
            if (listener != speaker) {
                others.add(listener);
            }
        }
        Set<ServerPlayer> heard;
        try {
            heard = judge(speaker, others);
        } catch (RuntimeException e) {
            log.warning("chat hearing skipped (judge failed); no listener hears this line: "
                    + e.getClass().getSimpleName());
            heard = Set.of();
        }
        deliver(speaker, event.getRawText(), event.getMessage(), heard);
    }

    /** Sends the line to the speaker and to those who hear it clearly, and writes it to the server log. */
    private void deliver(ServerPlayer speaker, String raw, Component content, Set<ServerPlayer> heard) {
        PlayerChatMessage message = PlayerChatMessage.unsigned(speaker.getUUID(), raw).withUnsignedContent(content);
        OutgoingChatMessage outgoing = OutgoingChatMessage.create(message);
        ChatType.Bound type = ChatType.bind(ChatType.CHAT, speaker);
        speaker.level().getServer().logChatMessage(message.decoratedContent(), type, null);
        speaker.sendChatMessage(outgoing, false, type);
        for (ServerPlayer listener : heard) {
            listener.sendChatMessage(outgoing, false, type);
        }
    }

    /** Runs on the server thread: the clear listeners are returned, and the muffled notices are sent. */
    Set<ServerPlayer> judge(ServerPlayer speaker, List<ServerPlayer> others) {
        Set<ServerPlayer> clear = new HashSet<>();
        if (!(speaker.level() instanceof ServerLevel world)) {
            return clear;
        }
        List<ChatHearing.Candidate> candidates = new ArrayList<>();
        Map<String, ServerPlayer> byName = new HashMap<>();
        for (ServerPlayer listener : others) {
            String name = Compat.name(listener);
            boolean sameWorld = listener.level() == world;
            candidates.add(new ChatHearing.Candidate(name, sameWorld, listener.getX(), listener.getY(), listener.getZ()));
            byName.put(name, listener);
        }
        BlockReader reader = readers.apply(world);
        List<ChatHearing.Verdict> verdicts = hearing.judge(ticks.getAsLong(), Compat.name(speaker), speaker.getX(),
                speaker.getY(), speaker.getZ(), candidates, reader);
        for (ChatHearing.Verdict verdict : verdicts) {
            ServerPlayer listener = byName.get(verdict.key());
            if (verdict.tier() == Tier.CLEAR) {
                clear.add(listener);
            } else if (verdict.tier() == Tier.MUFFLED && EventEnvelope.isLifeId(verdict.key())) {
                sink.muffled(verdict.key(), verdict.notice().direction(), verdict.notice().band());
            }
        }
        return clear;
    }
}
