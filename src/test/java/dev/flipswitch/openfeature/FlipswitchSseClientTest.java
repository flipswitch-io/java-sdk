package dev.flipswitch.openfeature;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FlipswitchSseClientTest {

    @Test
    void constructor_createsClientWithoutConnecting() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-api-key")
                .streamingEnabled(true)
                .build();

        AtomicReference<FlagChangeEvent> receivedEvent = new AtomicReference<>();

        // Should not throw, should not connect yet
        FlipswitchSseClient client = new FlipswitchSseClient(
                config,
                receivedEvent::set,
                () -> {}
        );

        assertNotNull(client);
        assertNull(receivedEvent.get());
        client.close();
    }

    @Test
    void close_canBeCalledMultipleTimes() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-api-key")
                .streamingEnabled(true)
                .build();

        FlipswitchSseClient client = new FlipswitchSseClient(
                config,
                event -> {},
                () -> {}
        );

        // Should not throw
        client.close();
        client.close();
        client.close();
    }

    @Test
    void flagChangeEvent_hasTimestamp() {
        Instant now = Instant.now();
        FlagChangeEvent event = new FlagChangeEvent(now);

        assertEquals(now, event.getTimestamp());
        assertTrue(event.toString().contains(now.toString()));
    }

    @Test
    void configWithStreaming_hasCorrectDefaults() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-api-key")
                .streamingEnabled(true)
                .build();

        assertTrue(config.isStreamingEnabled());
        assertEquals(1000, config.getReconnectDelayMs());
        assertEquals(30000, config.getMaxReconnectDelayMs());
        assertEquals(60000, config.getHeartbeatTimeoutMs());
    }

    @Test
    void configWithCustomStreamingValues_usesCustomValues() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-api-key")
                .streamingEnabled(true)
                .reconnectDelayMs(2000)
                .maxReconnectDelayMs(60000)
                .heartbeatTimeoutMs(120000)
                .build();

        assertEquals(2000, config.getReconnectDelayMs());
        assertEquals(60000, config.getMaxReconnectDelayMs());
        assertEquals(120000, config.getHeartbeatTimeoutMs());
    }

    @Test
    void configWithoutStreaming_hasFalseDefault() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-api-key")
                .build();

        assertFalse(config.isStreamingEnabled());
    }
}
