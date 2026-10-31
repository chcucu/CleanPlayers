package dev.cleanplayers.data;

import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.IpUtil;
import dev.cleanplayers.util.UuidUtil;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The plugin's own protection list, persisted as YAML. Thread-safe because commands mutate it
 * on the main thread while an async cleanup reads it.
 */
public final class ProtectionStore {

    public enum EntryType {
        NAME, UUID, IP
    }

    public record Snapshot(List<String> names, List<String> uuids, List<String> ips) {
    }

    private final Path file;
    private final Set<String> names = new HashSet<>();
    private final Set<UUID> uuids = new HashSet<>();
    private final Set<String> ips = new HashSet<>();
    private boolean dirty;

    public ProtectionStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the protection list strictly: malformed YAML, non-list fields or unparsable entries
     * raise an IOException instead of silently producing an empty (and therefore unsafe) list.
     */
    public synchronized void load() throws IOException {
        names.clear();
        uuids.clear();
        ips.clear();
        dirty = false;
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException ex) {
            throw new IOException("Cannot parse data/protect.yml: " + ex.getMessage(), ex);
        }
        for (String key : List.of("players", "uuids", "ips")) {
            if (yaml.contains(key) && !yaml.isList(key)) {
                throw new IOException("data/protect.yml field '" + key + "' must be a list");
            }
        }
        for (String raw : yaml.getStringList("players")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim();
            try {
                validateName(value);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid player name in data/protect.yml: " + value, ex);
            }
            names.add(value.toLowerCase(Locale.ROOT));
        }
        for (String raw : yaml.getStringList("uuids")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            UUID parsed = UuidUtil.parse(raw)
                    .orElseThrow(() -> new IOException("Invalid UUID in data/protect.yml: " + raw));
            uuids.add(parsed);
        }
        for (String raw : yaml.getStringList("ips")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = IpUtil.normalize(raw);
            if (normalized == null) {
                throw new IOException("Invalid IP address in data/protect.yml: " + raw);
            }
            ips.add(normalized);
        }
    }

    public synchronized EntryType add(String input) {
        String value = normalizeInput(input);
        UUID uuid = UuidUtil.parse(value).orElse(null);
        if (uuid != null) {
            uuids.add(uuid);
            dirty = true;
            return EntryType.UUID;
        }
        String ip = IpUtil.normalize(value);
        if (ip != null) {
            ips.add(ip);
            dirty = true;
            return EntryType.IP;
        }
        validateName(value);
        names.add(value.toLowerCase(Locale.ROOT));
        dirty = true;
        return EntryType.NAME;
    }

    public synchronized boolean remove(String input) {
        String value = normalizeInput(input);
        UUID uuid = UuidUtil.parse(value).orElse(null);
        if (uuid != null) {
            boolean removed = uuids.remove(uuid);
            dirty |= removed;
            return removed;
        }
        String ip = IpUtil.normalize(value);
        if (ip != null) {
            boolean removed = ips.remove(ip);
            dirty |= removed;
            return removed;
        }
        boolean removed = names.remove(value.toLowerCase(Locale.ROOT));
        dirty |= removed;
        return removed;
    }

    public synchronized boolean isProtected(UUID uuid, String name, Collection<String> candidateIps) {
        if (uuid != null && uuids.contains(uuid)) {
            return true;
        }
        if (name != null && names.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return IpUtil.isIpProtected(candidateIps, ips);
    }

    public synchronized Snapshot snapshot() {
        List<String> nameList = new ArrayList<>(names);
        List<String> uuidList = new ArrayList<>();
        for (UUID uuid : uuids) {
            uuidList.add(uuid.toString());
        }
        List<String> ipList = new ArrayList<>(ips);
        Collections.sort(nameList);
        Collections.sort(uuidList);
        Collections.sort(ipList);
        return new Snapshot(
                Collections.unmodifiableList(nameList),
                Collections.unmodifiableList(uuidList),
                Collections.unmodifiableList(ipList));
    }

    public synchronized boolean saveIfDirty() throws IOException {
        if (!dirty) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("players", new ArrayList<>(new LinkedHashSet<>(sortedNames())));
        yaml.set("uuids", new ArrayList<>(new LinkedHashSet<>(snapshot().uuids())));
        yaml.set("ips", new ArrayList<>(new LinkedHashSet<>(snapshot().ips())));
        Path parent = file.toAbsolutePath().getParent();
        FileUtil.ensureDirectory(parent);
        Path temp = parent.resolve(file.getFileName() + ".tmp");
        yaml.save(temp.toFile());
        FileUtil.moveAtomic(temp, file);
        dirty = false;
        return true;
    }

    private List<String> sortedNames() {
        List<String> list = new ArrayList<>(names);
        Collections.sort(list);
        return list;
    }

    private static String normalizeInput(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("value must not be empty");
        }
        return input.trim();
    }

    private static void validateName(String value) {
        if (value.contains(".") || value.contains(":")) {
            throw new IllegalArgumentException("'" + value + "' is not a valid player name or IP address");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("player name is too long");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("player names cannot contain spaces");
        }
    }
}
