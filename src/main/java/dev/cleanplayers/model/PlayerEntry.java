package dev.cleanplayers.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything known about a player after a scan. Mutable while the scan runs, then published
 * as part of an immutable {@link ScanResult}.
 */
public final class PlayerEntry {

    private final UUID uuid;
    private String name;
    private long lastPlayed = -1L;
    private long lastSeen = -1L;
    private String lastIp;
    private boolean unreadable;
    private final Map<String, Map<DataKind, Path>> filesByWorld = new LinkedHashMap<>();

    public PlayerEntry(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public long lastPlayed() {
        return lastPlayed;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public String lastIp() {
        return lastIp;
    }

    public void lastIp(String lastIp) {
        if (lastIp != null && !lastIp.isBlank()) {
            this.lastIp = lastIp;
        }
    }

    public boolean unreadable() {
        return unreadable;
    }

    public void markUnreadable() {
        this.unreadable = true;
    }

    public void observeTimestamps(long played, long seen) {
        if (played > 0L && played > lastPlayed) {
            lastPlayed = played;
        }
        if (seen > 0L && seen > lastSeen) {
            lastSeen = seen;
        }
    }

    public void putFile(String world, DataKind kind, Path path) {
        filesByWorld.computeIfAbsent(world, ignored -> new EnumMap<>(DataKind.class)).put(kind, path);
    }

    public Map<String, Map<DataKind, Path>> filesByWorld() {
        Map<String, Map<DataKind, Path>> copy = new LinkedHashMap<>();
        filesByWorld.forEach((world, kinds) ->
                copy.put(world, Collections.unmodifiableMap(new EnumMap<>(kinds))));
        return Collections.unmodifiableMap(copy);
    }

    public List<Path> allFiles() {
        List<Path> files = new ArrayList<>();
        for (Map<DataKind, Path> kinds : filesByWorld.values()) {
            files.addAll(kinds.values());
        }
        return files;
    }

    public boolean hasPlayerData() {
        for (Map<DataKind, Path> kinds : filesByWorld.values()) {
            if (kinds.containsKey(DataKind.PLAYERDATA)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyFile() {
        return !filesByWorld.isEmpty();
    }

    public int fileCount() {
        int count = 0;
        for (Map<DataKind, Path> kinds : filesByWorld.values()) {
            count += kinds.size();
        }
        return count;
    }

    public String displayName() {
        return name == null ? uuid.toString() : name + " (" + uuid + ")";
    }
}
