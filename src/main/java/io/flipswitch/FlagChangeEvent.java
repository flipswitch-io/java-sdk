package io.flipswitch;

import com.squareup.moshi.Json;

import java.time.Instant;

/**
 * Represents a flag change event received via SSE.
 */
public record FlagChangeEvent(
    @Json(name = "flagKey") String flagKey,
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
        return "FlagChangeEvent{" +
                "flagKey='" + flagKey + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
