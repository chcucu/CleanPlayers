package dev.cleanplayers.clean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cleanplayers.CleanPlayersPlugin;
import dev.cleanplayers.config.Settings;
import dev.cleanplayers.data.ProtectionStore;
import dev.cleanplayers.data.UserCache;
import dev.cleanplayers.data.VanillaProtection;
import dev.cleanplayers.model.Candidate;
import dev.cleanplayers.model.CandidateReason;
import dev.cleanplayers.model.DataKind;
import dev.cleanplayers.model.PlayerEntry;
import dev.cleanplayers.model.ScanResult;
import dev.cleanplayers.scan.CandidateEvaluator;
import dev.cleanplayers.util.FileUtil;
import dev.cleanplayers.util.TimeUtil;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Coordinates scanning, candidate evaluation, backup + deletion, cache purging and reporting.
 * All filesystem work happens on an async scheduler thread and a bounded I/O pool.
 */
public final class CleanupService {

    private final CleanPlayersPlugin plugin;
    private final CandidateEvaluator evaluator = new CandidateEvaluator();
    private final OperationLock lock;

    public CleanupService(CleanPlayersPlugin plugin, OperationLock lock) {
        this.plugin = plugin;
        this.lock = lock;
    }

    public boolean isRunning() {
        return lock.isLocked();
    }

