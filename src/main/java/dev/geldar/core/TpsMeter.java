package dev.geldar.core;

/**
 * Ticks a second over the last minute, as Paper's first TPS figure is: the ticks the server ran in the window divided
 * by the time they took, never above 20. Fed once a tick with the clock; not thread-safe.
 */
final class TpsMeter {
    static final double FULL = 20.0;
    static final long WINDOW_NANOS = 60_000_000_000L;
    /** Room for a minute of ticks at full speed, and one more for the tick that opens the window. */
    private static final int CAPACITY = 1201;

    private final long[] at = new long[CAPACITY];
    private int next;
    private int size;

    /** Records one tick at {@code nanos}. */
    void tick(long nanos) {
        at[next] = nanos;
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
    }

    /** The figure at {@code nanos}; 20 until two ticks are known. */
    double tps(long nanos) {
        int counted = 0;
        long oldest = nanos;
        for (int i = 1; i <= size; i++) {
            long t = at[Math.floorMod(next - i, CAPACITY)];
            if (nanos - t > WINDOW_NANOS) {
                break;
            }
            oldest = t;
            counted++;
        }
        if (counted < 2 || nanos <= oldest) {
            return FULL;
        }
        return Math.min(FULL, (counted - 1) * 1_000_000_000.0 / (nanos - oldest));
    }
}
