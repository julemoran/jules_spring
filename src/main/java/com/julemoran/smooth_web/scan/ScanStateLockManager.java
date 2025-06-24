package com.julemoran.smooth_web.scan;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages and provides access to locks for synchronizing operations on specific locations.
 * This ensures that operations related to a particular location's scan state are serialized.
 */
@Service
public class ScanStateLockManager {

    private final ConcurrentHashMap<Long, Lock> locationLocks = new ConcurrentHashMap<>();

    /**
     * Retrieves (or creates if not existing) a lock for a given location ID.
     * Callers are responsible for using this lock appropriately (e.g., in a try-finally block).
     *
     * @param locationId The ID of the location for which to get the lock.
     * @return The Lock instance for the location.
     */
    public Lock getLock(Long locationId) {
        return locationLocks.computeIfAbsent(locationId, k -> new ReentrantLock());
    }

    // No explicit releaseLock method is needed here, as the standard usage is:
    // Lock lock = scanStateLockManager.getLock(id);
    // lock.lock();
    // try { ... }
    // finally { lock.unlock(); }
    // The map will grow indefinitely if location IDs are unbounded.
    // For a system with a finite or manageable number of locations, this is acceptable.
    // If location IDs can be arbitrary and numerous, a strategy for cleaning up unused locks
    // might be needed (e.g., using WeakReferences or a periodic cleanup task),
    // but that adds complexity and is omitted for now.
}
