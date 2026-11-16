package dev.geldar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pieces that stand where Paper stood: world names, damage cause names, the TPS figure and the settings file. */
class PortTablesTest {
    @Test
    void worldsCarryThePaperNamesTheBodyKnows() {
        assertEquals("world", Worlds.name("world", "minecraft:overworld"));
        assertEquals("world_nether", Worlds.name("world", "minecraft:the_nether"));
        assertEquals("world_the_end", Worlds.name("world", "minecraft:the_end"));
        assertEquals("geldar_nether", Worlds.name("geldar", "minecraft:the_nether"));
        assertEquals("world_aether_sky", Worlds.name("world", "aether:sky"));
    }

    @Test
    void dimensionsReadInTheContractsWords() {
        assertEquals("overworld", Worlds.word("minecraft:overworld"));
        assertEquals("nether", Worlds.word("minecraft:the_nether"));
        assertEquals("end", Worlds.word("minecraft:the_end"));
        assertEquals("other", Worlds.word("aether:sky"));
    }

    @Test
    void damageTypesReadAsBukkitCauseNames() {
        assertEquals("FALL", DamageCauses.name("fall", false));
        assertEquals("ENTITY_ATTACK", DamageCauses.name("mob_attack", true));
        assertEquals("ENTITY_ATTACK", DamageCauses.name("player_attack", true));
        assertEquals("PROJECTILE", DamageCauses.name("arrow", true));
        assertEquals("DROWNING", DamageCauses.name("drown", false));
        assertEquals("STARVATION", DamageCauses.name("starve", false));
        assertEquals("FIRE_TICK", DamageCauses.name("on_fire", false));
        assertEquals("LAVA", DamageCauses.name("lava", false));
        assertEquals("VOID", DamageCauses.name("out_of_world", false));
        assertEquals("BLOCK_EXPLOSION", DamageCauses.name("explosion", false));
        assertEquals("ENTITY_EXPLOSION", DamageCauses.name("explosion", true));
        assertEquals("CUSTOM", DamageCauses.name("somemod:zap", false));
    }

    @Test
    void everyCauseTheGravestoneKnowsCanBeReached() {
        // The gravestone picks its cause word from these Bukkit names; each must come out of the table for some type.
        assertEquals("slain", Gravestone.causeWord(DamageCauses.name("mob_attack", true)));
        assertEquals("slain", Gravestone.causeWord(DamageCauses.name("thorns", true)));
        assertEquals("slain", Gravestone.causeWord(DamageCauses.name("sonic_boom", true)));
        assertEquals("shot", Gravestone.causeWord(DamageCauses.name("arrow", true)));
        assertEquals("drowned", Gravestone.causeWord(DamageCauses.name("drown", false)));
        assertEquals("fell", Gravestone.causeWord(DamageCauses.name("fall", false)));
        assertEquals("burned", Gravestone.causeWord(DamageCauses.name("in_fire", false)));
        assertEquals("burned", Gravestone.causeWord(DamageCauses.name("hot_floor", false)));
        assertEquals("starved", Gravestone.causeWord(DamageCauses.name("starve", false)));
        assertEquals("other", Gravestone.causeWord(DamageCauses.name("cactus", false)));
    }

    @Test
    void tpsIsTwentyUntilTwoTicksAreKnown() {
        TpsMeter meter = new TpsMeter();
        assertEquals(20.0, meter.tps(0));
        meter.tick(0);
        assertEquals(20.0, meter.tps(1_000_000));
    }

    @Test
    void tpsIsTheTickRateOfTheLastMinuteAndNeverAboveTwenty() {
        TpsMeter slow = new TpsMeter();
        // One tick every 100 ms: ten a second.
        for (int i = 0; i <= 300; i++) {
            slow.tick(i * 100_000_000L);
        }
        assertEquals(10.0, slow.tps(300 * 100_000_000L), 0.01);

        TpsMeter fast = new TpsMeter();
        for (int i = 0; i <= 300; i++) {
            fast.tick(i * 40_000_000L);
        }
        assertEquals(20.0, fast.tps(300 * 40_000_000L));
    }

    @Test
    void tpsForgetsTicksOlderThanAMinute() {
        TpsMeter meter = new TpsMeter();
        // A slow first minute, then two minutes at full speed: only the last minute counts.
        for (int i = 0; i < 120; i++) {
            meter.tick(i * 500_000_000L);
        }
        long start = 120 * 500_000_000L;
        for (int i = 0; i <= 2400; i++) {
            meter.tick(start + i * 50_000_000L);
        }
        assertEquals(20.0, meter.tps(start + 2400 * 50_000_000L), 0.01);
    }

    @Test
    void theShippedSettingsParseAndCarryThePluginsDefaults() throws Exception {
        String toml;
        try (InputStream in = Settings.class.getResourceAsStream(Settings.DEFAULTS)) {
            toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Settings s = Settings.parse(toml);
        assertEquals("nats://nats:4222", s.getString("nats.url"));
        assertEquals("phase0", s.getString("shard"));
        assertEquals(10_000, s.getInt("queue.capacity", -1));
        assertEquals(20, s.getInt("tps_interval_seconds", -1));
        assertFalse(s.getBoolean("recipes.grant_on_join", true));
        assertTrue(s.getBoolean("gravestones.enabled", false));
        assertEquals(4, s.getInt("gravestones.max_per_chunk", -1));
        assertFalse(s.getBoolean("lakitu.enabled", true));
        assertFalse(s.getBoolean("kino.enabled", true));
        assertTrue(s.getBoolean("maintenance.broadcast", false));
        assertTrue(s.getBoolean("blockowner.enabled", false));
        assertEquals(2000L, s.getLong("blockowner.lookup_timeout_ms", -1));
        assertEquals(8.0, s.getDouble("chat.muffle.margin", -1));
        assertEquals(512, s.getInt("chat.muffle.per_line_cap", -1));
        assertFalse(s.getBoolean("chat.muffle.bench_log", true));
        CoreConfig config = CoreConfig.load(s, k -> null);
        assertEquals("phase0", config.shard());
    }

    @Test
    void anAbsentKeyOrAValueOfAnotherKindReadsAsTheDefault() {
        Settings s = Settings.parse("shard = 7\n[queue]\ncapacity = \"many\"\n");
        assertNull(s.getString("shard"));
        assertNull(s.getString("nats.url"));
        assertEquals(10_000, s.getInt("queue.capacity", 10_000));
        assertTrue(s.getBoolean("gravestones.enabled", true));
    }

    @Test
    void aMissingSettingsFileIsWrittenFromTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config").resolve("geldar-core.toml");
        Settings s = Settings.load(file);
        assertTrue(Files.exists(file));
        assertEquals("phase0", s.getString("shard"));
        // A file that is there is read as it is, not overwritten.
        Files.writeString(file, "shard = \"colo\"\n");
        assertEquals("colo", Settings.load(file).getString("shard"));
    }
}
