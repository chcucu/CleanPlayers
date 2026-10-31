package dev.cleanplayers.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.IpUtil;
import dev.cleanplayers.util.JsonUtil;
import dev.cleanplayers.util.UuidUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent name/UUID/IP mapping used for IP-based protection and name lookups.
 *
 * <p>Entries are recorded when players log in. As an additional fallback the scanner can feed
 * IP values found in playerdata NBT (vanilla does not normally store an IP there, but some
 * server setups / older data do).</p>
 */
public final class PlayerIpStore {

    private static final int MAX_IPS_PER_PLAYER = 8;

    public record IpRecord(String name, Set<String> ips, long lastSeen) {
    }

    private final Path file;
    private final Map<UUID, IpRecord> players = new HashMap<>();
    private final Map<String, UUID> nameIndex = new HashMap<>();
    private boolean dirty;

    public PlayerIpStore(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        players.clear();
        nameIndex.clear();
        dirty = false;
        if (!Files.exists(file)) {
            return;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            JsonElement rootElement = JsonParser.parseString(text);
            if (rootElement == null || !rootElement.isJsonObject()) {
                throw new IOException("player-ips.json is not a JSON object");
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonElement playersElement = root.get("players");
            if (playersElement == null || !playersElement.isJsonObject()) {
                return;
            }
            JsonObject playersObject = playersElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> mapEntry : playersObject.entrySet()) {
                UUID uuid = UuidUtil.parse(mapEntry.getKey()).orElse(null);
                if (uuid == null || !mapEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject object = mapEntry.getValue().getAsJsonObject();
                String name = string(object, "name");
                Set<String> ips = new LinkedHashSet<>();
                JsonElement ipsElement = object.get("ips");
                if (ipsElement != null && ipsElement.isJsonArray()) {
                    JsonArray array = ipsElement.getAsJsonArray();
                    for (JsonElement element : array) {
                        if (element != null && !element.isJsonNull()) {
                            String normalized = IpUtil.normalize(element.getAsString());
                            if (normalized != null) {
                                ips.add(normalized);
                            }
                        }
                    }
                }
                long lastSeen = 0L;
                JsonElement seenElement = object.get("lastSeen");
                if (seenElement != null && !seenElement.isJsonNull()) {
                    try {
                        lastSeen = seenElement.getAsLong();
                    } catch (NumberFormatException ignored) {
                        // Keep zero.
                    }
                }
                IpRecord record = new IpRecord(name, ips, lastSeen);
                players.put(uuid, record);
                if (name != null && !name.isBlank()) {
                    nameIndex.putIfAbsent(name.toLowerCase(Locale.ROOT), uuid);
                }
            }
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("Could not parse player-ips.json: " + ex.getMessage(), ex);
        }
    }

    public synchronized void record(UUID uuid, String name, String rawIp) {
        if (uuid == null) {
            return;
        }
        IpRecord existing = players.get(uuid);
        String resolvedName = name == null || name.isBlank() ? (existing == null ? null : existing.name()) : name;
        Set<String> ips = existing == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(existing.ips());
        String normalizedIp = IpUtil.normalize(rawIp);
        if (normalizedIp != null && !ips.contains(normalizedIp)) {
            ips.add(normalizedIp);
            while (ips.size() > MAX_IPS_PER_PLAYER) {
                String oldest = ips.iterator().next();
                ips.remove(oldest);
            }
        }
        players.put(uuid, new IpRecord(resolvedName, ips, System.currentTimeMillis()));
        if (resolvedName != null && !resolvedName.isBlank()) {
            nameIndex.putIfAbsent(resolvedName.toLowerCase(Locale.ROOT), uuid);
        }
        dirty = true;
    }

    public synchronized void recordName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        IpRecord existing = players.get(uuid);
        if (existing != null && name.equalsIgnoreCase(existing.name())) {
            return;
        }
        Set<String> ips = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing.ips());
        players.put(uuid, new IpRecord(name, ips,
                existing == null ? System.currentTimeMillis() : existing.lastSeen()));
        nameIndex.putIfAbsent(name.toLowerCase(Locale.ROOT), uuid);
        dirty = true;
    }

    public synchronized Set<String> ips(UUID uuid) {
        IpRecord record = players.get(uuid);
        return record == null ? Collections.emptySet() : Collections.unmodifiableSet(record.ips());
    }

    public synchronized String name(UUID uuid) {
        IpRecord record = players.get(uuid);
        return record == null ? null : record.name();
    }

    public synchronized UUID uuidByName(String name) {
        if (name == null) {
            return null;
        }
        return nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized List<String> knownNames() {
        return new ArrayList<>(nameIndex.keySet());
    }

    public synchronized boolean saveIfDirty() throws IOException {
        if (!dirty) {
            return false;
        }
        JsonObject root = new JsonObject();
        JsonObject playersObject = new JsonObject();
        for (Map.Entry<UUID, IpRecord> entry : players.entrySet()) {
            IpRecord record = entry.getValue();
            JsonObject object = new JsonObject();
            if (record.name() != null) {
                object.addProperty("name", record.name());
            }
            JsonArray ips = new JsonArray();
            for (String ip : record.ips()) {
                ips.add(ip);
            }
            object.add("ips", ips);
            object.addProperty("lastSeen", record.lastSeen());
            playersObject.add(entry.getKey().toString(), object);
        }
        root.addProperty("version", 1);
        root.add("players", playersObject);
        FileUtil.atomicWriteString(file, JsonUtil.gson().toJson(root));
        dirty = false;
        return true;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
