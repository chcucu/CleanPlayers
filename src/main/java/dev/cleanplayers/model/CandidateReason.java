package dev.cleanplayers.model;

public enum CandidateReason {

    OFFLINE_DAYS("offline > threshold"),
    NAME_PREFIX("name prefix"),
    CACHE_ONLY("cache-only placeholder");

    private final String description;

    CandidateReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
