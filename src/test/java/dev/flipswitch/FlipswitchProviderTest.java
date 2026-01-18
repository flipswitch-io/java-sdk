package dev.flipswitch;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FlipswitchProviderTest {

    private MockWebServer mockServer;
    private FlipswitchProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.shutdown();
        }
        mockServer.shutdown();
    }

    private FlipswitchProvider createProvider() {
        return FlipswitchProvider.builder("test-api-key")
                .baseUrl(mockServer.url("/").toString())
                .enableRealtime(false)
                .build();
    }

    @Test
    void initialization_shouldSucceed() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"flags\":[]}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        assertEquals(ProviderState.READY, provider.getState());
    }

    @Test
    void initialization_shouldFailOnInvalidApiKey() {
        mockServer.enqueue(new MockResponse().setResponseCode(401));

        provider = createProvider();

        Exception exception = assertThrows(Exception.class, () -> provider.initialize(new ImmutableContext()));
        assertTrue(exception.getMessage().contains("Invalid API key"));
        assertEquals(ProviderState.ERROR, provider.getState());
    }

    @Test
    void initialization_shouldFailOnForbidden() {
        mockServer.enqueue(new MockResponse().setResponseCode(403));

        provider = createProvider();

        Exception exception = assertThrows(Exception.class, () -> provider.initialize(new ImmutableContext()));
        assertTrue(exception.getMessage().contains("Invalid API key"));
        assertEquals(ProviderState.ERROR, provider.getState());
    }

    @Test
    void initialization_shouldFailOnServerError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        provider = createProvider();

        assertThrows(Exception.class, () -> provider.initialize(new ImmutableContext()));
        assertEquals(ProviderState.ERROR, provider.getState());
    }

    @Test
    void getBooleanEvaluation_shouldResolveFlag() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":true,\"variant\":\"on\",\"reason\":\"TARGETING_MATCH\"}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "dark-mode", false, new ImmutableContext("user-1"));

        assertTrue(result.getValue());
        assertEquals("on", result.getVariant());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getStringEvaluation_shouldResolveFlag() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":\"Welcome!\",\"variant\":\"greeting\"}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        ProviderEvaluation<String> result = provider.getStringEvaluation(
                "welcome-message", "Hello", new ImmutableContext());

        assertEquals("Welcome!", result.getValue());
    }

    @Test
    void getIntegerEvaluation_shouldResolveFlag() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":42,\"variant\":\"large\"}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        ProviderEvaluation<Integer> result = provider.getIntegerEvaluation(
                "max-items", 10, new ImmutableContext());

        assertEquals(42, result.getValue());
    }

    @Test
    void getDoubleEvaluation_shouldResolveFlag() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":3.14,\"variant\":\"pi\"}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        ProviderEvaluation<Double> result = provider.getDoubleEvaluation(
                "discount-rate", 0.0, new ImmutableContext());

        assertEquals(3.14, result.getValue(), 0.001);
    }

    @Test
    void evaluation_shouldReturnDefaultOn404() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse().setResponseCode(404));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "nonexistent", true, new ImmutableContext());

        assertTrue(result.getValue());
        assertNotNull(result.getErrorCode());
    }

    @Test
    void evaluation_shouldSendApiKeyHeader() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":true}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());
        provider.getBooleanEvaluation("test", false, new ImmutableContext());

        mockServer.takeRequest(); // init request
        RecordedRequest evalRequest = mockServer.takeRequest();

        assertEquals("test-api-key", evalRequest.getHeader("X-API-Key"));
    }

    @Test
    void evaluation_shouldIncludeContext() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":true}"));

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        Map<String, dev.openfeature.sdk.Value> attrs = new HashMap<>();
        attrs.put("email", new dev.openfeature.sdk.Value("test@example.com"));
        attrs.put("plan", new dev.openfeature.sdk.Value("premium"));
        EvaluationContext context = new ImmutableContext("user-123", attrs);

        provider.getBooleanEvaluation("test", false, context);

        mockServer.takeRequest(); // init request
        RecordedRequest evalRequest = mockServer.takeRequest();
        String body = evalRequest.getBody().readUtf8();

        assertTrue(body.contains("\"targetingKey\":\"user-123\""));
        assertTrue(body.contains("\"email\":\"test@example.com\""));
        assertTrue(body.contains("\"plan\":\"premium\""));
    }

    @Test
    void metadata_shouldReturnFlipswitch() {
        provider = createProvider();
        assertEquals("flipswitch", provider.getMetadata().getName());
    }
}

class FlagCacheTest {

    @Test
    void shouldStoreAndRetrieveValues() {
        FlagCache cache = new FlagCache();
        cache.set("key", "value");
        assertEquals("value", cache.get("key"));
    }

    @Test
    void shouldReturnNullForMissingKeys() {
        FlagCache cache = new FlagCache();
        assertNull(cache.get("missing"));
    }

    @Test
    void shouldInvalidateSpecificKey() {
        FlagCache cache = new FlagCache();
        cache.set("key1", "value1");
        cache.set("key2", "value2");

        cache.invalidate("key1");

        assertNull(cache.get("key1"));
        assertEquals("value2", cache.get("key2"));
    }

    @Test
    void shouldInvalidateAllKeys() {
        FlagCache cache = new FlagCache();
        cache.set("key1", "value1");
        cache.set("key2", "value2");

        cache.invalidateAll();

        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));
    }

    @Test
    void shouldHandleFlagChangeEventWithSpecificKey() {
        FlagCache cache = new FlagCache();
        cache.set("flag-1", true);
        cache.set("flag-2", false);

        FlagChangeEvent event = new FlagChangeEvent("flag-1", "2024-01-01T00:00:00Z");
        cache.handleFlagChange(event);

        assertNull(cache.get("flag-1"));
        assertEquals(false, cache.get("flag-2"));
    }

    @Test
    void shouldHandleFlagChangeEventWithNullKey() {
        FlagCache cache = new FlagCache();
        cache.set("flag-1", true);
        cache.set("flag-2", false);

        FlagChangeEvent event = new FlagChangeEvent(null, "2024-01-01T00:00:00Z");
        cache.handleFlagChange(event);

        assertNull(cache.get("flag-1"));
        assertNull(cache.get("flag-2"));
    }

    @Test
    void shouldExpireValuesAfterTtl() throws InterruptedException {
        FlagCache cache = new FlagCache(Duration.ofMillis(50));
        cache.set("key", "value");

        assertEquals("value", cache.get("key"));

        TimeUnit.MILLISECONDS.sleep(60);

        assertNull(cache.get("key"));
    }
}
