package dev.cleanplayers.scan;

import dev.cleanplayers.config.Settings;
import dev.cleanplayers.data.PlayerIpStore;
import dev.cleanplayers.data.ProtectionStore;
import dev.cleanplayers.data.VanillaProtection;
import dev.cleanplayers.model.Candidate;
import dev.cleanplayers.model.CandidateReason;
import dev.cleanplayers.model.PlayerEntry;
import dev.cleanplayers.model.ScanResult;
import dev.cleanplayers.model.UserCacheEntry;
import dev.cleanplayers.perms.BypassChecker;
import dev.cleanplayers.util.IpUtil;
import dev.cleanplayers.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Applies the protection rules and decides which players are eligible for cleanup. */
public final class CandidateEvaluator {

    public record Evaluation(List<Candidate> candidates, Map<String, Integer> skipped,
                             int cacheEntriesConsidered) {
    }

    public Evaluation evaluate(Settings settings, ScanResult scan, ProtectionStore protection,
                               VanillaProtection vanilla, PlayerIpStore ipStore,
                               Predicate<UUID> online, Predicate<UUID> bypass, long now) {
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Integer> skipped = new LinkedHashMap<>();
        long thresholdDays = settings.offlineDays();

        for (PlayerEntry entry : scan.entries().values()) {
            UUID uuid = entry.uuid();
            String name = entry.name() != null ? entry.name() : ipStore.name(uuid);

            if (online.test(uuid)) {
                skipped.merge("online", 1, Integer::sum);
                continue;
            }
            if (vanilla.protects(uuid, name)) {
                skipped.merge("vanilla_protected", 1, Integer::sum);
                continue;
            }
            if (protection.isProtected(uuid, name, candidateIps(entry, ipStore))) {
                skipped.merge("protected", 1, Integer::sum);
                continue;
            }
            if (bypass.test(uuid)) {
                skipped.merge("bypass", 1, Integer::sum);
                continue;
            }

            if (entry.hasPlayerData() && matchesPrefix(name, settings.namePrefixes())) {
                candidates.add(new Candidate(entry, CandidateReason.NAME_PREFIX, days(entry, now)));
                continue;
            }

            if (entry.lastPlayed() <= 0L) {
                skipped.merge("unknown_timestamp", 1, Integer::sum);
                continue;
            }

            long days = TimeUtil.offlineDays(entry.lastPlayed(), now);
            if (days > thresholdDays) {
                candidates.add(new Candidate(entry, CandidateReason.OFFLINE_DAYS, days));
            } else {
                skipped.merge("too_recent", 1, Integer::sum);
            }
        }

        int cacheConsidered = 0;
        if (settings.purgeUsercacheOrphans()) {
            for (UserCacheEntry cacheEntry : scan.cacheOnlyEntries()) {
                PlayerEntry existing = scan.entry(cacheEntry.uuid());
                if (existing != null && existing.hasPlayerData()) {
                    continue;
                }
                cacheConsidered++;
                String name = cacheEntry.name();
                UUID uuid = cacheEntry.uuid();
                PlayerEntry synthetic = new PlayerEntry(uuid);
                synthetic.name(name);

                if (online.test(uuid)) {
                    skipped.merge("online", 1, Integer::sum);
                    continue;
                }
                if (vanilla.protects(uuid, name)) {
                    skipped.merge("vanilla_protected", 1, Integer::sum);
                    continue;
                }
                if (protection.isProtected(uuid, name, ipStore.ips(uuid))) {
                    skipped.merge("protected", 1, Integer::sum);
                    continue;
                }
                if (bypass.test(uuid)) {
                    skipped.merge("bypass", 1, Integer::sum);
                    continue;
                }
                if (matchesPrefix(name, settings.namePrefixes())) {
                    candidates.add(new Candidate(synthetic, CandidateReason.CACHE_ONLY, 0L));
                } else {
                    skipped.merge("cache_only_ignored", 1, Integer::sum);
                }
            }
        }

        return new Evaluation(candidates, skipped, cacheConsidered);
    }

    public static boolean matchesPrefix(String name, List<String> prefixes) {
        if (name == null || prefixes.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static Collection<String> candidateIps(PlayerEntry entry, PlayerIpStore ipStore) {
        Set<String> ips = new HashSet<>(ipStore.ips(entry.uuid()));
        if (entry.lastIp() != null) {
            String normalized = IpUtil.normalize(entry.lastIp());
            ips.add(normalized == null ? entry.lastIp() : normalized);
        }
        return ips;
    }

    private static long days(PlayerEntry entry, long now) {
        return entry.lastPlayed() <= 0L ? 0L : TimeUtil.offlineDays(entry.lastPlayed(), now);
    }
}
