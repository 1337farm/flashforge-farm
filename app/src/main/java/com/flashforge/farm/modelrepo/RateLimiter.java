package com.flashforge.farm.modelrepo;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe rate limiter using a sliding window algorithm.
 * Tracks request timestamps and enforces a maximum request rate.
 * 
 * This implementation uses a deque to track request timestamps within a sliding window.
 * When the number of requests in the current window exceeds the maximum, new requests are rejected.
 */
public class RateLimiter {
    private static final String TAG = "RateLimiter";
    
    private final int maxRequests;
    private final long windowSizeMs;
    private final Deque<Long> timestamps;
    private final Object lock = new Object();
    
    // Default values for search queries
    public static final int DEFAULT_MAX_REQUESTS = 10;
    public static final long DEFAULT_WINDOW_SECONDS = 60;
    
    /**
     * Create a rate limiter with specified parameters.
     * 
     * @param maxRequests Maximum number of requests allowed in the window
     * @param windowSizeMs Size of the sliding window in milliseconds
     */
    public RateLimiter(int maxRequests, long windowSizeMs) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("windowSizeMs must be positive");
        }
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.timestamps = new ArrayDeque<>(maxRequests + 1);
    }
    
    /**
     * Create a rate limiter with default values (10 requests per 60 seconds).
     */
    public RateLimiter() {
        this(DEFAULT_MAX_REQUESTS, TimeUnit.SECONDS.toMillis(DEFAULT_WINDOW_SECONDS));
    }
    
    /**
     * Create a rate limiter with requests per minute.
     */
    public static RateLimiter perMinute(int maxRequests) {
        return new RateLimiter(maxRequests, 60000);
    }
    
    /**
     * Create a rate limiter with requests per second.
     */
    public static RateLimiter perSecond(int maxRequests) {
        return new RateLimiter(maxRequests, 1000);
    }
    
    /**
     * Check if a request can be made without exceeding the rate limit.
     * This does NOT record the request - use tryAcquire() for that.
     * 
     * @return true if a request can be made, false otherwise
     */
    public boolean canAcquire() {
        synchronized (lock) {
            cleanupOldEntries();
            return timestamps.size() < maxRequests;
        }
    }
    
    /**
     * Try to acquire permission for a request.
     * If the rate limit has not been exceeded, the request is recorded and true is returned.
     * If the rate limit has been exceeded, false is returned.
     * 
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryAcquire() {
        synchronized (lock) {
            cleanupOldEntries();
            if (timestamps.size() >= maxRequests) {
                Log.d(TAG, "Rate limit exceeded: " + timestamps.size() + "/" + maxRequests);
                return false;
            }
            timestamps.addLast(System.currentTimeMillis());
            return true;
        }
    }
    
    /**
     * Acquire permission for a request, blocking if necessary.
     * This will wait until the rate limit allows a new request.
     * 
     * @param timeoutMs Maximum time to wait in milliseconds, 0 means no timeout
     * @return true if request is allowed, false if timeout occurred
     */
    public boolean acquire(long timeoutMs) {
        long start = System.currentTimeMillis();
        
        while (true) {
            synchronized (lock) {
                cleanupOldEntries();
                if (timestamps.size() < maxRequests) {
                    timestamps.addLast(System.currentTimeMillis());
                    return true;
                }
                
                // Calculate how long to wait for the oldest entry to expire
                if (timestamps.isEmpty()) {
                    // Shouldn't happen, but safety check
                    timestamps.addLast(System.currentTimeMillis());
                    return true;
                }
                
                long oldest = timestamps.peekFirst();
                long waitTimeVal = (oldest + windowSizeMs) - System.currentTimeMillis();
                
                if (waitTimeVal <= 0) {
                    // Oldest has expired, cleanup and retry
                    cleanupOldEntries();
                    continue;
                }
                
                if (timeoutMs > 0 && (System.currentTimeMillis() - start) >= timeoutMs) {
                    Log.d(TAG, "Rate limit acquire timeout");
                    return false;
                }
                
                // Wait outside the synchronized block
                lock.notifyAll();
            }
            
            // Wait for the calculated time or a notification
            try {
                long waitTimeVal2 = waitTimeVal;
                long wait = Math.min(waitTimeVal2, timeoutMs > 0 ? 
                    (start + timeoutMs) - System.currentTimeMillis() : waitTimeVal2);
                if (wait > 0) {
                    synchronized (lock) {
                        lock.wait(wait);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
    
    /**
     * Get the current number of requests in the window.
     */
    public int getCurrentCount() {
        synchronized (lock) {
            cleanupOldEntries();
            return timestamps.size();
        }
    }
    
    /**
     * Get the remaining number of requests allowed in the current window.
     */
    public int getRemaining() {
        synchronized (lock) {
            cleanupOldEntries();
            return Math.max(0, maxRequests - timestamps.size());
        }
    }
    
    /**
     * Get the time until the next request will be allowed (in milliseconds).
     * Returns 0 if a request can be made immediately.
     */
    public long getWaitTimeMs() {
        synchronized (lock) {
            cleanupOldEntries();
            if (timestamps.size() < maxRequests) {
                return 0;
            }
            if (timestamps.isEmpty()) {
                return 0;
            }
            long oldest = timestamps.peekFirst();
            long waitTime = (oldest + windowSizeMs) - System.currentTimeMillis();
            return Math.max(0, waitTime);
        }
    }
    
    /**
     * Reset the rate limiter, clearing all recorded requests.
     */
    public void reset() {
        synchronized (lock) {
            timestamps.clear();
            lock.notifyAll();
        }
    }
    
    /**
     * Remove timestamps that are outside the current window.
     * Must be called while holding the lock.
     */
    private void cleanupOldEntries() {
        long now = System.currentTimeMillis();
        long cutoff = now - windowSizeMs;
        
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
    }
    
    /**
     * Get a string representation of the rate limiter state.
     */
    @Override
    public String toString() {
        synchronized (lock) {
            cleanupOldEntries();
            return String.format("RateLimiter[%d/%d in %dms window]", 
                    timestamps.size(), maxRequests, windowSizeMs);
        }
    }
    
    /**
     * Create a rate limiter for search queries with sensible defaults.
     */
    public static RateLimiter forSearch() {
        return perMinute(10); // 10 searches per minute
    }
    
    /**
     * Create a rate limiter for model publishing with stricter limits.
     */
    public static RateLimiter forPublish() {
        return perMinute(5); // 5 publishes per minute
    }
    
    /**
     * Create a rate limiter for blob operations with higher limits.
     */
    public static RateLimiter forBlobs() {
        return perSecond(20); // 20 blob operations per second
    }
}
