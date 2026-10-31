package dev.cleanplayers.clean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cleanplayers.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    private static final UUID UUID = new UUID(0L, 1L);

    @TempDir
    Path tempDir;

    @Test
    void listsManifestsFindsPlayersAndPurgesExpiredRuns() throws Exception {
        BackupService service = new BackupService(tempDir, ZoneId.systemDefault(), Logger.getLogger("test"));

        Path run = tempDir.resolve("2020-01-01").resolve("2020-01-01_00-00-00");
        Path playerdata = run.resolve("world").resolve("world").resolve("playerdata");
        Files.createDirectories(playerdata);
        Files.write(playerdata.resolve(UUID + ".dat"), new byte[]{1, 2, 3});

        JsonObject manifest = new JsonObject();
        manifest.addProperty("bytes", 3L);
        JsonArray players = new JsonArray();
        JsonObject player = new JsonObject();
        player.addProperty("uuid", UUID.toString());
        player.addProperty("name", "OldSteve");
        players.add(player);
        manifest.add("players", players);
        Files.writeString(run.resolve("manifest.json"), JsonUtil.gson().toJson(manifest), StandardCharsets.UTF_8);

        assertEquals(1, service.listRuns(20).size());
        assertEquals(1, service.findLatestBackup(UUID).orElseThrow().files().size());

        UUID byName = service.findUuidByName("oldsteve");
        assertEquals(UUID, byName);

        BackupService.PurgeResult result = service.purgeExpired(7);
        assertEquals(1, result.runsDeleted());
        assertFalse(Files.exists(run));
        assertFalse(Files.exists(tempDir.resolve("2020-01-01")));
    }

    @Test
    void neverPurgesFoldersWithoutAManifest() throws Exception {
        BackupService service = new BackupService(tempDir, ZoneId.systemDefault(), Logger.getLogger("test"));
        Path run = tempDir.resolve("2020-01-01").resolve("2020-01-01_00-00-00");
        Files.createDirectories(run.resolve("world").resolve("playerdata"));
        Files.write(run.resolve("world").resolve("playerdata").resolve(UUID + ".dat"), new byte[]{1});

        assertEquals(0, service.listRuns(20).size());
        assertEquals(0, service.purgeExpired(7).runsDeleted());
        assertTrue(Files.exists(run));
    }

    @Test
    void keepDaysZeroDisablesPurge() throws Exception {
        BackupService service = new BackupService(tempDir, ZoneId.systemDefault(), Logger.getLogger("test"));
        Path run = tempDir.resolve("2020-01-01").resolve("2020-01-01_00-00-00");
        Files.createDirectories(run);
        Files.writeString(run.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);

        assertEquals(0, service.purgeExpired(0).runsDeleted());
        assertTrue(Files.exists(run));
    }
}
