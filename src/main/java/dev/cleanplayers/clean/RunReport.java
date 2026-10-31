package dev.cleanplayers.clean;

import dev.cleanplayers.config.Settings;
import dev.cleanplayers.model.Candidate;
import dev.cleanplayers.model.ScanResult;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutable summary of one dry run or real cleanup. Only touched by the run coordinator. */
public final class RunReport {

    public enum OutcomeStatus {
        DELETED,
        PARTIAL_FAILURE,
        FAILED,
        SKIPPED_ONLINE,
        SKIPPED_BACKUP_FAILED,
        SKIPPED_PROTECTED,
        CACHE_PURGED
    }

    public record PlayerOutcome(UUID uuid, String name, String reason, OutcomeStatus status,
                                int files, long bytes, String error) {
    }

    public final boolean dryRun;
    public boolean success = true;
    public String error;
    public Settings settings;
    public ScanResult scan;
    public String runId;
    public LocalDateTime createdAt;
    public int candidates;
    public int processed;
    public int deletedPlayers;
    public int deletedFiles;
    public int partialFailures;
    public int failedPlayers;
    public int skippedOnline;
    public int skippedBackupFailed;
    public int cachePurged;
    public long bytes;
    public long durationMillis;
    public final List<Candidate> preview = new ArrayList<>();
    public final List<PlayerOutcome> outcomes = new ArrayList<>();
    public final Map<String, Integer> skippedRules = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();
    public Path runDirectory;

    public RunReport(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public static RunReport failure(boolean dryRun, String error) {
        RunReport report = new RunReport(dryRun);
        report.success = false;
        report.error = error;
        return report;
    }

    public int totalCandidates() {
        return candidates;
    }
}
