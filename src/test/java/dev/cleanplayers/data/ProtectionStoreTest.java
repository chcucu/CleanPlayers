package dev.cleanplayers.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionStoreTest {

    private static final UUID UUID = new UUID(0L, 4L);

    @TempDir
    Path tempDir;

    @Test
    void storesNamesUuidsAndIpsAndPersistsThem() throws Exception {
        Path file = tempDir.resolve("protect.yml");
        ProtectionStore store = new ProtectionStore(file);
        store.load();

        assertEquals(ProtectionStore.EntryType.NAME, store.add("Steve"));
        assertEquals(ProtectionStore.EntryType.UUID, store.add(UUID.toString()));
        assertEquals(ProtectionStore.EntryType.IP, store.add("1.2.3.4"));
        assertTrue(store.saveIfDirty());

        ProtectionStore reloaded = new ProtectionStore(file);
        reloaded.load();
        assertTrue(reloaded.isProtected(UUID, null, List.of()));
        assertTrue(reloaded.isProtected(new UUID(0L, 5L), "Steve", List.of()));
        assertTrue(reloaded.isProtected(new UUID(0L, 6L), "Alex", List.of("1.2.3.4")));
        assertFalse(reloaded.isProtected(new UUID(0L, 7L), "Alex", List.of("1.2.3.5")));

        ProtectionStore.Snapshot snapshot = reloaded.snapshot();
        assertTrue(snapshot.names().contains("steve"));
        assertTrue(snapshot.uuids().contains(UUID.toString()));
        assertTrue(snapshot.ips().contains("1.2.3.4"));
        assertEquals(1, snapshot.names().size());
        assertEquals(1, snapshot.uuids().size());
        assertEquals(1, snapshot.ips().size());
    }

    @Test
    void removeWorksForEachType() throws Exception {
        ProtectionStore store = new ProtectionStore(tempDir.resolve("protect.yml"));
        store.load();
        store.add("Steve");
        store.add(UUID.toString());
        store.add("1.2.3.4");

        assertTrue(store.remove("Steve"));
        assertTrue(store.remove(UUID.toString()));
        assertTrue(store.remove("1.2.3.4"));
        assertFalse(store.remove("Steve"));
        assertEquals(0, store.snapshot().names().size());
    }

    @Test
    void rejectsMalformedProtectFileInsteadOfLoadingAnEmptyList() throws Exception {
        Path file = tempDir.resolve("protect.yml");
        Files.writeString(file, "players: [unclosed\n");
        assertThrows(IOException.class, () -> new ProtectionStore(file).load());

        Path wrongType = tempDir.resolve("wrong-type.yml");
        Files.writeString(wrongType, "uuids: not-a-list\n");
        assertThrows(IOException.class, () -> new ProtectionStore(wrongType).load());

        Path badUuid = tempDir.resolve("bad-uuid.yml");
        Files.writeString(badUuid, "uuids:\n  - 1234\n");
        assertThrows(IOException.class, () -> new ProtectionStore(badUuid).load());

        Path badIp = tempDir.resolve("bad-ip.yml");
        Files.writeString(badIp, "ips:\n  - definitely-not-an-ip\n");
        assertThrows(IOException.class, () -> new ProtectionStore(badIp).load());
    }
}
