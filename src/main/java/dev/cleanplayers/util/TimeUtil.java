package dev.cleanplayers.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class TimeUtil {

    public static final long MILLIS_PER_DAY = 86_400_000L;

    public static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeUtil() {
    }

    /** Whole offline days; clock skew (negative) is treated as zero. */
    public static long offlineDays(long lastPlayedMillis, long nowMillis) {
        if (lastPlayedMillis <= 0L) {
            return 0L;
        }
        long delta = nowMillis - lastPlayedMillis;
        return delta <= 0L ? 0L : delta / MILLIS_PER_DAY;
    }

    public static String formatInstantMillis(long millis) {
        if (millis <= 0L) {
            return "unknown";
        }
        return DISPLAY_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    public static String isoNow() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                LocalDateTime.now().atZone(ZoneId.systemDefault()));
    }

    public static LocalDateTime parseRunId(String runId) {
        try {
            return LocalDateTime.parse(runId, RUN_ID_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    public static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    public static String humanDuration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0L) {
            return hours + "h " + (minutes % 60L) + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + (seconds % 60L) + "s";
        }
        return seconds + "s";
    }

    public static Duration days(int days) {
        return Duration.ofDays(Math.max(0, days));
    }
}
