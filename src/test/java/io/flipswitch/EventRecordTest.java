package io.flipswitch;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EventRecordTest {

    // ========================================
    // FlagChangeEvent tests
    // ========================================

    @Test
    void flagChangeEvent_getTimestampAsInstant() {
        FlagChangeEvent event = new FlagChangeEvent("my-flag", "2024-01-15T10:30:00Z");
        Instant ts = event.getTimestampAsInstant();
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), ts);
    }

    @Test
    void flagChangeEvent_toString() {
        FlagChangeEvent event = new FlagChangeEvent("my-flag", "2024-01-15T10:30:00Z");
        String str = event.toString();
        assertTrue(str.contains("my-flag"));
        assertTrue(str.contains("2024-01-15T10:30:00Z"));
        assertTrue(str.contains("FlagChangeEvent"));
    }

    // ========================================
    // ConfigUpdatedEvent tests
    // ========================================

    @Test
    void configUpdatedEvent_getTimestampAsInstant() {
        ConfigUpdatedEvent event = new ConfigUpdatedEvent("2024-06-01T12:00:00Z");
        Instant ts = event.getTimestampAsInstant();
        assertEquals(Instant.parse("2024-06-01T12:00:00Z"), ts);
    }

    @Test
    void configUpdatedEvent_toString() {
        ConfigUpdatedEvent event = new ConfigUpdatedEvent("2024-06-01T12:00:00Z");
        String str = event.toString();
        assertTrue(str.contains("2024-06-01T12:00:00Z"));
        assertTrue(str.contains("ConfigUpdatedEvent"));
    }

    // ========================================
    // ApiKeyRotatedEvent tests
    // ========================================

    @Test
    void apiKeyRotatedEvent_getTimestampAsInstant() {
        ApiKeyRotatedEvent event = new ApiKeyRotatedEvent("2024-12-01T00:00:00Z", "2024-01-01T00:00:00Z");
        Instant ts = event.getTimestampAsInstant();
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), ts);
    }

    @Test
    void apiKeyRotatedEvent_getValidUntilAsInstant_withValue() {
        ApiKeyRotatedEvent event = new ApiKeyRotatedEvent("2024-12-01T00:00:00Z", "2024-01-01T00:00:00Z");
        Instant validUntil = event.getValidUntilAsInstant();
        assertEquals(Instant.parse("2024-12-01T00:00:00Z"), validUntil);
    }

    @Test
    void apiKeyRotatedEvent_getValidUntilAsInstant_null() {
        ApiKeyRotatedEvent event = new ApiKeyRotatedEvent(null, "2024-01-01T00:00:00Z");
        assertNull(event.getValidUntilAsInstant());
    }

    @Test
    void apiKeyRotatedEvent_getValidUntilAsInstant_empty() {
        ApiKeyRotatedEvent event = new ApiKeyRotatedEvent("", "2024-01-01T00:00:00Z");
        assertNull(event.getValidUntilAsInstant());
    }

    @Test
    void apiKeyRotatedEvent_toString() {
        ApiKeyRotatedEvent event = new ApiKeyRotatedEvent("2024-12-01T00:00:00Z", "2024-01-01T00:00:00Z");
        String str = event.toString();
        assertTrue(str.contains("2024-12-01T00:00:00Z"));
        assertTrue(str.contains("2024-01-01T00:00:00Z"));
        assertTrue(str.contains("ApiKeyRotatedEvent"));
    }
}
