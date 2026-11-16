package dev.geldar.core.blockowner;

import dev.geldar.core.blockowner.OwnerCache.ChunkKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owners exported from a Paper shard's CoreProtect database, as {@code tools/coreprotect-owners.sql} writes them: a CSV
 * with the header {@code world,x,y,z,user,block}, one line per position whose newest CoreProtect row is a place by a
 * player. Pure: it reads and writes text and holds no game types, so it is tested without a server.
 *
 * <p>A line that cannot be read (the wrong number of fields, a coordinate that is not a whole number, no world, a user
 * that is not a player) is skipped and counted; the first few are named in {@link Parsed#problems}. A line with no
 * block is kept with an empty one, as an owner whose block CoreProtect could not name.
 */
public final class OwnerImport {
    /** The header the export writes; the importer insists on it, so a file of something else is refused whole. */
    public static final String HEADER = "world,x,y,z,user,block";
    static final int MAX_PROBLEMS = 5;

    private OwnerImport() {}

    /** One owned position: where, who placed it, and the block placed, by its name without the game's namespace (or empty). */
    public record Row(int x, int y, int z, String owner, String block) {}

    /**
     * What a file held: its rows grouped by world and chunk (in file order), how many lines were read as rows, how many
     * were skipped, and what was wrong with the first few skipped.
     */
    public record Parsed(Map<ChunkKey, List<Row>> chunks, int rows, int skipped, List<String> problems) {}

    /** Reads an exported file. */
    public static Parsed read(Path file) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(in);
        }
    }

    /**
     * Reads exported text.
     *
     * @throws IOException when the text cannot be read, or its first line is not {@link #HEADER}
     */
    public static Parsed parse(BufferedReader in) throws IOException {
        String header = in.readLine();
        if (header == null || !HEADER.equals(stripBom(header).strip())) {
            throw new IOException("the first line must be \"" + HEADER + "\"; is this the export of coreprotect-owners.sql"
                    + " run with -header -csv?");
        }
        Map<ChunkKey, List<Row>> chunks = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int rows = 0;
        int skipped = 0;
        int number = 1;
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            String problem;
            try {
                List<String> fields = fields(line);
                problem = fields.size() != 6 ? "has " + fields.size() + " fields, not 6" : null;
                if (problem == null) {
                    String world = fields.get(0).strip();
                    int x = Integer.parseInt(fields.get(1).strip());
                    int y = Integer.parseInt(fields.get(2).strip());
                    int z = Integer.parseInt(fields.get(3).strip());
                    String user = fields.get(4).strip();
                    String block = blockName(fields.get(5).strip());
                    // An empty block is kept: CoreProtect's row named a material its map no longer holds, and the
                    // owner still stands, as it did on Paper.
                    problem = world.isEmpty() ? "names no world"
                            : !FirstRowResolver.isPlayer(user) ? "names no player"
                            : null;
                    if (problem == null) {
                        chunks.computeIfAbsent(new ChunkKey(world, x >> 4, z >> 4), k -> new ArrayList<>())
                                .add(new Row(x, y, z, user, block));
                        rows++;
                        continue;
                    }
                }
            } catch (NumberFormatException e) {
                problem = "has a coordinate that is not a whole number";
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            }
            skipped++;
            if (problems.size() < MAX_PROBLEMS) {
                problems.add("line " + number + " " + problem);
            }
        }
        return new Parsed(chunks, rows, skipped, List.copyOf(problems));
    }

    /** Writes rows in the export's own format, so the file can be read back with {@link #read}. */
    public static void write(Path file, Map<ChunkKey, List<Row>> chunks) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try (Writer out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.write('\n');
            for (Map.Entry<ChunkKey, List<Row>> chunk : chunks.entrySet()) {
                String world = quote(chunk.getKey().world());
                for (Row row : chunk.getValue()) {
                    out.write(world + "," + row.x() + "," + row.y() + "," + row.z() + "," + quote(row.owner()) + ","
                            + quote(row.block()) + "\n");
                }
            }
        }
        Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** A block id as the ledger names blocks: lower case, without the game's own namespace; a mod's keeps its own. */
    public static String blockName(String id) {
        String name = id.toLowerCase(Locale.ROOT);
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    /** The fields of one CSV line: separated by commas; a field in double quotes may hold commas and doubled quotes. */
    static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("has a quote that is never closed");
        }
        fields.add(field.toString());
        return fields;
    }

    private static String quote(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}
