package dev.cleanplayers;

import dev.cleanplayers.clean.AuditLogger;
import dev.cleanplayers.clean.BackupService;
import dev.cleanplayers.clean.CleanupService;
import dev.cleanplayers.clean.OperationLock;
import dev.cleanplayers.clean.RestoreService;
import dev.cleanplayers.clean.RunReport;
import dev.cleanplayers.command.CleanPlayersCommand;
import dev.cleanplayers.config.PluginPaths;
import dev.cleanplayers.config.Settings;
import dev.cleanplayers.data.PlayerIpStore;
import dev.cleanplayers.data.ProtectionStore;
import dev.cleanplayers.data.VanillaProtection;
import dev.cleanplayers.gate.PlayerActivityGate;
import dev.cleanplayers.listener.PlayerActivityListener;
import dev.cleanplayers.model.Candidate;
import dev.cleanplayers.model.CandidateReason;
import dev.cleanplayers.model.ScanResult;
import dev.cleanplayers.perms.BypassChecker;
import dev.cleanplayers.scan.PlayerScanner;
import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public final class CleanPlayersPlugin extends JavaPlugin {

    private static final String PREFIX = "[CleanPlayers] ";

    private PluginPaths paths;
    private volatile Settings settings;
    private ProtectionStore protectionStore;
    private PlayerIpStore ipStore;
    private PlayerActivityGate activityGate;
    private BypassChecker bypassChecker;
    private PlayerScanner scanner;
    private BackupService backupService;
    private AuditLogger auditLogger;
    private OperationLock operationLock;
    private CleanupService cleanupService;
    private RestoreService restoreService;
    private volatile ScanResult lastScan;
    private volatile boolean protectionHealthy = true;
    private volatile boolean storageHealthy = true;
    private volatile boolean configHealthy = true;

    private final List<BukkitTask> tasks = new CopyOnWriteArrayList<>();
    private BukkitTask scheduledCleanupTask;

    @Override
    public void onEnable() {
        long started = System.currentTimeMillis();
        saveDefaultConfig();
        paths = new PluginPaths(this);
        try {
            FileUtil.ensureDirectory(paths.dataFolder());
            FileUtil.ensureDirectory(paths.data());
            FileUtil.ensureDirectory(paths.logs());
        } catch (IOException ex) {
            storageHealthy = false;
            getLogger().log(Level.SEVERE, "Could not create plugin directories. Commands stay "
                    + "available but cleanups and restores are disabled until this is fixed", ex);
        }

        if (!loadSettings()) {
            configHealthy = false;
            settings = Settings.defaults();
            getLogger().severe("Fix config.yml and run /cleanplayers reload. Until then cleanups are disabled.");
        }

        protectionStore = new ProtectionStore(paths.protectFile());
        try {
            protectionStore.load();
            protectionHealthy = true;
        } catch (IOException ex) {
            protectionHealthy = false;
            getLogger().log(Level.SEVERE, "Could not load data/protect.yml. Cleanups are disabled "
                    + "until the file is fixed and /cleanplayers reload succeeds.", ex);
        }

        ipStore = new PlayerIpStore(paths.ipFile());
        try {
            ipStore.load();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not load data/player-ips.json; starting with an "
                    + "empty IP map. IP-based protection may be incomplete.", ex);
        }

        activityGate = new PlayerActivityGate(settings.activeGuardMillis());
        bypassChecker = new BypassChecker(getLogger());
        scanner = new PlayerScanner(getLogger());
        auditLogger = new AuditLogger(paths.logs(), ZoneId.systemDefault(), getLogger());
        backupService = new BackupService(paths.backups(), ZoneId.systemDefault(), getLogger());
        operationLock = new OperationLock();
        cleanupService = new CleanupService(this, operationLock);
        restoreService = new RestoreService(this, operationLock);

        CleanPlayersCommand command = new CleanPlayersCommand(this);
        var pluginCommand = getCommand("cleanplayers");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
            getLogger().info("Command registered: /" + pluginCommand.getName());
        } else {
            getLogger().severe("Command 'cleanplayers' is missing from plugin.yml");
        }
        getServer().getPluginManager().registerEvents(new PlayerActivityListener(this), this);

        scheduleBackgroundTasks();
        scheduleStartupWork();

        getLogger().info("Enabled in " + (System.currentTimeMillis() - started) + " ms. "
                + "offline-days=" + settings.offlineDays()
                + ", worlds=" + settings.worlds()
                + ", auto-cleanup=" + (settings.checkIntervalHours() == 0
                        ? "off" : settings.checkIntervalHours() + "h")
                + ", startup-cleanup=" + (settings.cleanupOnStartup() ? "on" : "off")
                + ", name-prefixes=" + settings.namePrefixes());
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        if (ipStore != null) {
            try {
                ipStore.saveIfDirty();
            } catch (IOException ex) {
                getLogger().warning("Could not save player-ips.json: " + ex.getMessage());
            }
        }
        if (protectionStore != null) {
            try {
                protectionStore.saveIfDirty();
            } catch (IOException ex) {
                getLogger().warning("Could not save protect.yml: " + ex.getMessage());
            }
        }
        getLogger().info("Disabled.");
    }

    private boolean loadSettings() {
        try {
            settings = Settings.load(getConfig());
            return true;
        } catch (IllegalArgumentException ex) {
            getLogger().log(Level.SEVERE, "Invalid config.yml: " + ex.getMessage(), ex);
            return false;
        }
    }

    private void scheduleBackgroundTasks() {
        long twentyFourHours = 24L * 60L * 60L * 20L;
        tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> purgeExpiredBackups("daily"), twentyFourHours, twentyFourHours));
        tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> {
                    try {
                        ipStore.saveIfDirty();
                    } catch (IOException ex) {
                        getLogger().warning("Could not save player-ips.json: " + ex.getMessage());
                    }
                }, 6000L, 6000L));
        tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> activityGate.prune(), 12000L, 12000L));
        rescheduleCleanup();
    }

    private void scheduleStartupWork() {
        getServer().getScheduler().runTaskLaterAsynchronously(this,
                () -> purgeExpiredBackups("startup"), 20L);
        getServer().getScheduler().runTaskAsynchronously(this, this::warmUpIndex);
        if (settings.cleanupOnStartup()) {
            getServer().getScheduler().runTaskLaterAsynchronously(this,
                    () -> cleanupService.runAsync(false, getServer().getConsoleSender()), 40L);
        }
    }

    private void warmUpIndex() {
        try {
            ScanResult scan = scanner.scan(settings, paths.serverRoot(), paths.userCacheFile(), ipStore);
            publishScan(scan);
            ipStore.saveIfDirty();
            getLogger().info("Startup index built: " + scan.playerDataCount()
                    + " player(s) across " + settings.worlds().size() + " world(s).");
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Could not build the startup player index", throwable);
        }
    }

    private void purgeExpiredBackups(String reason) {
        try {
            BackupService.PurgeResult result = backupService.purgeExpired(settings.backupKeepDays());
            if (result.runsDeleted() > 0) {
                getLogger().info("Backup retention (" + reason + "): purged "
                        + result.runsDeleted() + " run(s), freed "
                        + FileUtil.formatBytes(result.bytesFreed()) + ".");
            }
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Backup retention failed (" + reason + ")", throwable);
        }
    }

    private void rescheduleCleanup() {
        if (scheduledCleanupTask != null) {
            scheduledCleanupTask.cancel();
            tasks.remove(scheduledCleanupTask);
            scheduledCleanupTask = null;
        }
        int hours = settings.checkIntervalHours();
        if (hours <= 0) {
            return;
        }
        long periodTicks = (long) hours * 3600L * 20L;
        scheduledCleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> cleanupService.runAsync(false, getServer().getConsoleSender()),
                periodTicks, periodTicks);
        tasks.add(scheduledCleanupTask);
        getLogger().info("Automatic cleanup scheduled every " + hours + " hour(s).");
    }

    public void reload(CommandSender sender) {
        if (operationLock != null && operationLock.isLocked()) {
            sender.sendMessage(PREFIX + "Cannot reload while a cleanup or restore is running.");
            return;
        }
        Settings previous = settings;
        reloadConfig();
        Settings loaded;
        try {
            loaded = Settings.load(getConfig());
        } catch (IllegalArgumentException ex) {
            getLogger().log(Level.SEVERE, "Invalid config.yml during reload: " + ex.getMessage(), ex);
            sender.sendMessage(PREFIX + "Reload failed: " + ex.getMessage()
                    + " (previous settings are still active).");
            return;
        }

        try {
            protectionStore.load();
            protectionHealthy = true;
        } catch (IOException ex) {
            protectionHealthy = false;
            getLogger().log(Level.SEVERE, "Could not reload protect.yml; cleanups are disabled.", ex);
        }
        try {
            ipStore.load();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not reload player-ips.json: " + ex.getMessage(), ex);
        }

        settings = loaded;
        configHealthy = true;
        activityGate.setGuardMillis(loaded.activeGuardMillis());
        rescheduleCleanup();
        sender.sendMessage(PREFIX + "Configuration reloaded. offline-days=" + loaded.offlineDays()
                + ", auto-cleanup=" + (loaded.checkIntervalHours() == 0
                        ? "off" : loaded.checkIntervalHours() + "h")
                + ", protection=" + (protectionHealthy ? "ok" : "FAILED"));
        if (previous != null && previous.checkIntervalHours() != loaded.checkIntervalHours()) {
            getLogger().info("Cleanup interval changed: " + previous.checkIntervalHours()
                    + "h -> " + loaded.checkIntervalHours() + "h");
        }
    }

    public void saveProtectionAsync() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                protectionStore.saveIfDirty();
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "Could not save protect.yml", ex);
            }
        });
    }

    public void deliverRunReport(RunReport report, CommandSender sender) {
        if (!report.success) {
            String message = report.error == null ? "Cleanup failed." : report.error;
            sender.sendMessage(PREFIX + message);
            getLogger().warning(message);
            return;
        }

        List<String> lines = report.dryRun ? formatDryRun(report) : formatRealRun(report);
        for (String line : lines) {
            sender.sendMessage(line);
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            getLogger().info(lines.get(0).replace(PREFIX, ""));
        }
        if (!report.dryRun && settings.notifyAdmins()
                && (report.deletedPlayers > 0 || report.cachePurged > 0 || report.failedPlayers > 0)) {
            String shortMessage = PREFIX + "Cleanup finished: " + report.deletedPlayers
                    + " player(s) deleted, " + report.deletedFiles + " file(s), "
                    + report.cachePurged + " cache entry/entries purged.";
            notifyAdmins(shortMessage, sender);
        }
    }

    public void deliverRestoreReport(RestoreService.RestoreReport report, CommandSender sender) {
        if (!report.success()) {
            sender.sendMessage(PREFIX + "Restore failed: " + report.error());
            getLogger().warning("Restore failed: " + report.error());
            return;
        }
        sender.sendMessage(PREFIX + "Restore successful for "
                + (report.name() == null ? report.uuid() : report.name() + " (" + report.uuid() + ")")
                + " from backup run " + report.backupRunId() + ".");
        sender.sendMessage("  Restored " + report.filesRestored() + " file(s) ("
                + FileUtil.formatBytes(report.bytes()) + ") in world(s) " + report.worlds() + ".");
        if (report.rollbackFiles() > 0) {
            sender.sendMessage("  " + report.rollbackFiles()
                    + " existing file(s) were copied to backups/_restore-rollback/ first.");
        }
        getLogger().info("Restored " + report.filesRestored() + " file(s) for "
                + report.uuid() + " from " + report.backupRunId() + ".");
    }

    private List<String> formatDryRun(RunReport report) {
        List<String> lines = new ArrayList<>();
        ScanResult scan = report.scan;
        int players = scan == null ? 0 : scan.playerDataCount();
        long files = scan == null ? 0 : scan.scannedFiles();
        lines.add(PREFIX + "Dry run finished in " + formatDuration(report.durationMillis)
                + ": scanned " + players + " player(s) / " + files + " playerdata file(s); "
                + report.candidates + " candidate(s), " + FileUtil.formatBytes(report.bytes) + ".");
        appendWarnings(lines, report);
        appendSkipped(lines, report);
        appendNameDiagnostics(lines, report);

        int cap = 40;
        int shown = 0;
        for (Candidate candidate : report.preview) {
            if (shown >= cap) {
                break;
            }
            shown++;
            StringBuilder header = new StringBuilder("  - ")
                    .append(candidate.entry().displayName())
                    .append(" [").append(candidate.reason().description());
            if (candidate.offlineDays() > 0) {
                header.append(", ").append(candidate.offlineDays()).append(" day(s) offline");
            }
            header.append("] ").append(candidate.entry().fileCount()).append(" file(s)");
            lines.add(header.toString());
            int fileShown = 0;
            for (Path file : candidate.entry().allFiles()) {
                if (fileShown >= 3) {
                    lines.add("      ...");
                    break;
                }
                fileShown++;
                lines.add("      " + displayPath(file));
            }
        }
        if (report.preview.size() > shown) {
            lines.add("  ... and " + (report.preview.size() - shown)
                    + " more candidate(s); the full list is in the audit log.");
        }
        if (report.candidates == 0) {
            lines.add("  No players matched the cleanup rules.");
        } else {
            lines.add("  Nothing was deleted. Use /cleanplayers run to execute this cleanup.");
        }
        return lines;
    }

    private List<String> formatRealRun(RunReport report) {
        List<String> lines = new ArrayList<>();
        StringBuilder summary = new StringBuilder(PREFIX)
                .append("Cleanup finished in ").append(formatDuration(report.durationMillis));
        if (report.runId != null) {
            summary.append(" (run ").append(report.runId).append(")");
        }
        summary.append(": deleted ").append(report.deletedPlayers).append(" player(s) / ")
                .append(report.deletedFiles).append(" file(s) (")
                .append(FileUtil.formatBytes(report.bytes)).append(")");
        if (report.cachePurged > 0) {
            summary.append(", purged ").append(report.cachePurged).append(" cache entry/entries");
        }
        summary.append('.');
        lines.add(summary.toString());

        lines.add("  skipped online: " + report.skippedOnline
                + ", skipped (no backup): " + report.skippedBackupFailed
                + ", failed: " + report.failedPlayers + ".");
        if (report.runDirectory != null) {
            lines.add("  Backup: " + displayPath(report.runDirectory));
        } else if (report.deletedPlayers == 0) {
            lines.add("  Nothing was deleted; no backup folder was created.");
        }
        appendWarnings(lines, report);
        return lines;
    }

    private void appendWarnings(List<String> lines, RunReport report) {
        if (report.warnings.isEmpty()) {
            return;
        }
        lines.add("  Warnings:");
        for (String warning : report.warnings) {
            lines.add("    - " + warning);
        }
    }

    private void appendSkipped(List<String> lines, RunReport report) {
        if (report.skippedRules.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder("  Protected/skipped: ");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : report.skippedRules.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        lines.add(builder.toString());
    }

    private void appendNameDiagnostics(List<String> lines, RunReport report) {
        if (report.scan == null || report.scan.entries().isEmpty()) {
            return;
        }
        long total = report.scan.entries().size();
        long resolved = report.scan.entries().values().stream()
                .filter(entry -> entry.name() != null)
                .count();
        if (resolved < total) {
            lines.add("  Names resolved: " + resolved + " / " + total
                    + " (unresolved players can only match by offline-days).");
        }
        if (!settings.namePrefixes().isEmpty()) {
            int matched = (int) report.preview.stream()
                    .filter(candidate -> candidate.reason() == CandidateReason.NAME_PREFIX)
                    .count();
            lines.add("  Prefix matches for " + settings.namePrefixes() + ": " + matched + ".");
        }
    }

    private void notifyAdmins(String message, CommandSender sender) {
        for (Player player : getServer().getOnlinePlayers()) {
            if (sender instanceof Player self && self.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (player.hasPermission("cleanplayers.notify") || player.hasPermission("cleanplayers.admin")) {
                player.sendMessage(message);
            }
        }
    }

    private String displayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = paths.serverRoot();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString().replace('\\', '/');
        }
        Path data = paths.dataFolder();
        if (normalized.startsWith(data)) {
            return "plugins/CleanPlayers/" + data.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString();
    }

    private static String formatDuration(long millis) {
        if (millis < 1000L) {
            return millis + " ms";
        }
        return String.format("%.1f s", millis / 1000.0);
    }

    public void publishScan(ScanResult scan) {
        this.lastScan = scan;
    }

    public ScanResult lastScan() {
        return lastScan;
    }

    public Settings settings() {
        return settings;
    }

    public PluginPaths paths() {
        return paths;
    }

    public ProtectionStore protectionStore() {
        return protectionStore;
    }

    public PlayerIpStore ipStore() {
        return ipStore;
    }

    public PlayerActivityGate activityGate() {
        return activityGate;
    }

    public BypassChecker bypassChecker() {
        return bypassChecker;
    }

    public PlayerScanner scanner() {
        return scanner;
    }

    public BackupService backupService() {
        return backupService;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public CleanupService cleanupService() {
        return cleanupService;
    }

    public RestoreService restoreService() {
        return restoreService;
    }

    public boolean protectionHealthy() {
        return protectionHealthy;
    }

    public boolean storageHealthy() {
        return storageHealthy;
    }

    public boolean configHealthy() {
        return configHealthy;
    }
}