    public void runAsync(boolean dryRun, CommandSender sender) {
        if (!lock.tryAcquire()) {
            sender.sendMessage("[CleanPlayers] A cleanup or restore is already running. Please wait.");
            return;
        }
        String actor = sender.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            RunReport report;
            try {
                report = execute(dryRun, actor);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "Cleanup failed unexpectedly", throwable);
                report = RunReport.failure(dryRun, "Unexpected error: " + throwable.getMessage());
                plugin.auditLogger().write(actor, "CLEANUP",
                        "result=FAILED error=" + throwable.getMessage());
            } finally {
                lock.release();
            }
            RunReport result = report;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.deliverRunReport(result, sender));
        });
    }

    private RunReport execute(boolean dryRun, String actor) throws IOException {
        long started = System.currentTimeMillis();
        Settings settings = plugin.settings();
        RunReport report = new RunReport(dryRun);
        report.settings = settings;

        if (!plugin.protectionHealthy()) {
            return RunReport.failure(dryRun,
                    "The protection list could not be loaded; refusing to run for safety. "
                            + "Fix data/protect.yml and use /cleanplayers reload.");
        }
        if (!plugin.configHealthy()) {
            return RunReport.failure(dryRun,
                    "config.yml is invalid; refusing to run for safety. Fix it and use /cleanplayers reload.");
        }
        if (!plugin.storageHealthy()) {
            return RunReport.failure(dryRun,
                    "Plugin storage is not writable; refusing to run for safety. "
                            + "Check the plugins/CleanPlayers folder permissions.");
        }

        VanillaProtection vanilla;
        try {
            vanilla = VanillaProtection.load(plugin.paths().serverRoot(),
                    settings.protectWhitelist(), settings.protectOps(), settings.protectBanned());
        } catch (IOException ex) {
            return RunReport.failure(dryRun, "Could not read vanilla protection lists: "
                    + ex.getMessage() + " - refusing to run for safety.");
        }

        ScanResult scan = plugin.scanner().scan(settings, plugin.paths().serverRoot(),
                plugin.paths().userCacheFile(), plugin.ipStore());
        plugin.publishScan(scan);
        report.scan = scan;

        CandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
                settings, scan, plugin.protectionStore(), vanilla, plugin.ipStore(),
                uuid -> plugin.activityGate().isActive(uuid)
                        || plugin.getServer().getPlayer(uuid) != null,
                plugin.bypassChecker()::isBypassed,
                System.currentTimeMillis());
        report.candidates = evaluation.candidates().size();
        report.skippedRules.putAll(evaluation.skipped());
        if (!settings.purgeUsercacheOrphans() && evaluation.cacheEntriesConsidered() > 0) {
            report.warnings.add(evaluation.cacheEntriesConsidered()
                    + " usercache-only shell account(s) found but not cleaned; "
                    + "set purge-usercache-orphans: true to remove them.");
        }
        for (String warning : scan.warnings()) {
            report.warnings.add(warning);
        }

        if (dryRun) {
            report.preview.addAll(evaluation.candidates());
            for (Candidate candidate : evaluation.candidates()) {
                for (Path file : candidate.entry().allFiles()) {
                    report.bytes += FileUtil.fileSize(file);
                }
            }
            report.durationMillis = System.currentTimeMillis() - started;
            writeDryRunAudit(actor, report, evaluation.candidates());
            return report;
        }

        FileUtil.requireWritable(plugin.paths().backups());

        List<Candidate> candidates = new ArrayList<>(evaluation.candidates());
        if (settings.maxPlayersPerRun() > 0 && candidates.size() > settings.maxPlayersPerRun()) {
            report.warnings.add("Candidate list was limited to max-players-per-run="
                    + settings.maxPlayersPerRun() + " (" + candidates.size() + " eligible).");
            candidates = new ArrayList<>(candidates.subList(0, settings.maxPlayersPerRun()));
        }
        report.candidates = candidates.size();

        BackupService.BackupRun run = plugin.backupService().newRun();
        report.runId = run.id();
        report.createdAt = run.createdAt();

        List<Candidate> fileCandidates = new ArrayList<>();
        List<Candidate> cacheCandidates = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.reason() == CandidateReason.CACHE_ONLY || !candidate.hasFiles()) {
                cacheCandidates.add(candidate);
            } else {
                fileCandidates.add(candidate);
            }
        }

        runFileCandidates(settings, run, fileCandidates, report);
        try {
            purgeCacheCandidates(settings, run, cacheCandidates, report);
        } catch (IOException ex) {
            report.warnings.add("usercache purge failed: " + ex.getMessage());
            plugin.getLogger().warning("usercache purge failed: " + ex.getMessage());
        }

        report.deletedPlayers = (int) report.outcomes.stream()
                .filter(outcome -> outcome.status() == RunReport.OutcomeStatus.DELETED
                        || outcome.status() == RunReport.OutcomeStatus.PARTIAL_FAILURE)
                .count();
        report.deletedFiles = report.outcomes.stream()
                .filter(outcome -> outcome.status() == RunReport.OutcomeStatus.DELETED
                        || outcome.status() == RunReport.OutcomeStatus.PARTIAL_FAILURE)
                .mapToInt(RunReport.PlayerOutcome::files)
                .sum();
        report.bytes = report.outcomes.stream().mapToLong(RunReport.PlayerOutcome::bytes).sum();
        report.skippedOnline = (int) report.outcomes.stream()
                .filter(outcome -> outcome.status() == RunReport.OutcomeStatus.SKIPPED_ONLINE)
                .count();
        report.skippedBackupFailed = (int) report.outcomes.stream()
                .filter(outcome -> outcome.status() == RunReport.OutcomeStatus.SKIPPED_BACKUP_FAILED)
                .count();
        report.failedPlayers = (int) report.outcomes.stream()
                .filter(outcome -> outcome.status() == RunReport.OutcomeStatus.PARTIAL_FAILURE
                        || outcome.status() == RunReport.OutcomeStatus.FAILED)
                .count();

        if (run.created()) {
            report.runDirectory = run.directory();
            try {
                writeManifest(run, report, actor);
            } catch (IOException ex) {
                report.warnings.add("Could not write backup manifest: " + ex.getMessage());
                plugin.getLogger().warning("Could not write backup manifest: " + ex.getMessage());
            }
        } else {
            report.runId = null;
            report.createdAt = null;
        }

        report.durationMillis = System.currentTimeMillis() - started;
        writeRunAudit(actor, report);
        return report;
    }

    private void runFileCandidates(Settings settings, BackupService.BackupRun run,
                                   List<Candidate> candidates, RunReport report) throws IOException {
        if (candidates.isEmpty()) {
            return;
        }
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "CleanPlayers-IO-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService pool = Executors.newFixedThreadPool(settings.ioThreads(), factory);
        try {
            List<Callable<RunReport.PlayerOutcome>> tasks = new ArrayList<>(candidates.size());
            for (Candidate candidate : candidates) {
                tasks.add(() -> processCandidate(run, candidate));
            }
            List<Future<RunReport.PlayerOutcome>> futures = pool.invokeAll(tasks);
            for (Future<RunReport.PlayerOutcome> future : futures) {
                try {
                    report.outcomes.add(future.get());
                } catch (Exception ex) {
                    report.outcomes.add(new RunReport.PlayerOutcome(null, null, "unknown",
                            RunReport.OutcomeStatus.FAILED, 0, 0L, ex.getMessage()));
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Cleanup interrupted", ex);
        } finally {
            pool.shutdownNow();
        }
    }

    private RunReport.PlayerOutcome processCandidate(BackupService.BackupRun run, Candidate candidate) {
        PlayerEntry entry = candidate.entry();
        UUID uuid = entry.uuid();
        if (isOnline(uuid)) {
            return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                    RunReport.OutcomeStatus.SKIPPED_ONLINE, 0, 0L, "player is online");
        }

        List<Path> verifiedFiles = new ArrayList<>();
        for (Map.Entry<String, Map<DataKind, Path>> worldEntry : entry.filesByWorld().entrySet()) {
            String world = worldEntry.getKey();
            for (Map.Entry<DataKind, Path> kindEntry : worldEntry.getValue().entrySet()) {
                DataKind kind = kindEntry.getKey();
                Path file = kindEntry.getValue();
                Path expectedDirectory = plugin.paths().worldDirectory(world).resolve(kind.directoryName());
                String expectedName = uuid + kind.extension();
                if (!Files.isRegularFile(file)
                        || !FileUtil.isWithin(file, expectedDirectory)
                        || !file.getFileName().toString().equals(expectedName)) {
                    plugin.getLogger().warning("Skipping unexpected file path during cleanup: " + file);
                    continue;
                }
                verifiedFiles.add(file);
            }
        }
        if (verifiedFiles.isEmpty()) {
            return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                    RunReport.OutcomeStatus.FAILED, 0, 0L, "no existing files");
        }

        long bytes = 0L;
        int backedUp = 0;
        for (Map.Entry<String, Map<DataKind, Path>> worldEntry : entry.filesByWorld().entrySet()) {
            String world = worldEntry.getKey();
            for (Map.Entry<DataKind, Path> kindEntry : worldEntry.getValue().entrySet()) {
                Path file = kindEntry.getValue();
                if (!verifiedFiles.contains(file)) {
                    continue;
                }
                try {
                    bytes += plugin.backupService().backupFile(run, world, kindEntry.getKey(), uuid, file);
                    backedUp++;
                } catch (IOException ex) {
                    return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                            RunReport.OutcomeStatus.SKIPPED_BACKUP_FAILED, 0, bytes,
                            "backup failed: " + ex.getMessage());
                }
            }
        }
        if (backedUp == 0) {
            return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                    RunReport.OutcomeStatus.SKIPPED_BACKUP_FAILED, 0, 0L, "no files backed up");
        }

        // A player may have joined while the backup was being written: never delete in that case.
        if (isOnline(uuid)) {
            return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                    RunReport.OutcomeStatus.SKIPPED_ONLINE, 0, bytes, "player joined during cleanup");
        }

        int deleted = 0;
        String error = null;
        for (Path file : verifiedFiles) {
            try {
                Files.deleteIfExists(file);
                deleted++;
            } catch (IOException ex) {
                error = "delete failed: " + ex.getMessage();
                plugin.getLogger().warning("Could not delete " + file + ": " + ex.getMessage());
            }
        }
        if (error != null) {
            return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                    RunReport.OutcomeStatus.PARTIAL_FAILURE, deleted, bytes, error);
        }
        return new RunReport.PlayerOutcome(uuid, entry.name(), candidate.reason().name(),
                RunReport.OutcomeStatus.DELETED, deleted, bytes, null);
    }

    private void purgeCacheCandidates(Settings settings, BackupService.BackupRun run,
                                      List<Candidate> candidates, RunReport report) throws IOException {
        if (candidates.isEmpty() || !settings.purgeUsercacheOrphans()) {
            return;
        }
        Set<UUID> toRemove = new HashSet<>();
        for (Candidate candidate : candidates) {
            UUID uuid = candidate.uuid();
            String name = candidate.entry().name();
            if (isOnline(uuid) || hasPlayerData(uuid)) {
                report.outcomes.add(new RunReport.PlayerOutcome(uuid, name,
                        CandidateReason.CACHE_ONLY.name(), RunReport.OutcomeStatus.SKIPPED_ONLINE,
                        0, 0L, "player online or data present"));
                continue;
            }
            ProtectionStore protection = plugin.protectionStore();
            if (protection.isProtected(uuid, name, plugin.ipStore().ips(uuid))) {
                report.outcomes.add(new RunReport.PlayerOutcome(uuid, name,
                        CandidateReason.CACHE_ONLY.name(), RunReport.OutcomeStatus.SKIPPED_PROTECTED,
                        0, 0L, "protected (re-check)"));
                continue;
            }
            toRemove.add(uuid);
        }

        if (toRemove.isEmpty()) {
            return;
        }

        run.ensureCreated();
        Path backupTarget = run.directory().resolve("server").resolve("usercache.json");
        int removed = UserCache.purge(plugin.paths().userCacheFile(), backupTarget, toRemove);
        report.cachePurged = removed;
        for (UUID uuid : toRemove) {
            report.outcomes.add(new RunReport.PlayerOutcome(uuid, null,
                    CandidateReason.CACHE_ONLY.name(), RunReport.OutcomeStatus.CACHE_PURGED,
                    0, 0L, null));
        }
        if (removed < toRemove.size()) {
            report.warnings.add("usercache purge removed " + removed + " of " + toRemove.size()
                    + " entries (some were already absent).");
        }
    }

    private boolean isOnline(UUID uuid) {
        return plugin.activityGate().isActive(uuid) || plugin.getServer().getPlayer(uuid) != null;
    }

    private boolean hasPlayerData(UUID uuid) {
        for (String world : plugin.settings().worlds()) {
            Path file = plugin.paths().worldDirectory(world)
                    .resolve(DataKind.PLAYERDATA.directoryName())
                    .resolve(uuid + DataKind.PLAYERDATA.extension());
            if (Files.isRegularFile(file)) {
                return true;
            }
        }
        return false;
    }

    private void writeManifest(BackupService.BackupRun run, RunReport report, String actor) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", 1);
        manifest.addProperty("runId", run.id());
        manifest.addProperty("createdAt", TimeUtil.isoNow());
        manifest.addProperty("actor", actor);
        manifest.addProperty("dryRun", false);
        manifest.addProperty("deletedPlayers", report.deletedPlayers);
        manifest.addProperty("deletedFiles", report.deletedFiles);
        manifest.addProperty("cachePurged", report.cachePurged);
        manifest.addProperty("bytes", report.bytes);
        manifest.addProperty("failedPlayers", report.failedPlayers);

        JsonArray players = new JsonArray();
        for (RunReport.PlayerOutcome outcome : report.outcomes) {
            JsonObject player = new JsonObject();
            if (outcome.uuid() != null) {
                player.addProperty("uuid", outcome.uuid().toString());
            }
            if (outcome.name() != null) {
                player.addProperty("name", outcome.name());
            }
            player.addProperty("reason", outcome.reason());
            player.addProperty("status", outcome.status().name());
            player.addProperty("files", outcome.files());
            player.addProperty("bytes", outcome.bytes());
            if (outcome.error() != null) {
                player.addProperty("error", outcome.error());
            }
            players.add(player);
        }
        manifest.add("players", players);
        plugin.backupService().writeManifest(run, manifest);
    }

    private void writeDryRunAudit(String actor, RunReport report, List<Candidate> candidates) {
        List<String> lines = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int fileCount = candidate.entry().fileCount();
            lines.add("uuid=" + candidate.uuid() + " name=" + candidate.entry().name()
                    + " reason=" + candidate.reason().name() + " days=" + candidate.offlineDays()
                    + " files=" + fileCount);
        }
        lines.add("summary scanned=" + report.scan.playerDataCount() + " candidates=" + report.candidates
                + " bytes=" + report.bytes + " duration=" + report.durationMillis + "ms");
        plugin.auditLogger().writeLines(actor, "DRYRUN", lines);
    }

    private void writeRunAudit(String actor, RunReport report) {
        List<String> lines = new ArrayList<>();
        for (RunReport.PlayerOutcome outcome : report.outcomes) {
            lines.add("uuid=" + outcome.uuid() + " name=" + outcome.name()
                    + " reason=" + outcome.reason() + " status=" + outcome.status()
                    + " files=" + outcome.files() + " bytes=" + outcome.bytes()
                    + (outcome.error() == null ? "" : " error=" + outcome.error()));
        }
        lines.add("summary run=" + report.runId + " deletedPlayers=" + report.deletedPlayers
                + " deletedFiles=" + report.deletedFiles + " cachePurged=" + report.cachePurged
                + " failed=" + report.failedPlayers + " skippedOnline=" + report.skippedOnline
                + " skippedBackup=" + report.skippedBackupFailed + " bytes=" + report.bytes
                + " duration=" + report.durationMillis + "ms");
        plugin.auditLogger().writeLines(actor, "CLEANUP", lines);
    }
}
