package dev.cleanplayers.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.cleanplayers.util.UuidUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Snapshot of the vanilla whitelist / ops / ban lists. */
public final class VanillaProtection {

    public static final VanillaProtection EMPTY = new VanillaProtection(
            Collections.emptySet(), Collections.emptySet());

    private final Set<UUID> uuids;
    private final Set<String> names;

    private VanillaProtection(Set<UUID> uuids, Set<String> names) {
        this.uuids = uuids;
        this.names = names;
    }

    public static VanillaProtection load(Path serverRoot, boolean whitelist, boolean ops, boolean banned)
            throws IOException {
        Set<UUID> uuids = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        if (whitelist) {
            readList(serverRoot.resolve("whitelist.json"), "whitelist.json", uuids, names);
        }
        if (ops) {
            readList(serverRoot.resolve("ops.json"), "ops.json", uuids, names);
        }
        if (banned) {
            readList(serverRoot.resolve("banned-players.json"), "banned-players.json", uuids, names);
        }
        return new VanillaProtection(
                Collections.unmodifiableSet(uuids),
                Collections.unmodifiableSet(names));
    }

    public boolean protects(UUID uuid, String name) {
        if (uuid != null && uuids.contains(uuid)) {
            return true;
        }
        return name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return uuids.size() + names.size();
    }

    private static void readList(Path file, String label, Set<UUID> uuids, Set<String> names)
            throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonArray array;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element == null || !element.isJsonArray()) {
                throw new IOException(label + " is not a JSON array");
            }
            array = element.getAsJsonArray();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("Could not parse " + label + ": " + ex.getMessage(), ex);
        }

        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            JsonElement uuidElement = object.get("uuid");
            if (uuidElement != null && !uuidElement.isJsonNull()) {
                UuidUtil.parse(uuidElement.getAsString()).ifPresent(uuids::add);
            }
            JsonElement nameElement = object.get("name");
            if (nameElement != null && !nameElement.isJsonNull()) {
                String name = nameElement.getAsString().trim();
                if (!name.isEmpty()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
    }
}
