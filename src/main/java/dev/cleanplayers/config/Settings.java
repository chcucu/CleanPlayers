package dev.cleanplayers.config;

import dev.cleanplayers.util.FileUtil;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable snapshot of config.yml, validated once per load/reload. */
public final class Settings {

    private final int offlineDays;
    private final int checkIntervalHours;
    private final int backupKeepDays;
    private final List<String> namePrefixes;
    private final List<String> worlds;
    private final int activeGuardMinutes;
    private final int maxPlayersPerRun;
    private final boolean deleteRelatedData;
    private final boolean protectWhitelist;
    private final boolean protectOps;
    private final boolean protectBanned;
    private final int ioThreads;
    private final boolean notifyAdmins;
    private final boolean purgeUsercacheOrphans;

    private Settings(int offlineDays, int checkIntervalHours, int backupKeepDays,
                     List<String> namePrefixes, List<String> worlds, int activeGuardMinutes,
                     int maxPlayersPerRun, boolean deleteRelatedData, boolean protectWhitelist,
                     boolean protectOps, boolean protectBanned, int ioThreads, boolean notifyAdmins,
                     boolean purgeUsercacheOrphans) {
        this.offlineDays = offlineDays;
        this.checkIntervalHours = checkIntervalHours;
        this.backupKeepDays = backupKeepDays;
        this.namePrefixes = namePrefixes;
        this.worlds = worlds;
        this.activeGuardMinutes = activeGuardMinutes;
        this.maxPlayersPerRun = maxPlayersPerRun;
        this.deleteRelatedData = deleteRelatedData;
        this.protectWhitelist = protectWhitelist;
        this.protectOps = protectOps;
        this.protectBanned = protectBanned;
        this.ioThreads = ioThreads;
        this.notifyAdmins = notifyAdmins;
        this.purgeUsercacheOrphans = purgeUsercacheOrphans;
    }

    public static Settings load(ConfigurationSection config) {
        int offlineDays = Math.max(0, config.getInt("offline-days", 90));
        int interval = Math.max(0, config.getInt("check-interval-hours", 0));
        if (interval > 8760) {
            throw new IllegalArgumentException("check-interval-hours must be between 0 and 8760");
        }
        int keepDays = Math.max(0, config.getInt("backup-keep-days", 7));
        int guard = Math.max(0, Math.min(1440, config.getInt("active-guard-minutes", 5)));
        int maxPerRun = Math.max(0, config.getInt("max-players-per-run", 0));

        List<String> prefixes = new ArrayList<>();
        for (String raw : config.getStringList("name-prefixes")) {
            if (raw == null) {
                continue;
            }
            String prefix = raw.trim().toLowerCase(Locale.ROOT);
            if (!prefix.isEmpty()) {
                prefixes.add(prefix);
            }
        }

        List<String> worlds = new ArrayList<>();
        for (String raw : config.getStringList("worlds")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String world = raw.trim();
            if (!FileUtil.isSafeWorldName(world)) {
                throw new IllegalArgumentException(
                        "Invalid world folder name in config: '" + world
                                + "' (only letters, digits, '_', '-' and '.' are allowed)");
            }
            if (!worlds.contains(world)) {
                worlds.add(world);
            }
        }
        if (worlds.isEmpty()) {
            throw new IllegalArgumentException("config.yml must contain at least one world in 'worlds'");
        }

        int ioThreads = Math.max(1, Math.min(8, config.getInt("io-threads", 2)));

        return new Settings(offlineDays, interval, keepDays,
                Collections.unmodifiableList(prefixes),
                Collections.unmodifiableList(worlds),
                guard, maxPerRun,
                config.getBoolean("delete-related-data", true),
                config.getBoolean("protect-vanilla-whitelist", true),
                config.getBoolean("protect-vanilla-ops", true),
                config.getBoolean("protect-vanilla-banned", true),
                ioThreads,
                config.getBoolean("notify-admins", true),
                config.getBoolean("purge-usercache-orphans", false));
    }

    public int offlineDays() {
        return offlineDays;
    }

    public int checkIntervalHours() {
        return checkIntervalHours;
    }

    public int backupKeepDays() {
        return backupKeepDays;
    }

    public List<String> namePrefixes() {
        return namePrefixes;
    }

    public List<String> worlds() {
        return worlds;
    }

    public int activeGuardMinutes() {
        return activeGuardMinutes;
    }

    public int maxPlayersPerRun() {
        return maxPlayersPerRun;
    }

    public boolean deleteRelatedData() {
        return deleteRelatedData;
    }

    public boolean protectWhitelist() {
        return protectWhitelist;
    }

    public boolean protectOps() {
        return protectOps;
    }

    public boolean protectBanned() {
        return protectBanned;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public boolean notifyAdmins() {
        return notifyAdmins;
    }

    public boolean purgeUsercacheOrphans() {
        return purgeUsercacheOrphans;
    }

    public long activeGuardMillis() {
        return activeGuardMinutes * 60_000L;
    }
}
