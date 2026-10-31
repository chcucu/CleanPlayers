package dev.cleanplayers.clean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Appends human-readable audit records to logs/audit-YYYY-MM-DD.log. */
public final class AuditLogger {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path logsDirectory;
    private final ZoneId zone;
    private final Logger logger;

    public AuditLogger(Path logsDirectory, ZoneId zone, Logger logger) {
        this.logsDirectory = logsDirectory;
        this.zone = zone;
        this.logger = logger;
    }

    public synchronized void write(String actor, String action, String details) {
        writeLines(actor, action, List.of(details));
    }

    public synchronized void writeLines(String actor, String action, List<String> details) {
        LocalDateTime now = LocalDateTime.now(zone);
        Path file = logsDirectory.resolve("audit-" + now.format(FILE_DATE) + ".log");
        String timestamp = now.format(TIMESTAMP);
        StringBuilder builder = new StringBuilder(details.size() * 80);
        for (String detail : details) {
            builder.append('[').append(timestamp).append("] actor=").append(actor)
                    .append(" action=").append(action).append(' ').append(detail).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(logsDirectory);
            Files.writeString(file, builder.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Could not write audit log: " + ex.getMessage());
        }
    }
}
