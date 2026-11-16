package dev.geldar.core.blockowner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geldar.core.blockowner.OwnerCache.ChunkKey;
import dev.geldar.core.blockowner.OwnerImport.Row;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the owners exported from CoreProtect, and keeping them with the world. */
class OwnerImportTest {
    private static OwnerImport.Parsed parse(String text) throws IOException {
        return OwnerImport.parse(new BufferedReader(new StringReader(text)));
    }

    @Test
    void rowsAreGroupedByWorldAndChunkWithTheBlockNamedAsTheLedgerNamesIt() throws IOException {
        OwnerImport.Parsed p = parse(OwnerImport.HEADER + "\n"
                + "world,10,64,20,ag_alice_01,minecraft:stone\n"
                + "world,-1,64,-1,ag_bob_01,minecraft:oak_planks\n"
                + "world,15,70,31,ag_alice_01,MINECRAFT:Farmland\n"
                + "world_nether,10,40,20,ag_alice_01,othermod:thing\n");
        assertEquals(4, p.rows());
        assertEquals(0, p.skipped());
        assertEquals(Map.of(
                new ChunkKey("world", 0, 1), List.of(new Row(10, 64, 20, "ag_alice_01", "stone"),
                        new Row(15, 70, 31, "ag_alice_01", "farmland")),
                new ChunkKey("world", -1, -1), List.of(new Row(-1, 64, -1, "ag_bob_01", "oak_planks")),
                new ChunkKey("world_nether", 0, 1), List.of(new Row(10, 40, 20, "ag_alice_01", "othermod:thing"))),
                p.chunks());
    }

    @Test
    void aFileWithoutTheHeaderIsRefusedWhole() {
        IOException e = assertThrows(IOException.class, () -> parse("world,1,2,3,ag_alice_01,minecraft:stone\n"));
        assertTrue(e.getMessage().contains(OwnerImport.HEADER), e.getMessage());
        assertThrows(IOException.class, () -> parse(""));
    }

    @Test
    void sqliteWritesAByteOrderMarkSometimesAndItIsAllowed() throws IOException {
        assertEquals(1, parse("﻿" + OwnerImport.HEADER + "\r\nworld,1,2,3,ag_alice_01,minecraft:stone\r\n").rows());
    }

    @Test
    void badLinesAreSkippedCountedAndTheFirstFewNamed() throws IOException {
        OwnerImport.Parsed p = parse(OwnerImport.HEADER + "\n"
                + "world,1,2,3,ag_alice_01,minecraft:stone\n"
                + "world,1,2\n"
                + "world,one,2,3,ag_alice_01,minecraft:stone\n"
                + "world,1,2,3,#tnt,minecraft:stone\n"
                + ",1,2,3,ag_alice_01,minecraft:stone\n"
                + "\"world,1,2,3,ag_alice_01,minecraft:stone\n"
                + "\n"
                + "world,1,2,4,ag_alice_01,\n");
        assertEquals(2, p.rows());
        assertEquals(5, p.skipped());
        assertEquals(List.of("line 3 has 3 fields, not 6", "line 4 has a coordinate that is not a whole number",
                "line 5 names no player", "line 6 names no world", "line 7 has a quote that is never closed"),
                p.problems());
        // A row with no block keeps its owner, with an empty block.
        assertEquals(new Row(1, 2, 4, "ag_alice_01", ""), p.chunks().get(new ChunkKey("world", 0, 0)).get(1));
    }

    @Test
    void quotedFieldsAreRead() {
        assertEquals(List.of("my world", "a,b", "say \"hi\"", ""), OwnerImport.fields("my world,\"a,b\",\"say \"\"hi\"\"\","));
    }

    @Test
    void whatIsWrittenReadsBackTheSame(@TempDir Path dir) throws IOException {
        Map<ChunkKey, List<Row>> chunks = Map.of(
                new ChunkKey("world", 0, 1), List.of(new Row(10, 64, 20, "ag_alice_01", "stone")),
                new ChunkKey("a, world", -1, -1), List.of(new Row(-1, 64, -1, "ag_bob_01", "")));
        Path file = dir.resolve("geldar-core").resolve("owner-import.csv");
        OwnerImport.write(file, chunks);
        OwnerImport.Parsed back = OwnerImport.read(file);
        assertEquals(chunks, back.chunks());
        assertEquals(0, back.skipped());
    }
}
