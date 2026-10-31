package dev.cleanplayers.nbt;

import dev.cleanplayers.util.TimeUtil;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNbtReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsGzipCompressedLastPlayed() throws Exception {
        long now = System.currentTimeMillis();
        long lastPlayed = now - 100L * TimeUtil.MILLIS_PER_DAY;

        CompoundTag root = new CompoundTag();
        root.putLong("lastPlayed", lastPlayed);
        CompoundTag paper = new CompoundTag();
        paper.putLong("LastSeen", lastPlayed + 1000L);
        paper.putString("lastIP", "1.2.3.4");
        root.put("Paper", paper);

        Path file = tempDir.resolve("00000000-0000-0000-0000-000000000001.dat");
        NBTUtil.write(root, file.toFile(), true);

        PlayerNbtReader reader = new PlayerNbtReader();
        PlayerNbtReader.NbtInfo info = reader.read(file);
        assertTrue(info.success());
        assertEquals(lastPlayed, info.lastPlayed());
        assertEquals(lastPlayed + 1000L, info.lastSeen());
        assertEquals("1.2.3.4", info.lastIp());
    }

    @Test
    void fallsBackToPaperLastSeenWhenLastPlayedIsMissing() throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag paper = new CompoundTag();
        paper.putLong("LastSeen", System.currentTimeMillis() - 10L * TimeUtil.MILLIS_PER_DAY);
        root.put("Paper", paper);

        Path file = tempDir.resolve("00000000-0000-0000-0000-000000000002.dat");
        NBTUtil.write(root, file.toFile(), true);

        PlayerNbtReader.NbtInfo info = new PlayerNbtReader().read(file);
        assertTrue(info.success());
        assertTrue(info.lastPlayed() > 0L);
    }

    @Test
    void cachesByModificationTimeAndSize() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putLong("lastPlayed", 1_700_000_000_000L);
        Path file = tempDir.resolve("cached.dat");
        NBTUtil.write(root, file.toFile(), true);

        PlayerNbtReader reader = new PlayerNbtReader();
        PlayerNbtReader.NbtInfo first = reader.read(file);
        assertEquals(1_700_000_000_000L, first.lastPlayed());
        // Rewriting the file changes its timestamp, forcing a re-read.
        root.putLong("lastPlayed", 1_800_000_000_000L);
        NBTUtil.write(root, file.toFile(), true);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000L));
        PlayerNbtReader.NbtInfo second = reader.read(file);
        assertEquals(1_800_000_000_000L, second.lastPlayed());
    }

    @Test
    void rejectsGarbageFilesWithoutThrowing() throws Exception {
        Path file = tempDir.resolve("garbage.dat");
        Files.write(file, new byte[]{0x01, 0x02, 0x03});
        PlayerNbtReader.NbtInfo info = new PlayerNbtReader().read(file);
        assertFalse(info.success());
        assertNotNull(info.error());
    }
}
