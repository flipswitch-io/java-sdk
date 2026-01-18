package dev.flipswitch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Represents a flag change event received via SSE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlagChangeEvent {

    @JsonProperty("flagKey")
    private String flagKey;

    @JsonProperty("timestamp")
    private String timestamp;

    public FlagChangeEvent() {
    }

    public FlagChangeEvent(String flagKey, String timestamp) {
        this.flagKey = flagKey;
        this.timestamp = timestamp;
    }

    /**
     * Get the flag key that changed, or null for bulk invalidation.
     */
    public String getFlagKey() {
        return flagKey;
    }

    /**
     * Get the timestamp of the change as ISO string.
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Get the timestamp as an Instant.
     */
    public Instant getTimestampAsInstant() {
        return timestamp != null ? Instant.parse(timestamp) : null;
    }

    @Override
    public String toString() {
        return "FlagChangeEvent{" +
                "flagKey='" + flagKey + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
