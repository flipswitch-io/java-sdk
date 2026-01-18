package dev.flipswitch;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory cache for flag values with TTL support.
 * Automatically invalidated by SSE events.
 */
public class FlagCache {

    private static class CacheEntry<T> {
        final T value;
        final Instant timestamp;

        CacheEntry(T value) {
            this.value = value;
            this.timestamp = Instant.now();
        }

        boolean isExpired(Duration ttl) {
            return Duration.between(timestamp, Instant.now()).compareTo(ttl) > 0;
        }
    }

    private final ConcurrentHashMap<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    /**
     * Create a new cache with the specified TTL.
     *
     * @param ttl Time-to-live for cache entries
     */
    public FlagCache(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Create a new cache with a 60-second TTL.
     */
    public FlagCache() {
        this(Duration.ofSeconds(60));
    }

    /**
     * Get a cached value if it exists and is not expired.
     *
     * @param key The cache key
     * @param <T> The value type
     * @return The cached value, or null if not found or expired
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        CacheEntry<?> entry = cache.get(key);
        if (entry == null) {
            return null;
        }

        if (entry.isExpired(ttl)) {
            cache.remove(key);
            return null;
        }

        return (T) entry.value;
    }

    /**
     * Set a value in the cache.
     *
     * @param key   The cache key
     * @param value The value to cache
     * @param <T>   The value type
     */
    public <T> void set(String key, T value) {
        cache.put(key, new CacheEntry<>(value));
    }

    /**
     * Invalidate a specific key.
     *
     * @param key The key to invalidate
     */
    public void invalidate(String key) {
        cache.remove(key);
    }

    /**
     * Invalidate all keys.
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Handle a flag change event from SSE.
     * Invalidates the specific flag or all flags if flagKey is null.
     *
     * @param event The flag change event
     */
    public void handleFlagChange(FlagChangeEvent event) {
        if (event.getFlagKey() != null) {
            invalidate(event.getFlagKey());
        } else {
            invalidateAll();
        }
    }

    /**
     * Get the number of entries in the cache.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Clear the entire cache.
     */
    public void clear() {
        cache.clear();
    }
}
