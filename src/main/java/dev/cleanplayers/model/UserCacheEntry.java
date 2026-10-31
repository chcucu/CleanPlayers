package dev.cleanplayers.model;

import java.util.UUID;

public record UserCacheEntry(String name, UUID uuid, String expiresOn) {
}
