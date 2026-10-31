package dev.cleanplayers.data;

import dev.cleanplayers.model.UserCacheEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserCacheTest {

    private static final UUID UUID = new UUID(0L, 2L);

    @TempDir
    Path tempDir;

    @Test
    void readsEntriesAndIgnoresInvalidUuids() throws Exception {
        Path file = tempDir.resolve("usercache.json");
        Files.writeString(file, "[{\"name\":\"Steve\",\"uuid\":\"" + UUID
                + "\",\"expiresOn\":\"2026-01-01\"},"
                + "{\"name\":\"Broken\",\"uuid\":\"not-a-uuid\"},"
                + "\"garbage\"]", StandardCharsets.UTF_8);

        UserCache.ReadResult result = UserCache.read(file);
        assertEquals(1, result.entries().size());
        UserCacheEntry entry = result.entries().get(0);
        assertEquals("Steve", entry.name());
        assertEquals(UUID, entry.uuid());
        assertTrue(result.warnings().size() >= 2);
    }

    @Test
    void purgeBacksUpAndRemovesOnlyRequestedEntries() throws Exception {
        Path file = tempDir.resolve("usercache.json");
        String original = "[{\"name\":\"Steve\",\"uuid\":\"" + UUID + "\",\"expiresOn\":\"x\"},"
                + "{\"name\":\"Alex\",\"uuid\":\"00000000-0000-0000-0000-000000000003\"}]";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        Path backup = tempDir.resolve("backup").resolve("usercache.json");
        int removed = UserCache.purge(file, backup, Set.of(UUID));
        assertEquals(1, removed);
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));

        UserCache.ReadResult result = UserCache.read(file);
        assertEquals(1, result.entries().size());
        assertEquals("Alex", result.entries().get(0).name());
    }

    @Test
    void missingFileIsNotAnError() throws Exception {
        UserCache.ReadResult result = UserCache.read(tempDir.resolve("missing.json"));
        assertEquals(0, result.entries().size());
    }
}
