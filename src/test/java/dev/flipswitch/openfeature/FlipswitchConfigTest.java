package dev.flipswitch.openfeature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlipswitchConfigTest {

    @Test
    void build_withApiKey_createsConfig() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("my-api-key")
                .build();

        assertEquals("my-api-key", config.getApiKey());
    }

    @Test
    void build_withoutApiKey_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                FlipswitchConfig.builder().build());

        assertEquals("apiKey is required", exception.getMessage());
    }

    @Test
    void build_withEmptyApiKey_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                FlipswitchConfig.builder()
                        .apiKey("")
                        .build());

        assertEquals("apiKey is required", exception.getMessage());
    }

    @Test
    void build_usesDefaultBaseUrl() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-key")
                .build();

        assertEquals("http://localhost:8080", config.getBaseUrl());
    }

    @Test
    void build_withCustomBaseUrl_overridesDefault() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl("https://api.flipswitch.dev")
                .apiKey("test-key")
                .build();

        assertEquals("https://api.flipswitch.dev", config.getBaseUrl());
    }

    @Test
    void build_usesDefaultConnectTimeout() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-key")
                .build();

        assertEquals(5000, config.getConnectTimeoutMs());
    }

    @Test
    void build_withCustomConnectTimeout_overridesDefault() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-key")
                .connectTimeoutMs(1000)
                .build();

        assertEquals(1000, config.getConnectTimeoutMs());
    }

    @Test
    void build_usesDefaultReadTimeout() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-key")
                .build();

        assertEquals(10000, config.getReadTimeoutMs());
    }

    @Test
    void build_withCustomReadTimeout_overridesDefault() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .apiKey("test-key")
                .readTimeoutMs(30000)
                .build();

        assertEquals(30000, config.getReadTimeoutMs());
    }

    @Test
    void build_withAllCustomValues_createsFullyConfiguredConfig() {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl("https://custom.example.com")
                .apiKey("custom-key")
                .connectTimeoutMs(2000)
                .readTimeoutMs(15000)
                .build();

        assertEquals("https://custom.example.com", config.getBaseUrl());
        assertEquals("custom-key", config.getApiKey());
        assertEquals(2000, config.getConnectTimeoutMs());
        assertEquals(15000, config.getReadTimeoutMs());
    }
}
