package io.flipswitch;

import com.squareup.moshi.Json;

import java.time.Instant;

/**
 * Represents an API key rotation event received via SSE.
 * This event indicates that the API key has been rotated and provides
 * information about when the current key will expire.
 */
public record ApiKeyRotatedEvent(
    @Json(name = "validUntil") String validUntil,
    @Json(name = "timestamp") String timestamp
) {
    /**
     * Get the validUntil as an Instant.
     */
    public Instant getValidUntilAsInstant() {
        return Instant.parse(validUntil);
    }

    /**
     * Get the timestamp as an Instant.
     */
    public Instant getTimestampAsInstant() {
        return Instant.parse(timestamp);
    }

    @Override
    public String toString() {
        return "ApiKeyRotatedEvent{" +
                "validUntil='" + validUntil + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
