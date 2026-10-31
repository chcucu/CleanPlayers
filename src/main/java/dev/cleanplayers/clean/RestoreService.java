package dev.cleanplayers.clean;

import dev.cleanplayers.CleanPlayersPlugin;
import dev.cleanplayers.config.Settings;
import dev.cleanplayers.model.DataKind;
import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.TimeUtil;
import dev.cleanplayers.util.UuidUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/** Restores a player's data from the most recent backup that contains it. */
public final class RestoreService {

    private static final DateTimeFormatter ROLLBACK_ID =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public record RestoreReport(boolean success, String error, String input, UUID uuid, String name,
                                String backupRunId, int filesRestored, int rollbackFiles,
                                long bytes, List<String> worlds, long durationMillis) {
    }

    private final CleanPlayersPlugin plugin;
    private final OperationLock lock;

    public RestoreService(CleanPlayersPlugin plugin, OperationLock lock) {
        this.plugin = plugin;
        this.lock = lock;
    }

    public void restoreAsync(String input, CommandSender sender) {
        if (!lock.tryAcquire()) {
            sender.sendMessage("[CleanPlayers] Another cleanup or restore is already running. Please wait.");
            return;
        }
        String actor = sender.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            RestoreReport report;
            try {
                report = execute(input, actor);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "Restore failed unexpectedly", throwable);
                report = new RestoreReport(false, "Unexpected error: " + throwable.getMessage(),
                        input, null, null, null, 0, 0, 0L, List.of(), 0L);
            } finally {
                lock.release();
            }
            if (!report.success()) {
                plugin.auditLogger().write(actor, "RESTORE",
                        "input=" + input + " result=FAILED error=" + report.error());
            }
            RestoreReport result = report;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.deliverRestoreReport(result, sender));
        });
    }

    private RestoreReport execute(String input, String actor) {
        long started = System.currentTimeMillis();
        if (input == null || input.isBlank()) {
            return failure(input, "Usage: /cp restore <player name or uuid>");
        }
        if (!plugin.storageHealthy()) {
            return failure(input, "Plugin storage is not writable; refusing to restore.");
        }

        UUID uuid = resolveUuid(input.trim());
        if (uuid == null) {
            return failure(input, "Unknown player '" + input + "'. Use a name that exists in "
                    + "usercache.json, the IP store or a backup manifest, or a full UUID.");
        }

        String name = resolveName(uuid);
        if (isOnline(uuid)) {
            return failure(input, "Player " + (name == null ? uuid : name)
                    + " is online (or just logged out); refusing to restore now.");
        }

        Optional<BackupService.BackupLocation> location = plugin.backupService().findLatestBackup(uuid);
        if (location.isEmpty()) {
            return new RestoreReport(false, "No backup containing " + uuid + " was found.",
                    input, uuid, name, null, 0, 0, 0L, List.of(),
                    System.currentTimeMillis() - started);
        }
        BackupService.BackupLocation backup = location.get();

        int restored = 0;
        int rollbackFiles = 0;
        long bytes = 0L;
        List<String> worlds = new ArrayList<>();
        String rollbackId = LocalDateTime.now().format(ROLLBACK_ID);

        for (Map.Entry<String, Map<DataKind, Path>> worldEntry : backup.files().entrySet()) {
            String world = worldEntry.getKey();
            if (!FileUtil.isSafeWorldName(world)) {
                plugin.getLogger().warning("Skipping restore to unsafe world name: " + world);
                continue;
            }
            Path worldDirectory = plugin.paths().worldDirectory(world);
            for (Map.Entry<DataKind, Path> kindEntry : worldEntry.getValue().entrySet()) {
                if (isOnline(uuid)) {
                    return new RestoreReport(false,
                            "Player came online during restore; aborted before overwriting more files.",
                            input, uuid, name, backup.run().id(), restored, rollbackFiles, bytes,
                            worlds, System.currentTimeMillis() - started);
                }
                DataKind kind = kindEntry.getKey();
                Path source = kindEntry.getValue();
                Path destination = kind.resolve(worldDirectory, uuid);
                try {
                    FileUtil.ensureDirectory(destination.getParent());
                    if (Files.isRegularFile(destination)) {
                        Path rollback = plugin.paths().rollbackRoot()
                                .resolve(rollbackId)
                                .resolve(world)
                                .resolve(kind.directoryName())
                                .resolve(uuid + kind.extension());
                        FileUtil.copyVerified(destination, rollback);
                        rollbackFiles++;
                    }
                    bytes += FileUtil.copyVerified(source, destination);
                    restored++;
                    if (!worlds.contains(world)) {
                        worlds.add(world);
                    }
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not restore " + source.getFileName()
                            + " to " + world + ": " + ex.getMessage());
                    return new RestoreReport(false,
                            "Restore failed for " + world + "/" + kind.directoryName()
                                    + ": " + ex.getMessage(),
                            input, uuid, name, backup.run().id(), restored, rollbackFiles, bytes,
                            worlds, System.currentTimeMillis() - started);
                }
            }
        }

        RestoreReport report = new RestoreReport(true, null, input, uuid, name,
                backup.run().id(), restored, rollbackFiles, bytes, worlds,
                System.currentTimeMillis() - started);
        List<String> audit = new ArrayList<>();
        audit.add("uuid=" + uuid + " name=" + name + " run=" + backup.run().id()
                + " files=" + restored + " rollbackFiles=" + rollbackFiles
                + " bytes=" + bytes + " worlds=" + worlds + " result=OK");
        plugin.auditLogger().write(actor, "RESTORE", String.join(" ", audit));
        return report;
    }

    private UUID resolveUuid(String input) {
        UUID direct = UuidUtil.parse(input).orElse(null);
        if (direct != null) {
            return direct;
        }
        var scan = plugin.lastScan();
        if (scan != null) {
            UUID fromScan = scan.findUuidByName(input);
            if (fromScan != null) {
                return fromScan;
            }
        }
        UUID fromStore = plugin.ipStore().uuidByName(input);
        if (fromStore != null) {
            return fromStore;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(input)) {
                return player.getUniqueId();
            }
        }
        return plugin.backupService().findUuidByName(input);
    }

    private String resolveName(UUID uuid) {
        var scan = plugin.lastScan();
        if (scan != null) {
            var entry = scan.entry(uuid);
            if (entry != null && entry.name() != null) {
                return entry.name();
            }
            for (var cacheEntry : scan.userCacheEntries()) {
                if (cacheEntry.uuid().equals(uuid)) {
                    return cacheEntry.name();
                }
            }
        }
        String stored = plugin.ipStore().name(uuid);
        if (stored != null) {
            return stored;
        }
        Player online = plugin.getServer().getPlayer(uuid);
        return online == null ? null : online.getName();
    }

    private boolean isOnline(UUID uuid) {
        if (plugin.activityGate().isActive(uuid)) {
            return true;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            return true;
        }
        var offline = plugin.getServer().getOfflinePlayer(uuid);
        return offline != null && offline.isOnline();
    }

    private RestoreReport failure(String input, String message) {
        return new RestoreReport(false, message, input, null, null, null,
                0, 0, 0L, List.of(), 0L);
    }
}
