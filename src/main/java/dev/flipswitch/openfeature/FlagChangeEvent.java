package dev.flipswitch.openfeature;

import java.time.Instant;

/**
 * Event received from the SSE stream when flags change.
 * Contains the environment ID and optionally the specific flag key that changed.
 */
public class FlagChangeEvent {
    private final int environmentId;
    private final String flagKey;  // null means multiple flags may have changed (e.g., segment update)
    private final Instant timestamp;

    public FlagChangeEvent(int environmentId, String flagKey, Instant timestamp) {
        this.environmentId = environmentId;
        this.flagKey = flagKey;
        this.timestamp = timestamp;
    }

    /**
     * Returns the environment ID where the change occurred.
     */
    public int getEnvironmentId() {
        return environmentId;
    }

    /**
     * Returns the specific flag key that changed, or null if multiple flags may have changed
     * (e.g., when a segment is updated, all flags using that segment may be affected).
     */
    public String getFlagKey() {
        return flagKey;
    }

    /**
     * Returns the timestamp when the change was detected.
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "FlagChangeEvent{environmentId=" + environmentId +
                ", flagKey=" + (flagKey != null ? "'" + flagKey + "'" : "null") +
                ", timestamp=" + timestamp + "}";
    }
}
