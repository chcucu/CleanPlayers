package dev.cleanplayers.clean;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shared lock so a cleanup and a restore can never run at the same time. */
public final class OperationLock {

    private final AtomicBoolean locked = new AtomicBoolean();

    public boolean tryAcquire() {
        return locked.compareAndSet(false, true);
    }

    public void release() {
        locked.set(false);
    }

    public boolean isLocked() {
        return locked.get();
    }
}
