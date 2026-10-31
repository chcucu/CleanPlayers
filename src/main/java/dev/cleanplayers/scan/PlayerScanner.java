package dev.cleanplayers.scan;

import dev.cleanplayers.config.Settings;
import dev.cleanplayers.data.PlayerIpStore;
import dev.cleanplayers.data.UserCache;
import dev.cleanplayers.model.DataKind;
import dev.cleanplayers.model.PlayerEntry;
import dev.cleanplayers.model.ScanResult;
import dev.cleanplayers.model.UserCacheEntry;
import dev.cleanplayers.nbt.PlayerNbtReader;
import dev.cleanplayers.util.UuidUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Async filesystem scan that builds the per-run UUID index. */
public final class PlayerScanner {

    private static final int MAX_DETAILED_WARNINGS = 25;

    private final Logger logger;
    private final PlayerNbtReader nbt = new PlayerNbtReader();

    public PlayerScanner(Logger logger) {
        this.logger = logger;
    }

    public ScanResult scan(Settings settings, Path serverRoot, Path userCacheFile, PlayerIpStore ipStore)
            throws IOException {
        long started = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        List<String> detailWarnings = new ArrayList<>();

        UserCache.ReadResult cacheResult = UserCache.read(userCacheFile);
        warnings.addAll(cacheResult.warnings());
        Map<UUID, UserCacheEntry> cacheByUuid = new HashMap<>();
        for (UserCacheEntry entry : cacheResult.entries()) {
            cacheByUuid.putIfAbsent(entry.uuid(), entry);
        }

        Map<UUID, PlayerEntry> entries = new LinkedHashMap<>();
        long scannedFiles = 0L;
        int invalidNames = 0;
        int unreadableFiles = 0;
        int missingWorlds = 0;

        for (String world : settings.worlds()) {
            Path worldDirectory = serverRoot.resolve(world);
            Path playerdataDirectory = worldDirectory.resolve(DataKind.PLAYERDATA.directoryName());
            if (!Files.isDirectory(playerdataDirectory)) {
                missingWorlds++;
                continue;
            }

            List<Path> dataFiles;
            try (Stream<Path> stream = Files.list(playerdataDirectory)) {
                dataFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(DataKind.PLAYERDATA.extension()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }

            for (Path dataFile : dataFiles) {
                UUID uuid = UuidUtil.parseFileName(dataFile.getFileName().toString(),
                        DataKind.PLAYERDATA.extension()).orElse(null);
                if (uuid == null) {
                    invalidNames++;
                    addDetail(detailWarnings, "Ignoring playerdata file with invalid UUID name: "
                            + world + "/" + dataFile.getFileName());
                    continue;
                }

                scannedFiles++;
                PlayerEntry entry = entries.computeIfAbsent(uuid, PlayerEntry::new);
                entry.putFile(world, DataKind.PLAYERDATA, dataFile);

                PlayerNbtReader.NbtInfo info = nbt.read(dataFile);
                if (!info.success()) {
                    unreadableFiles++;
                    entry.markUnreadable();
                    addDetail(detailWarnings, "Unreadable playerdata " + world + "/"
                            + dataFile.getFileName() + ": " + info.error());
                } else {
                    entry.observeTimestamps(info.lastPlayed(), info.lastSeen());
                    if (info.lastIp() != null) {
                        entry.lastIp(info.lastIp());
                        ipStore.record(uuid, null, info.lastIp());
                    }
                }

                if (settings.deleteRelatedData()) {
                    addIfExists(entry, world, DataKind.ADVANCEMENTS, uuid, worldDirectory);
                    addIfExists(entry, world, DataKind.STATS, uuid, worldDirectory);
                }
            }
        }

        if (missingWorlds > 0) {
            warnings.add(missingWorlds + " configured world folder(s) have no playerdata directory");
        }
        if (invalidNames > 0) {
            warnings.add(invalidNames + " playerdata file(s) with an invalid UUID name were ignored");
        }
        if (unreadableFiles > 0) {
            warnings.add(unreadableFiles + " playerdata file(s) could not be read and will not be cleaned");
        }
        warnings.addAll(detailWarnings);

        // Attach names from usercache.json (authoritative) and from the IP store (historical).
        for (UserCacheEntry cacheEntry : cacheResult.entries()) {
            PlayerEntry entry = entries.get(cacheEntry.uuid());
            if (entry != null) {
                entry.name(cacheEntry.name());
            }
        }
        for (PlayerEntry entry : entries.values()) {
            if (entry.name() == null) {
                entry.name(ipStore.name(entry.uuid()));
            }
        }

        long duration = System.currentTimeMillis() - started;
        long scannedFileCount = scannedFiles;
        logger.fine(() -> "Scanned " + scannedFileCount + " playerdata file(s) in " + duration + " ms");
        return new ScanResult(entries, cacheResult.entries(), warnings, duration, scannedFileCount);
    }

    private static void addIfExists(PlayerEntry entry, String world, DataKind kind,
                                    UUID uuid, Path worldDirectory) {
        Path path = kind.resolve(worldDirectory, uuid);
        if (Files.isRegularFile(path)) {
            entry.putFile(world, kind, path);
        }
    }

    private static void addDetail(List<String> details, String message) {
        if (details.size() < MAX_DETAILED_WARNINGS) {
            details.add(message);
        }
    }
}
