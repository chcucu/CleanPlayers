package dev.cleanplayers.util;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict UUID helpers used to validate everything before it is written to disk. */
public final class UuidUtil {

    private static final Pattern DASHED = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private UuidUtil() {
    }

    public static boolean isValid(String value) {
        return value != null && DASHED.matcher(value).matches();
    }

    public static Optional<UUID> parse(String value) {
        if (!isValid(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static Optional<UUID> parseFileName(String fileName, String extension) {
        if (fileName == null || !fileName.endsWith(extension)) {
            return Optional.empty();
        }
        return parse(fileName.substring(0, fileName.length() - extension.length()));
    }

    public static String canonical(UUID uuid) {
        return uuid.toString().toLowerCase();
    }
}
