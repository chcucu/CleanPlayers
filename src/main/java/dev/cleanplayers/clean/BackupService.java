package dev.cleanplayers.clean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.cleanplayers.model.DataKind;
import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.JsonUtil;
import dev.cleanplayers.util.TimeUtil;
import dev.cleanplayers.util.UuidUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Backup layout:
 * <pre>
 * backups/
 *   YYYY-MM-DD/
 *     YYYY-MM-DD_HH-mm-ss/
 *       manifest.json
 *       world/playerdata/&lt;uuid&gt;.dat
 *       world/advancements/&lt;uuid&gt;.json
 *       world/stats/&lt;uuid&gt;.json
 *       server/usercache.json
 * </pre>
 */
public final class BackupService {

    public static final String MANIFEST_NAME = "manifest.json";
    public static final String ROLLBACK_DIRECTORY = "_restore-rollback";

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public record BackupRunInfo(String id, LocalDateTime createdAt, Path directory,
                                int players, long bytes) {
        public long createdAtMillis(ZoneId zone) {
            return createdAt.atZone(zone).toInstant().toEpochMilli();
        }
    }

    public record BackupLocation(BackupRunInfo run, Map<String, Map<DataKind, Path>> files) {
    }

    public record PurgeResult(int runsDeleted, long bytesFreed) {
    }

    public static final class BackupRun {
        private final String id;
        private final LocalDateTime createdAt;
        private final Path directory;
        private volatile boolean created;

        private BackupRun(String id, LocalDateTime createdAt, Path directory) {
            this.id = id;
            this.createdAt = createdAt;
            this.directory = directory;
        }

        public String id() {
            return id;
        }

        public LocalDateTime createdAt() {
            return createdAt;
        }

        public Path directory() {
            return directory;
        }

        public synchronized void ensureCreated() throws IOException {
            if (!created) {
                FileUtil.ensureDirectory(directory);
                created = true;
            }
        }

        public boolean created() {
            return created;
        }
    }

    private final Path root;
    private final Path rollbackRoot;
    private final ZoneId zone;
    private final Logger logger;

    public BackupService(Path root, ZoneId zone, Logger logger) {
        this.root = root;
        this.rollbackRoot = root.resolve(ROLLBACK_DIRECTORY);
        this.zone = zone;
        this.logger = logger;
    }

    public Path root() {
        return root;
    }

    public Path rollbackRoot() {
        return rollbackRoot;
    }

    public synchronized BackupRun newRun() {
        LocalDateTime now = LocalDateTime.now(zone);
        String baseId = now.format(ID_FORMAT);
        String id = baseId;
        int suffix = 1;
        Path directory;
        do {
            directory = root.resolve(now.format(TimeUtil.DATE_FORMAT)).resolve(id);
            if (!Files.exists(directory)) {
                break;
            }
            suffix++;
            id = baseId + "-" + suffix;
        } while (suffix < 100);
        return new BackupRun(id, now, directory);
    }

    public long backupFile(BackupRun run, String world, DataKind kind, UUID uuid, Path source)
            throws IOException {
        run.ensureCreated();
        Path target = run.directory()
                .resolve("world")
                .resolve(world)
                .resolve(kind.directoryName())
                .resolve(uuid.toString() + kind.extension());
        return FileUtil.copyVerified(source, target);
    }

    public void backupUserCache(BackupRun run, Path userCache) throws IOException {
        if (!Files.exists(userCache)) {
            return;
        }
        run.ensureCreated();
        FileUtil.copyVerified(userCache, run.directory().resolve("server").resolve("usercache.json"));
    }

    public void writeManifest(BackupRun run, JsonObject manifest) throws IOException {
        run.ensureCreated();
        FileUtil.atomicWriteString(run.directory().resolve(MANIFEST_NAME),
                JsonUtil.gson().toJson(manifest));
    }

