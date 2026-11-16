-- The owners of a Paper shard's blocks, from its CoreProtect 24.1 SQLite database (plugins/CoreProtect/database.db),
-- for geldar-core's geldar-import-owners command.
--
-- For each position the newest block row decides, as Geldar-Core's FirstRowResolver decides from CoreProtect's lookup:
-- rows are taken newest first by rowid (the order CoreProtect's lookup returns them in), rolled-back rows (rolled_back
-- 1 or 3) are passed over, and only break (0), place (1) and click (2) rows count. A position is owned when its newest
-- row is a place by a player: a user whose name does not start with '#'. The block is the material that was placed.
--
-- Run it on a copy of the database, with the server stopped or the copy taken while it is:
--
--   sqlite3 -header -csv database.db < coreprotect-owners.sql > owners.csv
--
-- It needs SQLite 3.25 or newer (window functions). The tables carry CoreProtect's default prefix, co_; change it
-- below if the shard's table-prefix is another.
WITH newest AS (
    SELECT wid, x, y, z, user, type, action,
           ROW_NUMBER() OVER (PARTITION BY wid, x, y, z ORDER BY rowid DESC) AS n
    FROM co_block
    WHERE action IN (0, 1, 2) AND COALESCE(rolled_back, 0) NOT IN (1, 3)
)
SELECT w.world AS world, b.x AS x, b.y AS y, b.z AS z, u.user AS user, COALESCE(m.material, '') AS block
FROM newest b
JOIN co_world w ON w.id = b.wid
JOIN co_user u ON u.id = b.user
LEFT JOIN co_material_map m ON m.id = b.type
WHERE b.n = 1 AND b.action = 1 AND u.user NOT LIKE '#%' AND u.user <> ''
ORDER BY w.world, b.x, b.z, b.y;
