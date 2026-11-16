package dev.geldar.core;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * The mod's logger. The code shared with the Paper plugin logs through java.util.logging, as a plugin's logger does;
 * the game logs through Log4j, so each record is handed on to it.
 */
final class Log {
    static final String NAME = "Geldar-Core";

    private Log() {}

    static Logger create() {
        Logger log = Logger.getLogger(NAME);
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        for (Handler old : log.getHandlers()) {
            log.removeHandler(old);
        }
        org.apache.logging.log4j.Logger game = LogManager.getLogger(NAME);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                game.log(level(record.getLevel()), record.getMessage(), record.getThrown());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return log;
    }

    private static org.apache.logging.log4j.Level level(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return org.apache.logging.log4j.Level.ERROR;
        }
        if (value >= Level.WARNING.intValue()) {
            return org.apache.logging.log4j.Level.WARN;
        }
        if (value >= Level.INFO.intValue()) {
            return org.apache.logging.log4j.Level.INFO;
        }
        return org.apache.logging.log4j.Level.DEBUG;
    }
}
