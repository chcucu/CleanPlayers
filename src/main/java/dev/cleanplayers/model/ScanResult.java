package dev.cleanplayers.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Immutable result of one playerdata scan. Also acts as the in-memory UUID/name index. */
public record ScanResult(Map<UUID, PlayerEntry> entries,
                         List<UserCacheEntry> userCacheEntries,
                         List<String> warnings,
                         long durationMillis,
                         long scannedFiles) {

    public ScanResult {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        userCacheEntries = Collections.unmodifiableList(new ArrayList<>(userCacheEntries));
        warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public PlayerEntry entry(UUID uuid) {
        return entries.get(uuid);
    }

    public int playerDataCount() {
        int count = 0;
        for (PlayerEntry entry : entries.values()) {
            if (entry.hasPlayerData()) {
                count++;
            }
        }
        return count;
    }

    /** Players that only exist in usercache.json and have no playerdata anywhere. */
    public List<UserCacheEntry> cacheOnlyEntries() {
        List<UserCacheEntry> result = new ArrayList<>();
        for (UserCacheEntry cacheEntry : userCacheEntries) {
            PlayerEntry entry = entries.get(cacheEntry.uuid());
            if (entry == null || !entry.hasPlayerData()) {
                result.add(cacheEntry);
            }
        }
        return result;
    }

    public UUID findUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        for (UserCacheEntry cacheEntry : userCacheEntries) {
            if (cacheEntry.name() != null && cacheEntry.name().toLowerCase(Locale.ROOT).equals(key)) {
                return cacheEntry.uuid();
            }
        }
        for (PlayerEntry entry : entries.values()) {
            if (entry.name() != null && entry.name().toLowerCase(Locale.ROOT).equals(key)) {
                return entry.uuid();
            }
        }
        return null;
    }
}
