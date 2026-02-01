package io.flipswitch;

import com.squareup.moshi.Json;

import java.time.Instant;

/**
 * Represents an API key rotation event received via SSE.
 * This event indicates that the API key has been rotated (validUntil is set)
 * or that a rotation was aborted (validUntil is null).
 */
public record ApiKeyRotatedEvent(
    @Json(name = "validUntil") String validUntil,
    @Json(name = "timestamp") String timestamp
) {
    /**
     * Get the validUntil as an Instant.
     * @return the expiry instant, or null if rotation was aborted
     */
    public Instant getValidUntilAsInstant() {
        return validUntil != null && !validUntil.isEmpty() ? Instant.parse(validUntil) : null;
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
