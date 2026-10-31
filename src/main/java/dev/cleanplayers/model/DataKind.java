package dev.cleanplayers.model;

import java.nio.file.Path;
import java.util.UUID;

/** The three per-player data folders CleanPlayers manages. */
public enum DataKind {

    PLAYERDATA("playerdata", ".dat"),
    ADVANCEMENTS("advancements", ".json"),
    STATS("stats", ".json");

    private final String directoryName;
    private final String extension;

    DataKind(String directoryName, String extension) {
        this.directoryName = directoryName;
        this.extension = extension;
    }

    public String directoryName() {
        return directoryName;
    }

    public String extension() {
        return extension;
    }

    public String fileName(UUID uuid) {
        return uuid.toString() + extension;
    }

    public Path resolve(Path worldDataDirectory, UUID uuid) {
        return worldDataDirectory.resolve(directoryName).resolve(fileName(uuid));
    }
}
