package dev.cleanplayers.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerIpStoreTest {

    private static final UUID UUID = new UUID(0L, 10L);

    @TempDir
    Path tempDir;

    @Test
    void recordsNameAndIpsThenPersists() throws Exception {
        Path file = tempDir.resolve("player-ips.json");
        PlayerIpStore store = new PlayerIpStore(file);
        store.load();
        store.record(UUID, "Steve", "1.2.3.4");
        store.record(UUID, "Steve", "5.6.7.8");
        assertTrue(store.saveIfDirty());

        PlayerIpStore reloaded = new PlayerIpStore(file);
        reloaded.load();
        assertEquals("Steve", reloaded.name(UUID));
        assertEquals(UUID, reloaded.uuidByName("steve"));
        assertTrue(reloaded.ips(UUID).contains("1.2.3.4"));
        assertTrue(reloaded.ips(UUID).contains("5.6.7.8"));
        assertEquals(2, reloaded.ips(UUID).size());
        assertTrue(reloaded.knownNames().contains("steve"));
    }

    @Test
    void handlesUnknownPlayersAndInvalidIps() throws Exception {
        PlayerIpStore store = new PlayerIpStore(tempDir.resolve("player-ips.json"));
        store.load();
        assertNull(store.name(new UUID(0L, 11L)));
        assertTrue(store.ips(new UUID(0L, 12L)).isEmpty());
        store.record(UUID, "Steve", "not-an-ip");
        assertTrue(store.ips(UUID).isEmpty());
        assertEquals("Steve", store.name(UUID));
    }
}