    public List<BackupRunInfo> listRuns(int limit) {
        List<BackupRunInfo> runs = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return runs;
        }
        try (Stream<Path> dates = Files.list(root)) {
            List<Path> dateDirectories = dates
                    .filter(Files::isDirectory)
                    .filter(path -> TimeUtil.parseDate(path.getFileName().toString()) != null)
                    .toList();
            for (Path dateDirectory : dateDirectories) {
                try (Stream<Path> entries = Files.list(dateDirectory)) {
                    for (Path runDirectory : entries.filter(Files::isDirectory).toList()) {
                        LocalDateTime createdAt = TimeUtil.parseRunId(runDirectory.getFileName().toString());
                        if (createdAt == null || !Files.isRegularFile(runDirectory.resolve(MANIFEST_NAME))) {
                            continue;
                        }
                        JsonObject manifest = readManifest(runDirectory);
                        int players = 0;
                        long bytes = 0L;
                        if (manifest != null) {
                            players = manifest.has("players") && manifest.get("players").isJsonArray()
                                    ? manifest.getAsJsonArray("players").size() : 0;
                            bytes = manifest.has("bytes") ? manifest.get("bytes").getAsLong() : 0L;
                        }
                        runs.add(new BackupRunInfo(runDirectory.getFileName().toString(), createdAt,
                                runDirectory, players, bytes));
                    }
                }
            }
        } catch (IOException ex) {
            logger.warning("Could not list backups: " + ex.getMessage());
        }
        runs.sort(Comparator.comparing(BackupRunInfo::createdAt).reversed());
        if (limit > 0 && runs.size() > limit) {
            return runs.subList(0, limit);
        }
        return runs;
    }

    public PurgeResult purgeExpired(int keepDays) {
        if (keepDays <= 0) {
            return new PurgeResult(0, 0L);
        }
        long cutoff = System.currentTimeMillis() - (long) keepDays * TimeUtil.MILLIS_PER_DAY;
        int deleted = 0;
        long bytes = 0L;
        for (BackupRunInfo run : listRuns(0)) {
            if (run.createdAtMillis(zone) >= cutoff) {
                continue;
            }
            try {
                long size = FileUtil.directorySize(run.directory());
                FileUtil.deleteRecursively(run.directory());
                deleted++;
                bytes += size;
                logger.info("Purged expired backup " + run.id() + " (" + FileUtil.formatBytes(size) + ")");
            } catch (IOException ex) {
                logger.warning("Could not purge backup " + run.id() + ": " + ex.getMessage());
            }
        }
        deleteEmptyDateDirectories();
        return new PurgeResult(deleted, bytes);
    }

    public Optional<BackupLocation> findLatestBackup(UUID uuid) {
        BackupLocation fallback = null;
        for (BackupRunInfo run : listRuns(0)) {
            Map<String, Map<DataKind, Path>> files = collectFiles(run.directory(), uuid);
            if (files.isEmpty()) {
                continue;
            }
            boolean hasPlayerData = files.values().stream().anyMatch(kinds -> kinds.containsKey(DataKind.PLAYERDATA));
            if (hasPlayerData) {
                return Optional.of(new BackupLocation(run, files));
            }
            if (fallback == null) {
                fallback = new BackupLocation(run, files);
            }
        }
        return Optional.ofNullable(fallback);
    }

    public UUID findUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.toLowerCase(Locale.ROOT);
        for (BackupRunInfo run : listRuns(0)) {
            JsonObject manifest = readManifest(run.directory());
            if (manifest == null || !manifest.has("players") || !manifest.get("players").isJsonArray()) {
                continue;
            }
            JsonArray players = manifest.getAsJsonArray("players");
            for (JsonElement element : players) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject player = element.getAsJsonObject();
                JsonElement nameElement = player.get("name");
                if (nameElement == null || nameElement.isJsonNull()) {
                    continue;
                }
                if (target.equals(nameElement.getAsString().toLowerCase(Locale.ROOT))) {
                    JsonElement uuidElement = player.get("uuid");
                    if (uuidElement != null && !uuidElement.isJsonNull()) {
                        UUID uuid = UuidUtil.parse(uuidElement.getAsString()).orElse(null);
                        if (uuid != null) {
                            return uuid;
                        }
                    }
                }
            }
        }
        return null;
    }

    public JsonObject readManifest(Path runDirectory) {
        Path manifest = runDirectory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | JsonParseException | IllegalStateException ex) {
            logger.warning("Could not read backup manifest " + manifest + ": " + ex.getMessage());
            return null;
        }
    }

    private Map<String, Map<DataKind, Path>> collectFiles(Path runDirectory, UUID uuid) {
        Map<String, Map<DataKind, Path>> result = new LinkedHashMap<>();
        Path worldRoot = runDirectory.resolve("world");
        if (!Files.isDirectory(worldRoot)) {
            return result;
        }
        try (Stream<Path> worlds = Files.list(worldRoot)) {
            for (Path worldDirectory : worlds.filter(Files::isDirectory).toList()) {
                String world = worldDirectory.getFileName().toString();
                if (!FileUtil.isSafeWorldName(world)) {
                    continue;
                }
                for (DataKind kind : DataKind.values()) {
                    Path file = kind.resolve(worldDirectory, uuid);
                    if (Files.isRegularFile(file)) {
                        result.computeIfAbsent(world, ignored -> new LinkedHashMap<>()).put(kind, file);
                    }
                }
            }
        } catch (IOException ex) {
            logger.warning("Could not inspect backup " + runDirectory + ": " + ex.getMessage());
        }
        return result;
    }

    private void deleteEmptyDateDirectories() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> dates = Files.list(root)) {
            for (Path dateDirectory : dates.filter(Files::isDirectory).toList()) {
                if (TimeUtil.parseDate(dateDirectory.getFileName().toString()) == null) {
                    continue;
                }
                try (Stream<Path> entries = Files.list(dateDirectory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(dateDirectory);
                    }
                }
            }
        } catch (IOException ex) {
            logger.warning("Could not prune empty backup date folders: " + ex.getMessage());
        }
    }
}
