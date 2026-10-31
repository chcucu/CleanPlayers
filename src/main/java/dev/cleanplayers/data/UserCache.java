package dev.cleanplayers.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.cleanplayers.model.UserCacheEntry;
import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.JsonUtil;
import dev.cleanplayers.util.UuidUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reader/writer for the vanilla {@code usercache.json}. */
public final class UserCache {

    private UserCache() {
    }

    public record ReadResult(List<UserCacheEntry> entries, List<String> warnings) {
    }

    public static ReadResult read(Path file) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<UserCacheEntry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return new ReadResult(entries, warnings);
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonArray array;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element == null || !element.isJsonArray()) {
                throw new IOException("usercache.json is not a JSON array");
            }
            array = element.getAsJsonArray();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("Could not parse usercache.json: " + ex.getMessage(), ex);
        }

        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element == null || !element.isJsonObject()) {
                warnings.add("usercache entry #" + i + " is not an object; ignored");
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = getString(object, "name");
            String rawUuid = getString(object, "uuid");
            UUID uuid = UuidUtil.parse(rawUuid).orElse(null);
            if (uuid == null) {
                warnings.add("usercache entry '" + (name == null ? "?" : name)
                        + "' has an invalid uuid; ignored");
                continue;
            }
            if (!seen.add(uuid)) {
                continue;
            }
            entries.add(new UserCacheEntry(name, uuid, getString(object, "expiresOn")));
        }
        return new ReadResult(entries, warnings);
    }

    /**
     * Backs up the current file, then rewrites it without the given UUIDs. Returns how many
     * entries were removed. Missing file or unknown UUIDs are left untouched.
     */
    public static int purge(Path file, Path backupTarget, Set<UUID> uuidsToRemove) throws IOException {
        if (uuidsToRemove.isEmpty() || !Files.exists(file)) {
            return 0;
        }
        FileUtil.copyVerified(file, backupTarget);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonArray array;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element == null || !element.isJsonArray()) {
                throw new IOException("usercache.json is not a JSON array");
            }
            array = element.getAsJsonArray();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("Could not parse usercache.json: " + ex.getMessage(), ex);
        }

        JsonArray kept = new JsonArray();
        int removed = 0;
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                kept.add(element);
                continue;
            }
            UUID uuid = UuidUtil.parse(getString(element.getAsJsonObject(), "uuid")).orElse(null);
            if (uuid != null && uuidsToRemove.contains(uuid)) {
                removed++;
            } else {
                kept.add(element);
            }
        }

        if (removed > 0) {
            FileUtil.atomicWriteString(file, JsonUtil.gson().toJson(kept));
        }
        return removed;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
