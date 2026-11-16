package dev.geldar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** The damage, death and sleep events as the exporter builds them from plain values, and the command block's log. */
class EventExporterWorldEventsTest {
    private static final Logger LOG = Logger.getLogger("test");

    private final InMemoryEventPublisher mem = new InMemoryEventPublisher();
    private final EventExporter exporter =
            new EventExporter(mem, "phase0", () -> 48_100, () -> Instant.parse("2026-11-09T00:00:00Z"), LOG);

    @Test
    void damageCarriesTheCauseTheAmountAndTheAgent() {
        exporter.damage("ag_wren_02", "FALL", 3.5);
        EventEnvelope e = mem.events().get(0);
        assertEquals("geldar.phase0.damage", e.subject());
        assertEquals("ag_wren_02", e.agentId());
        assertEquals("FALL", e.payload().get("cause").getAsString());
        assertEquals(3.5, e.payload().get("amount").getAsDouble());
        assertEquals("ag_wren_02", e.payload().get("player").getAsString());
        assertTrue(EventSchemaCheck.errors(e).isEmpty());
    }

    @Test
    void aPlayerWhoIsNotAnAgentCarriesNoAgentId() {
        exporter.damage("Steve", "LAVA", 4.0);
        assertNull(mem.events().get(0).agentId());
        assertTrue(EventSchemaCheck.errors(mem.events().get(0)).isEmpty());
    }

    @Test
    void deathCarriesThePlaceTheDimensionTheDropsAndTheRespawnPoint() {
        exporter.death("ag_wren_02", "ENTITY_ATTACK", new EventExporter.Place(10, 64, -3), "overworld",
                List.of(new EventExporter.Drop("oak_log", 12), new EventExporter.Drop("stone_pickaxe", 1)),
                new EventExporter.Place(0, 70, 0));
        EventEnvelope e = mem.events().get(0);
        JsonObject p = e.payload();
        assertEquals("geldar.phase0.death", e.subject());
        assertEquals("ENTITY_ATTACK", p.get("cause").getAsString());
        assertEquals(10, p.get("x").getAsInt());
        assertEquals(64, p.get("y").getAsInt());
        assertEquals(-3, p.get("z").getAsInt());
        assertEquals("overworld", p.get("dimension").getAsString());
        assertEquals(13, p.get("drops").getAsInt());
        assertEquals(2, p.getAsJsonArray("drop_items").size());
        JsonObject first = p.getAsJsonArray("drop_items").get(0).getAsJsonObject();
        assertEquals("oak_log", first.get("item").getAsString());
        assertEquals(12, first.get("count").getAsInt());
        assertEquals(10, first.get("x").getAsInt());
        assertEquals(70, p.get("respawn_y").getAsInt());
        assertTrue(EventSchemaCheck.errors(e).isEmpty());
    }

    @Test
    void aDeathWithNothingKnownStillGoesOut() {
        exporter.death("ag_wren_02", null, null, null, List.of(), null);
        JsonObject p = mem.events().get(0).payload();
        assertEquals("UNKNOWN", p.get("cause").getAsString());
        assertFalse(p.has("x"));
        assertFalse(p.has("dimension"));
        assertFalse(p.has("drops"));
        assertFalse(p.has("drop_items"));
        assertFalse(p.has("respawn_x"));
        assertTrue(EventSchemaCheck.errors(mem.events().get(0)).isEmpty());
    }

    @Test
    void sleepIsAnEmptyEventForThePlayer() {
        exporter.sleep("ag_wren_02");
        EventEnvelope e = mem.events().get(0);
        assertEquals("geldar.phase0.sleep", e.subject());
        assertEquals("ag_wren_02", e.agentId());
        assertTrue(EventSchemaCheck.errors(e).isEmpty());
    }

    private static Logger capturing(List<String> lines) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return log;
    }

    @Test
    void theLoggedCommandIsTruncatedAndFlattened() {
        List<String> lines = new ArrayList<>();
        String hostile = "/say " + "x".repeat(5_000) + "\nforged line";
        new CommandBlockListener(capturing(lines)).blocked("ag_wren_02", hostile);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).length() < 250, lines.get(0).length() + " chars logged");
        assertTrue(lines.get(0).contains("(" + hostile.length() + " chars)"));
        assertEquals("/a b", CommandBlockListener.clip("/a\nb"));
    }

    @Test
    void unicodeControlAndFormatCharactersAreFlattened() {
        // U+2028 line separator, U+0085 next line, U+202E right-to-left override, U+200B zero width space.
        assertEquals("/a b c d e", CommandBlockListener.clip("/a b\u0085c‮d​e"));
    }

    @Test
    void aFloodOfCommandsIsLoggedOnlyForTheFirstFew() {
        List<String> lines = new ArrayList<>();
        long[] clock = {0};
        CommandBlockListener listener = new CommandBlockListener(capturing(lines), () -> clock[0]);
        int total = CommandBlockListener.MAX_LOGGED_PER_WINDOW + 5;
        for (int i = 0; i < total; i++) {
            listener.blocked("ag_wren_02", "/x" + i);
        }
        assertEquals(CommandBlockListener.MAX_LOGGED_PER_WINDOW, lines.size());
        clock[0] = CommandBlockListener.WINDOW_NANOS;
        listener.blocked("ag_wren_02", "/later");
        assertEquals(CommandBlockListener.MAX_LOGGED_PER_WINDOW + 2, lines.size());
        assertTrue(lines.get(CommandBlockListener.MAX_LOGGED_PER_WINDOW).startsWith("5 blocked commands"));
    }

    @Test
    void aFailedEnableReleasesWhatItStartedThenStopsTheServer() {
        List<String> calls = new ArrayList<>();
        GeldarCore.enableOrStop(
                () -> {
                    throw new IllegalStateException("bad config");
                },
                () -> calls.add("disable"),
                () -> calls.add("shutdown"),
                capturing(new ArrayList<>()));
        assertEquals(List.of("disable", "shutdown"), calls);
    }

    @Test
    void aSuccessfulEnableLeavesTheServerRunning() {
        List<String> calls = new ArrayList<>();
        GeldarCore.enableOrStop(() -> calls.add("enable"), () -> calls.add("disable"), () -> calls.add("shutdown"), LOG);
        assertEquals(List.of("enable"), calls);
    }
}
