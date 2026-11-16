package dev.geldar.core;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The settings file ({@code config/geldar-core.toml}), read by dotted key such as {@code chat.muffle.margin}. It stands
 * where the Paper plugin's {@code config.yml} stood and keeps its keys. A key that is absent, or holds a value of
 * another kind, reads as the default.
 */
public final class Settings {
    /** The file with the defaults and their comments, inside the jar. */
    static final String DEFAULTS = "/geldar-core.toml";

    private final Config values;

    private Settings(Config values) {
        this.values = values;
    }

    /** Settings from TOML text. */
    public static Settings parse(String toml) {
        return new Settings(new TomlParser().parse(toml));
    }

    /** Settings with nothing set: every key reads as its default. */
    public static Settings empty() {
        return new Settings(Config.inMemory());
    }

    /** Sets one key, for settings built in code. */
    public Settings set(String key, Object value) {
        values.set(key, value);
        return this;
    }

    /** Reads {@code file}, writing the defaults there first when it does not exist. */
    public static Settings load(Path file) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (InputStream in = Settings.class.getResourceAsStream(DEFAULTS)) {
                if (in == null) {
                    throw new IOException("the default settings are missing from the jar");
                }
                Files.copy(in, file);
            }
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The text at {@code key}, or null when it is absent or not text. */
    public String getString(String key) {
        return values.get(key) instanceof String s ? s : null;
    }

    public boolean getBoolean(String key, boolean fallback) {
        return values.get(key) instanceof Boolean b ? b : fallback;
    }

    public int getInt(String key, int fallback) {
        return values.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public long getLong(String key, long fallback) {
        return values.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    public double getDouble(String key, double fallback) {
        return values.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }
}
