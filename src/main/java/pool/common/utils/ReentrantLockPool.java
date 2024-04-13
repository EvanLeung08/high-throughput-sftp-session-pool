package pool.common.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockPool {
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    public synchronized boolean lock(String key, long timeout, TimeUnit unit) {
        ReentrantLock newLock = new ReentrantLock();
        ReentrantLock existingLock = lockMap.putIfAbsent(key, newLock);
        ReentrantLock lock = (existingLock == null) ? newLock : existingLock;
        boolean isLocked = false;
        try {
            isLocked = lock.tryLock(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!isLocked && lock.isHeldByCurrentThread() && !lock.hasQueuedThreads()) {
                // If the lock is not acquired, and the current thread holds the lock, 
                // and no other threads are waiting for this lock, remove it from the map
                lockMap.remove(key, lock);
            }
        }
        return isLocked;
    }

    public synchronized boolean lock(String key) {
        ReentrantLock newLock = new ReentrantLock();
        ReentrantLock existingLock = lockMap.putIfAbsent(key, newLock);
        ReentrantLock lock = (existingLock == null) ? newLock : existingLock;
        return lock.tryLock();
    }

    public synchronized void unlock(String key) {
        ReentrantLock lock = lockMap.get(key);
        if (lock != null) {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                // If no other threads are waiting for this lock, remove it from the map
                lockMap.remove(key, lock);
            }
        }
    }
}
