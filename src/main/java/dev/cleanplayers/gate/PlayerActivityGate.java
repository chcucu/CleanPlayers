package dev.cleanplayers.gate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks players that are online or recently came online. The cleanup pipeline checks this gate
 * immediately before every backup and deletion, so a player who logs in during a scan cancels
 * their own cleanup.
 */
public final class PlayerActivityGate {

    private static final long ONLINE = Long.MAX_VALUE;

    private final Map<UUID, Long> activeUntil = new ConcurrentHashMap<>();
    private volatile long guardMillis;

    public PlayerActivityGate(long guardMillis) {
        this.guardMillis = guardMillis;
    }

    public void setGuardMillis(long guardMillis) {
        this.guardMillis = Math.max(0L, guardMillis);
    }

    public void joining(UUID uuid) {
        if (uuid != null) {
            activeUntil.put(uuid, ONLINE);
        }
    }

    public void online(UUID uuid) {
        joining(uuid);
    }

    public void left(UUID uuid) {
        if (uuid != null) {
            activeUntil.put(uuid, System.currentTimeMillis() + guardMillis);
        }
    }

    public void clear(UUID uuid) {
        if (uuid != null) {
            activeUntil.remove(uuid);
        }
    }

    public boolean isActive(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long until = activeUntil.get(uuid);
        if (until == null) {
            return false;
        }
        if (until == ONLINE) {
            return true;
        }
        if (System.currentTimeMillis() < until) {
            return true;
        }
        activeUntil.remove(uuid, until);
        return false;
    }

    public void prune() {
        long now = System.currentTimeMillis();
        activeUntil.entrySet().removeIf(entry -> entry.getValue() != ONLINE && entry.getValue() < now);
    }

    public int size() {
        return activeUntil.size();
    }
}
