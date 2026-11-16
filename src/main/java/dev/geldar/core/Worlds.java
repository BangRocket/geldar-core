package dev.geldar.core;

/**
 * World names as the rest of Geldar knows them. Paper names each dimension's world after the server's level name
 * ({@code world}, {@code world_nether}, {@code world_the_end}), and the Body, the block-owner queries and the Kino feed
 * carry those names. This server has dimension ids instead, so they are turned into the same names here.
 */
public final class Worlds {
    private Worlds() {}

    /** The Paper world name of a dimension such as {@code minecraft:the_nether}, under the server's level name. */
    public static String name(String levelName, String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> levelName;
            case "minecraft:the_nether" -> levelName + "_nether";
            case "minecraft:the_end" -> levelName + "_the_end";
            default -> levelName + "_" + dimension.replace(':', '_').replace('/', '_');
        };
    }

    /** The dimension in the event contract's words; a custom dimension reads as {@code other}. */
    public static String word(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "overworld";
            case "minecraft:the_nether" -> "nether";
            case "minecraft:the_end" -> "end";
            default -> "other";
        };
    }
}
