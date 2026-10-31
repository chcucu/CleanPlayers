package dev.cleanplayers.nbt;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.LongTag;
import net.querz.nbt.tag.StringTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads playerdata NBT through the NBT library (never a hand-written binary parser) and caches
 * results by file modification time/size so repeated scans do not re-read unchanged files.
 */
public final class PlayerNbtReader {

    private static final long MIN_PLAUSIBLE_MILLIS = 946_684_800_000L; // 2000-01-01

    private static final List<String> IP_KEYS = List.of(
            "lastIP", "lastIp", "lastip", "last_ip", "ip", "IP", "LastIP");

    public record NbtInfo(boolean success, long lastPlayed, long lastSeen, String lastIp, String error) {

        public static NbtInfo failure(String error) {
            return new NbtInfo(false, -1L, -1L, null, error);
        }
    }

    private record CacheEntry(FileTime modified, long size, NbtInfo info) {
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public NbtInfo read(Path file) {
        String key = file.toAbsolutePath().normalize().toString();
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            long size = Files.size(file);
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.size() == size && cached.modified().equals(modified)) {
                return cached.info();
            }
            NbtInfo info = parse(file);
            cache.put(key, new CacheEntry(modified, size, info));
            return info;
        } catch (IOException ex) {
            return NbtInfo.failure("Could not stat playerdata file: " + ex.getMessage());
        }
    }

    private NbtInfo parse(Path file) {
        NamedTag named;
        IOException compressedError = null;
        try {
            named = NBTUtil.read(file.toFile(), true);
        } catch (IOException ex) {
            compressedError = ex;
            try {
                named = NBTUtil.read(file.toFile(), false);
            } catch (IOException plainError) {
                String message = "Unreadable playerdata (" + plainError.getMessage()
                        + "; gzip attempt: " + compressedError.getMessage() + ")";
                return NbtInfo.failure(message);
            }
        }

        if (named == null || !(named.getTag() instanceof CompoundTag root)) {
            return NbtInfo.failure("Playerdata root tag is not a compound");
        }

        long lastPlayed = plausible(longValue(root, "lastPlayed"));
        long lastSeen = -1L;
        String lastIp = findIp(root);

        CompoundTag paper = root.getCompoundTag("Paper");
        if (paper != null) {
            long paperSeen = plausible(longValue(paper, "LastSeen"));
            if (paperSeen > 0L) {
                lastSeen = paperSeen;
            }
            long paperLogin = plausible(longValue(paper, "LastLogin"));
            if (lastPlayed <= 0L && paperLogin > 0L) {
                lastPlayed = paperLogin;
            }
            if (lastIp == null) {
                lastIp = findIp(paper);
            }
        }
        if (lastPlayed <= 0L && lastSeen > 0L) {
            // lastPlayed is normally present; LastSeen is a safe fallback for Paper data.
            lastPlayed = lastSeen;
        }

        if (lastPlayed <= 0L && lastSeen <= 0L) {
            return new NbtInfo(true, -1L, -1L, lastIp, "No usable lastPlayed/LastSeen timestamp");
        }
        return new NbtInfo(true, lastPlayed, lastSeen, lastIp, null);
    }

    private static long longValue(CompoundTag tag, String key) {
        LongTag value = tag.getLongTag(key);
        return value == null ? -1L : value.asLong();
    }

    private static long plausible(long value) {
        if (value <= 0L || value < MIN_PLAUSIBLE_MILLIS) {
            return -1L;
        }
        return value;
    }

    private static String findIp(CompoundTag tag) {
        for (String key : IP_KEYS) {
            StringTag stringTag = tag.getStringTag(key);
            if (stringTag != null) {
                String value = stringTag.getValue();
                if (value != null && !value.isBlank() && !value.toLowerCase(Locale.ROOT).equals("unknown")) {
                    return value;
                }
            }
        }
        return null;
    }
}
