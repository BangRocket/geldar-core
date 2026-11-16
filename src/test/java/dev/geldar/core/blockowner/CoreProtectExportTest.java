package dev.geldar.core.blockowner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code tools/coreprotect-owners.sql} against a database laid out as CoreProtect 24.1 lays out its SQLite file. The
 * tables are made with CoreProtect's own statements (Database.java, its SQLite branch, at tag v24.1). The rule the SQL
 * must keep is the one {@link FirstRowResolver} applies to CoreProtect's lookup: newest row first by rowid, rolled-back
 * rows (1 or 3) passed over, only break, place and click rows counted, and an owner only when that row is a place by a
 * player.
 */
class CoreProtectExportTest {
    private static final Path SQL = Path.of("tools/coreprotect-owners.sql");
    private static final int BREAK = 0;
    private static final int PLACE = 1;
    private static final int CLICK = 2;
    private static final int KILL = 3;

    private Connection db;
    private int time;

    @BeforeEach
    void createCoreProtectTables() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER,"
                    + " y INTEGER, z INTEGER, type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, action INTEGER,"
                    + " rolled_back INTEGER);");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS co_material_map (id INTEGER, material TEXT);");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS co_user (id INTEGER PRIMARY KEY ASC, time INTEGER, user TEXT,"
                    + " uuid TEXT);");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS co_world (id INTEGER, world TEXT);");
            s.executeUpdate("INSERT INTO co_world VALUES (1, 'world'), (2, 'world_nether')");
            s.executeUpdate("INSERT INTO co_user (id, time, user, uuid) VALUES (1, 0, 'ag_alice_01', 'a'),"
                    + " (2, 0, 'ag_bob_01', 'b'), (3, 0, '#tnt', NULL), (4, 0, '#piston', NULL),"
                    + " (5, 0, 'geldar-system', NULL)");
            s.executeUpdate("INSERT INTO co_material_map VALUES (1, 'minecraft:stone'), (2, 'minecraft:oak_planks'),"
                    + " (3, 'minecraft:farmland'), (4, 'minecraft:stone_bricks')");
        }
    }

    private void row(int user, int world, int x, int y, int z, int type, int action, int rolledBack) throws Exception {
        try (var insert = db.prepareStatement("INSERT INTO co_block (time, user, wid, x, y, z, type, data, action,"
                + " rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)")) {
            insert.setInt(1, ++time);
            insert.setInt(2, user);
            insert.setInt(3, world);
            insert.setInt(4, x);
            insert.setInt(5, y);
            insert.setInt(6, z);
            insert.setInt(7, type);
            insert.setInt(8, action);
            insert.setInt(9, rolledBack);
            insert.executeUpdate();
        }
    }

    private List<String> export() throws Exception {
        List<String> lines = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(Files.readString(SQL, StandardCharsets.UTF_8))) {
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                columns.add(r.getMetaData().getColumnLabel(i));
            }
            lines.add(String.join(",", columns));
            while (r.next()) {
                lines.add(r.getString(1) + "," + r.getInt(2) + "," + r.getInt(3) + "," + r.getInt(4) + ","
                        + r.getString(5) + "," + r.getString(6));
            }
        }
        return lines;
    }

    @Test
    void theHeaderIsTheOneTheImporterAsksFor() throws Exception {
        assertEquals(List.of(OwnerImport.HEADER), export());
    }

    @Test
    void aPlaceByAPlayerIsAnOwnerWithTheMaterialPlaced() throws Exception {
        row(1, 1, 10, 64, 20, 1, PLACE, 0);
        row(1, 2, -5, 40, -7, 2, PLACE, 0);
        row(5, 1, 0, 70, 0, 4, PLACE, 0);
        assertEquals(List.of(OwnerImport.HEADER,
                "world,0,70,0,geldar-system,minecraft:stone_bricks",
                "world,10,64,20,ag_alice_01,minecraft:stone",
                "world_nether,-5,40,-7,ag_alice_01,minecraft:oak_planks"), export());
    }

    @Test
    void theNewestRowDecides() throws Exception {
        // Placed by alice, broken by bob: no owner.
        row(1, 1, 1, 64, 1, 1, PLACE, 0);
        row(2, 1, 1, 64, 1, 1, BREAK, 0);
        // Placed by alice, then by bob: bob.
        row(1, 1, 2, 64, 2, 1, PLACE, 0);
        row(2, 1, 2, 64, 2, 2, PLACE, 0);
        // Placed by bob, then a click by alice: the click is newest, so no owner, as the resolver rules.
        row(2, 1, 3, 64, 3, 1, PLACE, 0);
        row(1, 1, 3, 64, 3, 1, CLICK, 0);
        assertEquals(List.of(OwnerImport.HEADER, "world,2,64,2,ag_bob_01,minecraft:oak_planks"), export());
    }

    @Test
    void aNonPlayerChangeTakesTheOwnerAway() throws Exception {
        row(1, 1, 1, 64, 1, 1, PLACE, 0);
        row(3, 1, 1, 64, 1, 1, BREAK, 0);
        row(1, 1, 2, 64, 2, 1, PLACE, 0);
        row(4, 1, 2, 64, 2, 1, PLACE, 0);
        assertEquals(List.of(OwnerImport.HEADER), export());
    }

    @Test
    void rolledBackRowsArePassedOver() throws Exception {
        // A break that was rolled back (1) leaves the place standing; so does one rolled back and restored (3).
        row(1, 1, 1, 64, 1, 1, PLACE, 0);
        row(2, 1, 1, 64, 1, 1, BREAK, 1);
        row(1, 1, 2, 64, 2, 1, PLACE, 0);
        row(2, 1, 2, 64, 2, 1, BREAK, 3);
        // A place that was rolled back is no owner; the break under it stands.
        row(1, 1, 3, 64, 3, 1, BREAK, 0);
        row(2, 1, 3, 64, 3, 2, PLACE, 1);
        // A row whose rollback was undone (2) counts again.
        row(1, 1, 4, 64, 4, 1, PLACE, 0);
        row(2, 1, 4, 64, 4, 1, BREAK, 2);
        assertEquals(List.of(OwnerImport.HEADER,
                "world,1,64,1,ag_alice_01,minecraft:stone",
                "world,2,64,2,ag_alice_01,minecraft:stone"), export());
    }

    @Test
    void killRowsAreNotBlockRowsAndDoNotDecide() throws Exception {
        row(1, 1, 1, 64, 1, 3, PLACE, 0);
        row(2, 1, 1, 64, 1, 7, KILL, 0);
        assertEquals(List.of(OwnerImport.HEADER, "world,1,64,1,ag_alice_01,minecraft:farmland"), export());
    }

    @Test
    void theRowIdOrdersRowsNotTheirTime() throws Exception {
        // CoreProtect's lookup returns rows by rowid, newest first; a later row with an earlier time still wins.
        row(2, 1, 1, 64, 1, 1, PLACE, 0);
        time = -100;
        row(1, 1, 1, 64, 1, 2, PLACE, 0);
        assertEquals(List.of(OwnerImport.HEADER, "world,1,64,1,ag_alice_01,minecraft:oak_planks"), export());
    }

    @Test
    void anOwnerWhoseMaterialIsUnmappedKeepsAnEmptyBlock() throws Exception {
        row(1, 1, 1, 64, 1, 99, PLACE, 0);
        assertEquals(List.of(OwnerImport.HEADER, "world,1,64,1,ag_alice_01,"), export());
    }
}
