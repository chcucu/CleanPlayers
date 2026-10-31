package dev.cleanplayers.model;

import java.util.UUID;

public record Candidate(PlayerEntry entry, CandidateReason reason, long offlineDays) {

    public UUID uuid() {
        return entry.uuid();
    }

    public String displayName() {
        return entry.displayName();
    }

    public boolean hasFiles() {
        return entry.hasAnyFile();
    }
}
