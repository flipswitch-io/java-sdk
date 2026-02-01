package io.flipswitch;

import com.squareup.moshi.Json;

import java.time.Instant;

/**
 * Represents a configuration update event received via SSE.
 * This event indicates that configuration has changed that may affect multiple flags.
 */
public record ConfigUpdatedEvent(
    @Json(name = "timestamp") String timestamp
) {
    /**
     * Get the timestamp as an Instant.
     */
    public Instant getTimestampAsInstant() {
        return Instant.parse(timestamp);
    }

    @Override
    public String toString() {
        return "ConfigUpdatedEvent{" +
                "timestamp='" + timestamp + '\'' +
                '}';
    }
}
