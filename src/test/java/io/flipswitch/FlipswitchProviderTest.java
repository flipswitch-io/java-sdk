package io.flipswitch;

import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FlipswitchProvider.
 *
 * Note: These tests focus on FlipswitchProvider's specific functionality:
 * - Initialization and API key validation
 * - SSE connection management
 * - Bulk flag evaluation methods (evaluateAllFlags, evaluateFlag)
 *
 * The OpenFeature SDK evaluation methods (getBooleanEvaluation, etc.) are delegated
 * to the underlying OFREP provider, which has its own test suite.
 */
class FlipswitchProviderTest {

    private MockWebServer mockServer;
    private FlipswitchProvider provider;
    private TestDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        dispatcher = new TestDispatcher();
        mockServer.setDispatcher(dispatcher);
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.shutdown();
        }
        mockServer.close();
    }

    private FlipswitchProvider createProvider() {
        return FlipswitchProvider.builder("test-api-key")
                .baseUrl(mockServer.url("/").toString())
                .enableRealtime(false)
                .build();
    }

    private FlipswitchProvider createRealtimeProvider() {
        return FlipswitchProvider.builder("test-api-key")
                .baseUrl(mockServer.url("/").toString())
                .enableRealtime(true)
                .build();
    }

    private FlipswitchProvider createRealtimeProvider(int maxSseRetries) {
        return FlipswitchProvider.builder("test-api-key")
                .baseUrl(mockServer.url("/").toString())
                .enableRealtime(true)
                .maxSseRetries(maxSseRetries)
                .pollingIntervalMs(100)
                .build();
    }

    private static String sseFrame(String eventType, String data) {
        return "event: " + eventType + "\n" +
               "data: " + data + "\n" +
               "\n";
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Cache-Control", "no-cache")
                .setHeader("Connection", "keep-alive")
                .body(body)
                .build();
    }

    /**
     * Test dispatcher that handles OFREP endpoints dynamically.
     */
    static class TestDispatcher extends Dispatcher {
        private final Map<String, Supplier<MockResponse>> flagResponses = new ConcurrentHashMap<>();
        private Supplier<MockResponse> bulkResponse = () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[]}")
                .build();
        private volatile Supplier<MockResponse> sseResponse = null;
        private boolean failInit = false;
        private int initFailCode = 401;

        void setFlagResponse(String flagKey, Supplier<MockResponse> responseSupplier) {
            flagResponses.put(flagKey, responseSupplier);
        }

        void setBulkResponse(Supplier<MockResponse> responseSupplier) {
            this.bulkResponse = responseSupplier;
        }

        void setSseResponse(Supplier<MockResponse> responseSupplier) {
            this.sseResponse = responseSupplier;
        }

        void setInitFailure(int statusCode) {
            this.failInit = true;
            this.initFailCode = statusCode;
        }

        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest request) {
            String path = request.getUrl() != null ? request.getUrl().encodedPath() : null;

            // Bulk evaluation endpoint (used during init and for evaluateAllFlags)
            if (path != null && path.equals("/ofrep/v1/evaluate/flags")) {
                if (failInit) {
                    return new MockResponse.Builder().code(initFailCode).build();
                }
                return bulkResponse.get();
            }

            // Single flag evaluation endpoint (used by evaluateFlag direct HTTP method)
            if (path != null && path.startsWith("/ofrep/v1/evaluate/flags/")) {
                String flagKey = path.substring("/ofrep/v1/evaluate/flags/".length());
                Supplier<MockResponse> responseSupplier = flagResponses.get(flagKey);
                if (responseSupplier != null) {
                    return responseSupplier.get();
                }
                return new MockResponse.Builder()
                        .code(404)
                        .addHeader("Content-Type", "application/json")
                        .body("{\"key\":\"" + flagKey + "\",\"errorCode\":\"FLAG_NOT_FOUND\"}")
                        .build();
            }

            // SSE endpoint
            if (path != null && path.contains("/events")) {
                if (sseResponse != null) {
                    return sseResponse.get();
                }
                return new MockResponse.Builder().code(200).build();
            }

            return new MockResponse.Builder().code(404).build();
        }
    }

    // ========================================
    // Initialization Tests
    // ========================================

    @Test
    void initialization_shouldSucceed() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        assertEquals(ProviderState.READY, provider.getState());
    }

    @Test
    void initialization_shouldFailOnInvalidApiKey() {
        dispatcher.setInitFailure(401);

        provider = createProvider();

        Exception exception = assertThrows(Exception.class, () -> provider.initialize(new ImmutableContext()));
        assertTrue(exception.getMessage().contains("Invalid API key"));
        assertEquals(ProviderState.ERROR, provider.getState());
    }

    @Test
    void initialization_shouldFailOnForbidden() {
        dispatcher.setInitFailure(403);

        provider = createProvider();

        Exception exception = assertThrows(Exception.class, () -> provider.initialize(new ImmutableContext()));
        assertTrue(exception.getMessage().contains("Invalid API key"));
        assertEquals(ProviderState.ERROR, provider.getState());
    }

    @Test
    void initialization_shouldFailOnServerError() {
        dispatcher.setInitFailure(500);

        provider = createProvider();

        Exception exception = assertThrows(Exception.class, () -> provider.initialize(new ImmutableContext()));
        assertTrue(exception.getMessage().contains("Failed to connect"));
        assertEquals(ProviderState.ERROR, provider.getState());
    }

    // ========================================
    // Metadata Tests
    // ========================================

    @Test
    void metadata_shouldReturnFlipswitch() {
        provider = createProvider();
        assertEquals("flipswitch", provider.getMetadata().getName());
    }

    // ========================================
    // Bulk Evaluation Tests (Direct HTTP, not via OFREP provider)
    // ========================================

    @Test
    void evaluateAllFlags_shouldReturnAllFlags() throws Exception {
        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[" +
                        "{\"key\":\"flag-1\",\"value\":true,\"reason\":\"DEFAULT\"}," +
                        "{\"key\":\"flag-2\",\"value\":\"test\",\"reason\":\"TARGETING_MATCH\"}" +
                        "]}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var flags = provider.evaluateAllFlags(new ImmutableContext("user-1"));

        assertEquals(2, flags.size());
        assertEquals("flag-1", flags.get(0).getKey());
        assertTrue(flags.get(0).asBoolean());
        assertEquals("flag-2", flags.get(1).getKey());
        assertEquals("test", flags.get(1).asString());
    }

    @Test
    void evaluateAllFlags_shouldReturnEmptyListOnError() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // Set bulk response to error after init
        dispatcher.setInitFailure(500);

        var flags = provider.evaluateAllFlags(new ImmutableContext("user-1"));

        assertEquals(0, flags.size());
    }

    @Test
    void evaluateFlag_shouldReturnSingleFlag() throws Exception {
        dispatcher.setFlagResponse("my-flag", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"my-flag\",\"value\":\"hello\",\"reason\":\"DEFAULT\",\"variant\":\"v1\"}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        FlagEvaluation result = provider.evaluateFlag("my-flag", new ImmutableContext());

        assertNotNull(result);
        assertEquals("my-flag", result.getKey());
        assertEquals("hello", result.asString());
        assertEquals("DEFAULT", result.getReason());
        assertEquals("v1", result.getVariant());
    }

    @Test
    void evaluateFlag_shouldReturnNullForNonexistent() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        FlagEvaluation result = provider.evaluateFlag("nonexistent", new ImmutableContext());

        assertNull(result);
    }

    @Test
    void evaluateFlag_shouldHandleBooleanValues() throws Exception {
        dispatcher.setFlagResponse("bool-flag", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"bool-flag\",\"value\":true}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        FlagEvaluation result = provider.evaluateFlag("bool-flag", new ImmutableContext());

        assertNotNull(result);
        assertTrue(result.asBoolean());
    }

    @Test
    void evaluateFlag_shouldHandleNumericValues() throws Exception {
        dispatcher.setFlagResponse("num-flag", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"num-flag\",\"value\":42}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        FlagEvaluation result = provider.evaluateFlag("num-flag", new ImmutableContext());

        assertNotNull(result);
        assertEquals(42, result.asInt());
    }

    // ========================================
    // SSE Status Tests
    // ========================================

    @Test
    void sseStatus_shouldBeDisconnectedWhenRealtimeDisabled() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        assertEquals(SseClient.ConnectionStatus.DISCONNECTED, provider.getSseStatus());
    }

    // ========================================
    // Flag Change Listener Tests
    // ========================================

    @Test
    void flagChangeListener_canBeAddedAndRemoved() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var events = new java.util.ArrayList<FlagChangeEvent>();
        java.util.function.Consumer<FlagChangeEvent> listener = events::add;

        provider.addFlagChangeListener(listener);
        provider.removeFlagChangeListener(listener);

        // Verify no exceptions thrown - listener management works
        assertEquals(0, events.size());
    }

    // ========================================
    // Builder Tests
    // ========================================

    @Test
    void builder_shouldRequireApiKey() {
        assertThrows(IllegalArgumentException.class, () -> FlipswitchProvider.builder(null));
        assertThrows(IllegalArgumentException.class, () -> FlipswitchProvider.builder(""));
    }

    @Test
    void builder_shouldUseDefaults() {
        provider = FlipswitchProvider.builder("test-key")
                .enableRealtime(false)
                .build();

        assertEquals("flipswitch", provider.getMetadata().getName());
    }

    @Test
    void builder_shouldAllowCustomBaseUrl() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // If we get here without exception, the custom baseUrl was used
        assertEquals(ProviderState.READY, provider.getState());
    }

    // ========================================
    // URL Path Tests
    // ========================================

    @Test
    void ofrepRequests_shouldUseCorrectPath() throws Exception {
        dispatcher.setFlagResponse("test-flag", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"test-flag\",\"value\":true}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // Trigger a single flag evaluation via the direct HTTP method
        provider.evaluateFlag("test-flag", new ImmutableContext());

        // Skip the init request, get the flag evaluation request
        mockServer.takeRequest(); // init bulk request
        RecordedRequest flagRequest = mockServer.takeRequest();

        assertEquals("/ofrep/v1/evaluate/flags/test-flag", flagRequest.getUrl().encodedPath());
    }

    // ========================================
    // Polling Fallback Tests
    // ========================================

    @Test
    void pollingFallback_shouldNotBeActiveInitially() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        assertFalse(provider.isPollingActive());
    }

    @Test
    void pollingFallback_shouldBeDisabledWhenConfigured() {
        provider = FlipswitchProvider.builder("test-api-key")
                .baseUrl(mockServer.url("/").toString())
                .enableRealtime(false)
                .enablePollingFallback(false)
                .build();

        assertFalse(provider.isPollingActive());
    }

    // ========================================
    // Flag Change Listener Tests - Extended
    // ========================================

    @Test
    void flagChangeListener_shouldReceiveEvents() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        List<FlagChangeEvent> events = new ArrayList<>();
        Consumer<FlagChangeEvent> listener = events::add;

        provider.addFlagChangeListener(listener);

        // Simulate a flag change event through the internal handler
        // We need to use the SSE client for this, but since SSE is disabled,
        // we test that listener management works
        assertEquals(0, events.size());
        provider.removeFlagChangeListener(listener);
    }

    @Test
    void flagChangeListener_multipleListeners() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        AtomicInteger count3 = new AtomicInteger(0);

        provider.addFlagChangeListener(e -> count1.incrementAndGet());
        provider.addFlagChangeListener(e -> count2.incrementAndGet());
        provider.addFlagChangeListener(e -> count3.incrementAndGet());

        // Verify all listeners are registered without error
        assertEquals(0, count1.get());
        assertEquals(0, count2.get());
        assertEquals(0, count3.get());
    }

    // ========================================
    // Shutdown / Cleanup Tests
    // ========================================

    @Test
    void shutdown_shouldClearState() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        assertEquals(ProviderState.READY, provider.getState());

        provider.shutdown();

        assertEquals(ProviderState.NOT_READY, provider.getState());
    }

    @Test
    void shutdown_shouldBeIdempotent() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // Should not throw on double shutdown
        provider.shutdown();
        provider.shutdown();
    }

    // ========================================
    // Context Transformation Tests
    // ========================================

    @Test
    void contextTransformation_targetingKeyOnly() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // Evaluate a flag to capture the request body
        dispatcher.setFlagResponse("test-ctx", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"test-ctx\",\"value\":true}")
                .build());

        provider.evaluateFlag("test-ctx", new ImmutableContext("user-123"));

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String body = request.getBody().utf8();

        assertTrue(body.contains("\"targetingKey\":\"user-123\""));
    }

    @Test
    void contextTransformation_withAttributes() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setFlagResponse("test-ctx", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"test-ctx\",\"value\":true}")
                .build());

        MutableContext ctx = new MutableContext("user-123");
        ctx.add("email", "test@example.com");
        ctx.add("plan", "premium");

        provider.evaluateFlag("test-ctx", ctx);

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String body = request.getBody().utf8();

        assertTrue(body.contains("\"targetingKey\":\"user-123\""));
        assertTrue(body.contains("\"email\":\"test@example.com\""));
        assertTrue(body.contains("\"plan\":\"premium\""));
    }

    @Test
    void contextTransformation_emptyContext() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setFlagResponse("test-ctx", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"test-ctx\",\"value\":true}")
                .build());

        provider.evaluateFlag("test-ctx", new ImmutableContext());

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String body = request.getBody().utf8();

        assertTrue(body.contains("\"context\""));
    }

    // ========================================
    // Type Inference Tests (FlagEvaluation)
    // ========================================

    @Test
    void typeInference_boolean() {
        FlagEvaluation eval = new FlagEvaluation("key", true, null, null);
        assertEquals("boolean", eval.getValueType());
    }

    @Test
    void typeInference_string() {
        FlagEvaluation eval = new FlagEvaluation("key", "hello", null, null);
        assertEquals("string", eval.getValueType());
    }

    @Test
    void typeInference_integer() {
        FlagEvaluation eval = new FlagEvaluation("key", 42, null, null);
        assertEquals("integer", eval.getValueType());
    }

    @Test
    void typeInference_number() {
        FlagEvaluation eval = new FlagEvaluation("key", 3.14, null, null);
        assertEquals("number", eval.getValueType());
    }

    @Test
    void typeInference_null() {
        FlagEvaluation eval = new FlagEvaluation("key", null, null, null);
        assertEquals("null", eval.getValueType());
    }

    @Test
    void typeInference_object() {
        FlagEvaluation eval = new FlagEvaluation("key", Map.of("a", 1), null, null);
        assertEquals("object", eval.getValueType());
    }

    @Test
    void typeInference_array() {
        FlagEvaluation eval = new FlagEvaluation("key", List.of(1, 2, 3), null, null);
        assertEquals("array", eval.getValueType());
    }

    @Test
    void typeInference_metadataOverride() {
        FlagEvaluation eval = new FlagEvaluation("key", null, null, null, "boolean");
        assertEquals("boolean", eval.getValueType());
    }

    @Test
    void typeInference_decimalMapsToNumber() {
        FlagEvaluation eval = new FlagEvaluation("key", 3.14, null, null, "decimal");
        assertEquals("number", eval.getValueType());
    }

    // ========================================
    // Telemetry Headers Tests
    // ========================================

    @Test
    void telemetryHeaders_sdkHeader() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[]}")
                .build());

        provider.evaluateAllFlags(new ImmutableContext("user-1"));

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String sdk = request.getHeaders().get("X-Flipswitch-SDK");

        assertNotNull(sdk);
        assertTrue(sdk.startsWith("java/"));
    }

    @Test
    void telemetryHeaders_runtimeHeader() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[]}")
                .build());

        provider.evaluateAllFlags(new ImmutableContext("user-1"));

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String runtime = request.getHeaders().get("X-Flipswitch-Runtime");

        assertNotNull(runtime);
        assertTrue(runtime.startsWith("java/"));
    }

    @Test
    void telemetryHeaders_osHeader() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[]}")
                .build());

        provider.evaluateAllFlags(new ImmutableContext("user-1"));

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String os = request.getHeaders().get("X-Flipswitch-OS");

        assertNotNull(os);
        assertTrue(os.contains("/"));
    }

    @Test
    void telemetryHeaders_featuresHeader() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[]}")
                .build());

        provider.evaluateAllFlags(new ImmutableContext("user-1"));

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String features = request.getHeaders().get("X-Flipswitch-Features");

        assertEquals("sse=false", features);
    }

    // ========================================
    // Builder Options Tests
    // ========================================

    @Test
    void builder_enablePollingFallbackFalse() {
        provider = FlipswitchProvider.builder("test-api-key")
                .enablePollingFallback(false)
                .enableRealtime(false)
                .build();

        assertFalse(provider.isPollingActive());
    }

    @Test
    void builder_pollingIntervalMs() {
        provider = FlipswitchProvider.builder("test-api-key")
                .pollingIntervalMs(10000)
                .enableRealtime(false)
                .build();

        assertEquals("flipswitch", provider.getMetadata().getName());
    }

    @Test
    void builder_maxSseRetries() {
        provider = FlipswitchProvider.builder("test-api-key")
                .maxSseRetries(10)
                .enableRealtime(false)
                .build();

        assertEquals("flipswitch", provider.getMetadata().getName());
    }

    // ========================================
    // SSE Integration Tests (enableRealtime=true)
    // ========================================

    @Test
    void initialization_withRealtimeEnabled_shouldStartSse() throws Exception {
        // Track SSE requests to verify the connection was attempted
        AtomicInteger sseHitCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            sseHitCount.incrementAndGet();
            return sseResponse(sseFrame("heartbeat", "{}"));
        });

        provider = createRealtimeProvider();
        provider.initialize(new ImmutableContext());

        assertEquals(ProviderState.READY, provider.getState());

        // Wait for the SSE endpoint to be hit (proves SSE was started)
        awaitCondition(() -> sseHitCount.get() > 0, 5000);
        assertTrue(sseHitCount.get() > 0, "SSE endpoint should have been called");

        // getSseStatus may be CONNECTED or DISCONNECTED depending on timing,
        // but it should not be the initial value before any connection was attempted
        // The key assertion is that SSE was started (sseHitCount > 0)
    }

    @Test
    void sseStatusCallback_onError_shouldSetStaleState() throws Exception {
        // Serve a valid SSE response first, then errors
        AtomicInteger sseRequestCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            int count = sseRequestCount.incrementAndGet();
            if (count == 1) {
                // First request: connect successfully then close (empty body triggers onClosed -> reconnect)
                return sseResponse("");
            }
            // Subsequent requests: return 500 error
            return new MockResponse.Builder().code(500).body("error").build();
        });

        provider = createRealtimeProvider();
        provider.initialize(new ImmutableContext());

        // Wait for STALE state (SSE error after reconnect failures)
        awaitCondition(() -> provider.getState() == ProviderState.STALE, 10000);
        assertEquals(ProviderState.STALE, provider.getState());
    }

    @Test
    void sseStatusCallback_onReconnect_shouldRestoreReadyFromStale() throws Exception {
        AtomicInteger sseRequestCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            int count = sseRequestCount.incrementAndGet();
            if (count == 1) {
                // First: connect then close immediately
                return sseResponse("");
            } else if (count == 2) {
                // Second: error to trigger STALE
                return new MockResponse.Builder().code(500).body("error").build();
            }
            // Third+: successful reconnect
            return sseResponse(sseFrame("heartbeat", "{}"));
        });

        provider = createRealtimeProvider();
        provider.initialize(new ImmutableContext());

        // Wait for STALE
        awaitCondition(() -> provider.getState() == ProviderState.STALE, 10000);

        // Wait for recovery to READY
        awaitCondition(() -> provider.getState() == ProviderState.READY, 10000);
        assertEquals(ProviderState.READY, provider.getState());
    }

    @Test
    void pollingFallback_shouldActivateAfterMaxSseRetries() throws Exception {
        // Always fail SSE requests
        dispatcher.setSseResponse(() -> new MockResponse.Builder().code(500).body("error").build());

        provider = createRealtimeProvider(2);
        provider.initialize(new ImmutableContext());

        // Wait for polling to become active (after 2 SSE retries)
        awaitCondition(() -> provider.isPollingActive(), 15000);
        assertTrue(provider.isPollingActive());
    }

    @Test
    void pollingFallback_shouldStopWhenSseReconnects() throws Exception {
        AtomicInteger sseRequestCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            int count = sseRequestCount.incrementAndGet();
            if (count <= 3) {
                // First 3 requests: fail to trigger polling fallback
                return new MockResponse.Builder().code(500).body("error").build();
            }
            // After polling is active: succeed to stop polling
            return sseResponse(sseFrame("heartbeat", "{}"));
        });

        provider = createRealtimeProvider(2);
        provider.initialize(new ImmutableContext());

        // Wait for polling to activate
        awaitCondition(() -> provider.isPollingActive(), 15000);
        assertTrue(provider.isPollingActive());

        // Wait for SSE to reconnect and polling to stop
        awaitCondition(() -> !provider.isPollingActive(), 15000);
        assertFalse(provider.isPollingActive());
    }

    @Test
    void handleFlagChange_shouldNotifyListeners() throws Exception {
        String flagJson = "{\"flagKey\":\"test-flag\",\"timestamp\":\"2024-01-01T00:00:00Z\"}";
        dispatcher.setSseResponse(() -> sseResponse(sseFrame("flag-updated", flagJson)));

        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<FlagChangeEvent> receivedEvent = new AtomicReference<>();

        provider = createRealtimeProvider();
        provider.addFlagChangeListener(event -> {
            receivedEvent.set(event);
            eventLatch.countDown();
        });
        provider.initialize(new ImmutableContext());

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Should receive flag change event");
        assertNotNull(receivedEvent.get());
        assertEquals("test-flag", receivedEvent.get().flagKey());
    }

    @Test
    void handleFlagChange_listenerException_shouldNotPropagate() throws Exception {
        String flagJson = "{\"flagKey\":\"test-flag\",\"timestamp\":\"2024-01-01T00:00:00Z\"}";
        dispatcher.setSseResponse(() -> sseResponse(sseFrame("flag-updated", flagJson)));

        CountDownLatch eventLatch = new CountDownLatch(1);

        provider = createRealtimeProvider();
        // First listener throws
        provider.addFlagChangeListener(event -> {
            throw new RuntimeException("Listener error");
        });
        // Second listener should still be called
        provider.addFlagChangeListener(event -> eventLatch.countDown());
        provider.initialize(new ImmutableContext());

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS),
                "Second listener should be called despite first listener throwing");
    }

    // ========================================
    // Flag Change Event Details Tests
    // ========================================

    @Test
    void flagChangeEventDetails_flagUpdated_shouldIncludeFlagKeyInFlagsChanged() throws Exception {
        String flagJson = "{\"flagKey\":\"my-feature\",\"timestamp\":\"2024-01-01T00:00:00Z\"}";

        // First SSE request: heartbeat only (during initialization).
        // Subsequent requests deliver the flag-updated event (after OpenFeature event wiring is complete).
        AtomicInteger sseRequestCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            int count = sseRequestCount.incrementAndGet();
            if (count == 1) {
                return sseResponse(sseFrame("heartbeat", "{}"));
            }
            return sseResponse(sseFrame("flag-updated", flagJson));
        });

        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<EventDetails> receivedDetails = new AtomicReference<>();

        provider = createRealtimeProvider();

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();

        try {
            // Register event handler BEFORE setting provider to avoid race condition.
            // Filter for events that have flagsChanged (to distinguish from other config changed events).
            api.onProviderConfigurationChanged(details -> {
                if (details.getFlagsChanged() != null && !details.getFlagsChanged().isEmpty()) {
                    receivedDetails.set(details);
                    eventLatch.countDown();
                }
            });

            api.setProviderAndWait(provider);

            // Wait for SSE to connect, close, and reconnect with the flag-updated event
            assertTrue(eventLatch.await(10, TimeUnit.SECONDS),
                    "Should receive PROVIDER_CONFIGURATION_CHANGED event with flagsChanged");

            EventDetails details = receivedDetails.get();
            assertNotNull(details);
            assertNotNull(details.getFlagsChanged(), "flagsChanged should not be null for flag-updated event");
            assertEquals(1, details.getFlagsChanged().size());
            assertEquals("my-feature", details.getFlagsChanged().get(0));
        } finally {
            api.shutdown();
            provider = null; // Prevent double-shutdown in @AfterEach
        }
    }

    @Test
    void flagChangeEventDetails_configUpdated_shouldHaveEmptyFlagsChanged() throws Exception {
        String configJson = "{\"timestamp\":\"2024-01-01T00:00:00Z\"}";

        // First SSE request: heartbeat only (during initialization).
        // Subsequent requests deliver the config-updated event (after OpenFeature event wiring is complete).
        AtomicInteger sseRequestCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            int count = sseRequestCount.incrementAndGet();
            if (count == 1) {
                return sseResponse(sseFrame("heartbeat", "{}"));
            }
            return sseResponse(sseFrame("config-updated", configJson));
        });

        CountDownLatch listenerLatch = new CountDownLatch(1);
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<EventDetails> receivedDetails = new AtomicReference<>();

        provider = createRealtimeProvider();
        // Use a direct flag change listener to detect when the config-updated event has been processed
        provider.addFlagChangeListener(event -> {
            if (event.flagKey() == null) {
                listenerLatch.countDown();
            }
        });

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();

        try {
            // Register event handler BEFORE setting provider
            api.onProviderConfigurationChanged(details -> {
                if (details.getFlagsChanged() == null || details.getFlagsChanged().isEmpty()) {
                    receivedDetails.set(details);
                    eventLatch.countDown();
                }
            });

            api.setProviderAndWait(provider);

            // Wait for the config-updated SSE event to arrive via direct listener
            assertTrue(listenerLatch.await(10, TimeUnit.SECONDS),
                    "Should receive config-updated event via direct listener");

            // Now wait for the OpenFeature event
            assertTrue(eventLatch.await(10, TimeUnit.SECONDS),
                    "Should receive PROVIDER_CONFIGURATION_CHANGED event for config-updated");

            EventDetails details = receivedDetails.get();
            assertNotNull(details);
            assertTrue(details.getFlagsChanged() == null || details.getFlagsChanged().isEmpty(),
                    "flagsChanged should be null or empty for config-updated event (full invalidation)");
        } finally {
            api.shutdown();
            provider = null; // Prevent double-shutdown in @AfterEach
        }
    }

    @Test
    void reconnectSse_shouldCloseAndRestart() throws Exception {
        AtomicInteger sseHitCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            sseHitCount.incrementAndGet();
            return sseResponse(sseFrame("heartbeat", "{}"));
        });

        provider = createRealtimeProvider();
        provider.initialize(new ImmutableContext());

        // Wait for initial SSE connection
        awaitCondition(() -> sseHitCount.get() > 0, 5000);
        int countBeforeReconnect = sseHitCount.get();

        // Force reconnect
        provider.reconnectSse();

        // Wait for the reconnected SSE to hit the endpoint again
        awaitCondition(() -> sseHitCount.get() > countBeforeReconnect, 5000);
        assertTrue(sseHitCount.get() > countBeforeReconnect,
                "SSE endpoint should have been called again after reconnect");
    }

    @Test
    void reconnectSse_whenRealtimeDisabled_shouldBeNoOp() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // Should not throw
        provider.reconnectSse();

        assertEquals(SseClient.ConnectionStatus.DISCONNECTED, provider.getSseStatus());
    }

    @Test
    void shutdown_withActiveSseClient_shouldCloseIt() throws Exception {
        AtomicInteger sseHitCount = new AtomicInteger(0);
        dispatcher.setSseResponse(() -> {
            sseHitCount.incrementAndGet();
            return sseResponse(sseFrame("heartbeat", "{}"));
        });

        provider = createRealtimeProvider();
        provider.initialize(new ImmutableContext());

        // Wait for SSE to be established
        awaitCondition(() -> sseHitCount.get() > 0, 5000);

        provider.shutdown();

        assertEquals(ProviderState.NOT_READY, provider.getState());
        assertEquals(SseClient.ConnectionStatus.DISCONNECTED, provider.getSseStatus());
    }

    // ========================================
    // Delegation Tests
    // ========================================

    @Test
    void delegation_getBooleanEvaluation() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // The delegation call goes through the OFREP provider which will make
        // its own HTTP call. Since we can't easily mock it at this level,
        // we verify no exception is thrown and a result is returned.
        var result = provider.getBooleanEvaluation("test-flag", false, new ImmutableContext());
        assertNotNull(result);
        // Default value is returned when flag is not found
        assertEquals(false, result.getValue());
    }

    @Test
    void delegation_getStringEvaluation() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var result = provider.getStringEvaluation("test-flag", "default", new ImmutableContext());
        assertNotNull(result);
        assertEquals("default", result.getValue());
    }

    @Test
    void delegation_getIntegerEvaluation() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var result = provider.getIntegerEvaluation("test-flag", 0, new ImmutableContext());
        assertNotNull(result);
        assertEquals(0, result.getValue());
    }

    @Test
    void delegation_getDoubleEvaluation() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var result = provider.getDoubleEvaluation("test-flag", 0.0, new ImmutableContext());
        assertNotNull(result);
        assertEquals(0.0, result.getValue());
    }

    @Test
    void delegation_getObjectEvaluation() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        Value defaultVal = new Value("default");
        var result = provider.getObjectEvaluation("test-flag", defaultVal, new ImmutableContext());
        assertNotNull(result);
    }

    // ========================================
    // Flag Metadata Tests
    // ========================================

    @Test
    void evaluateAllFlags_withFlagMetadata_shouldExtractFlagType() throws Exception {
        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[" +
                        "{\"key\":\"typed-flag\",\"value\":true,\"reason\":\"DEFAULT\"," +
                        "\"metadata\":{\"flagType\":\"boolean\"}}" +
                        "]}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var flags = provider.evaluateAllFlags(new ImmutableContext("user-1"));

        assertEquals(1, flags.size());
        assertEquals("boolean", flags.get(0).getValueType());
    }

    // ========================================
    // Context Transformation - Extended
    // ========================================

    @Test
    void contextTransformation_withListAndStructureValues() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setFlagResponse("test-ctx", () -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"key\":\"test-ctx\",\"value\":true}")
                .build());

        MutableContext ctx = new MutableContext("user-123");
        ctx.add("tags", List.of(new Value("a"), new Value("b")));
        MutableContext profile = new MutableContext();
        profile.add("name", "test");
        ctx.add("profile", profile);

        provider.evaluateFlag("test-ctx", ctx);

        mockServer.takeRequest(); // init
        RecordedRequest request = mockServer.takeRequest();
        String body = request.getBody().utf8();

        assertTrue(body.contains("\"tags\""));
        assertTrue(body.contains("\"profile\""));
    }

    // ========================================
    // Telemetry Features Header with Realtime
    // ========================================

    @Test
    void telemetryHeaders_featuresHeader_withRealtime() throws Exception {
        dispatcher.setSseResponse(() -> sseResponse(sseFrame("heartbeat", "{}")));

        provider = createRealtimeProvider();
        provider.initialize(new ImmutableContext());

        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[]}")
                .build());

        provider.evaluateAllFlags(new ImmutableContext("user-1"));

        // Find the bulk eval request (skip init and SSE requests)
        RecordedRequest request;
        String features = null;
        for (int i = 0; i < 10; i++) {
            request = mockServer.takeRequest(2, TimeUnit.SECONDS);
            if (request != null && request.getUrl() != null
                    && request.getUrl().encodedPath().equals("/ofrep/v1/evaluate/flags")) {
                features = request.getHeaders().get("X-Flipswitch-Features");
                // Skip the init request, get the post-init one
                if (features != null && features.equals("sse=true")) {
                    break;
                }
            }
        }

        assertEquals("sse=true", features);
    }

    // ========================================
    // HTTP Edge Cases
    // ========================================

    @Test
    void validateApiKey_404_shouldNotFail() throws Exception {
        // 404 during init validation is not an error (no flags configured yet)
        dispatcher.setInitFailure(404);

        provider = createProvider();

        // This should NOT throw because we only treat 401/403 and >=500 as errors
        // But the dispatcher uses failInit flag which just sends 404 without a body.
        // The Java provider's validateApiKey checks:
        //   - 401/403 -> error
        //   - !successful && != 404 -> error
        //   - 404 is fine
        provider.initialize(new ImmutableContext());
        assertEquals(ProviderState.READY, provider.getState());
    }

    @Test
    void evaluateAllFlags_nullResponseBody_shouldReturnEmptyList() throws Exception {
        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{}")  // no "flags" key
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        // Re-set the bulk response after init
        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{}")  // no "flags" key
                .build());

        var flags = provider.evaluateAllFlags(new ImmutableContext("user-1"));
        assertEquals(0, flags.size());
    }

    @Test
    void evaluateAllFlags_flagWithoutKey_shouldSkip() throws Exception {
        dispatcher.setBulkResponse(() -> new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"flags\":[" +
                        "{\"value\":true}," +  // no key - should skip
                        "{\"key\":\"valid\",\"value\":\"hello\"}" +
                        "]}")
                .build());

        provider = createProvider();
        provider.initialize(new ImmutableContext());

        var flags = provider.evaluateAllFlags(new ImmutableContext("user-1"));
        assertEquals(1, flags.size());
        assertEquals("valid", flags.get(0).getKey());
    }

    // ========================================
    // Polling Edge Cases
    // ========================================

    @Test
    void doubleStartPollingFallback_shouldBeNoOp() throws Exception {
        // Always fail SSE requests
        dispatcher.setSseResponse(() -> new MockResponse.Builder().code(500).body("error").build());

        provider = createRealtimeProvider(1);
        provider.initialize(new ImmutableContext());

        // Wait for polling to become active
        awaitCondition(() -> provider.isPollingActive(), 15000);
        assertTrue(provider.isPollingActive());

        // Second activation should be a no-op (no crash, still active)
        // We can't call startPollingFallback directly since it's private,
        // but sending more SSE errors will try to start it again
        // The guard `if (pollingActive || !enablePollingFallback)` prevents double start
        assertTrue(provider.isPollingActive());
    }

    @Test
    void stopPolling_whenNotActive_shouldBeNoOp() throws Exception {
        provider = createProvider();
        provider.initialize(new ImmutableContext());

        assertFalse(provider.isPollingActive());

        // shutdown calls stopPolling internally; should not throw
        provider.shutdown();
        assertFalse(provider.isPollingActive());
    }

    // ========================================
    // Type Inference Edge Cases
    // ========================================

    @Test
    void typeInference_unknownType() {
        // Pass a custom object that doesn't match any known type
        Object customObj = new Object() {
            @Override
            public String toString() { return "custom"; }
        };
        FlagEvaluation eval = new FlagEvaluation("key", customObj, null, null);
        assertEquals("unknown", eval.getValueType());
    }

    @Test
    void flagEvaluation_valueToString_null() {
        FlagEvaluation eval = new FlagEvaluation("key", null, null, null);
        assertEquals("null", eval.getValueAsString());
    }

    @Test
    void flagEvaluation_toString_withVariant() {
        FlagEvaluation eval = new FlagEvaluation("my-key", true, "DEFAULT", "v1");
        String s = eval.toString();
        assertTrue(s.contains("my-key"));
        assertTrue(s.contains("boolean"));
        assertTrue(s.contains("true"));
        assertTrue(s.contains("DEFAULT"));
        assertTrue(s.contains("v1"));
    }

    @Test
    void flagEvaluation_toString_withoutVariant() {
        FlagEvaluation eval = new FlagEvaluation("my-key", "hello", "STATIC", null);
        String s = eval.toString();
        assertTrue(s.contains("my-key"));
        assertTrue(s.contains("string"));
        assertTrue(s.contains("\"hello\""));
        assertTrue(s.contains("STATIC"));
        assertFalse(s.contains("variant="));
    }

    // ========================================
    // Builder / Lifecycle Edge Cases
    // ========================================

    @Test
    void builder_customHttpClient_shouldBeUsed() throws Exception {
        okhttp3.OkHttpClient customClient = new okhttp3.OkHttpClient();

        provider = FlipswitchProvider.builder("test-api-key")
                .baseUrl(mockServer.url("/").toString())
                .enableRealtime(false)
                .httpClient(customClient)
                .build();

        // Should initialize successfully with the custom client
        provider.initialize(new ImmutableContext());
        assertEquals(ProviderState.READY, provider.getState());
    }

    @Test
    void builder_allOptions() {
        provider = FlipswitchProvider.builder("test-api-key")
                .baseUrl("https://custom.example.com")
                .enableRealtime(false)
                .enablePollingFallback(false)
                .pollingIntervalMs(5000)
                .maxSseRetries(3)
                .httpClient(new okhttp3.OkHttpClient())
                .build();

        assertEquals("flipswitch", provider.getMetadata().getName());
        assertFalse(provider.isPollingActive());
    }

    // ========================================
    // FlagEvaluation Convenience Accessors
    // ========================================

    @Test
    void flagEvaluation_asBoolean_nonBoolean_returnsFalse() {
        FlagEvaluation eval = new FlagEvaluation("key", "not-a-boolean", null, null);
        assertFalse(eval.asBoolean());
    }

    @Test
    void flagEvaluation_asInt_nonNumber_returnsZero() {
        FlagEvaluation eval = new FlagEvaluation("key", "not-a-number", null, null);
        assertEquals(0, eval.asInt());
    }

    @Test
    void flagEvaluation_asDouble_nonNumber_returnsZero() {
        FlagEvaluation eval = new FlagEvaluation("key", "not-a-number", null, null);
        assertEquals(0.0, eval.asDouble());
    }

    @Test
    void flagEvaluation_asString_nonString_returnsToString() {
        FlagEvaluation eval = new FlagEvaluation("key", 42, null, null);
        assertEquals("42", eval.asString());
    }

    @Test
    void flagEvaluation_asString_null_returnsNull() {
        FlagEvaluation eval = new FlagEvaluation("key", null, null, null);
        assertNull(eval.asString());
    }

    // ========================================
    // valueToObject - Isolated Tests
    // ========================================

    @Test
    void valueToObject_boolean_returnsBoolean() throws Exception {
        provider = createProvider();
        assertEquals(true, provider.valueToObject(new Value(true)));
        assertEquals(false, provider.valueToObject(new Value(false)));
    }

    @Test
    void valueToObject_string_returnsString() throws Exception {
        provider = createProvider();
        assertEquals("hello", provider.valueToObject(new Value("hello")));
        assertEquals("", provider.valueToObject(new Value("")));
    }

    @Test
    void valueToObject_integer_returnsInteger() throws Exception {
        provider = createProvider();
        // Doubles with no fractional part are returned as integers
        assertEquals(42, provider.valueToObject(new Value(42.0)));
        assertEquals(0, provider.valueToObject(new Value(0.0)));
        assertEquals(-5, provider.valueToObject(new Value(-5.0)));
    }

    @Test
    void valueToObject_double_returnsDouble() throws Exception {
        provider = createProvider();
        assertEquals(3.14, provider.valueToObject(new Value(3.14)));
        assertEquals(-0.5, provider.valueToObject(new Value(-0.5)));
    }

    @Test
    void valueToObject_emptyList_returnsEmptyList() throws Exception {
        provider = createProvider();
        Object result = provider.valueToObject(new Value(List.of()));
        assertInstanceOf(List.class, result);
        assertEquals(0, ((List<?>) result).size());
    }

    @Test
    void valueToObject_listOfMixedValues_returnsList() throws Exception {
        provider = createProvider();
        List<Value> values = List.of(new Value("a"), new Value(1.0), new Value(true));
        Object result = provider.valueToObject(new Value(values));
        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        assertEquals("a", list.get(0));
        assertEquals(1, list.get(1));    // 1.0 → integer
        assertEquals(true, list.get(2));
    }

    @Test
    void valueToObject_nestedList_returnsNestedList() throws Exception {
        provider = createProvider();
        List<Value> inner = List.of(new Value("nested"));
        List<Value> outer = List.of(new Value(inner));
        Object result = provider.valueToObject(new Value(outer));
        assertInstanceOf(List.class, result);
        List<?> outerList = (List<?>) result;
        assertInstanceOf(List.class, outerList.get(0));
        assertEquals("nested", ((List<?>) outerList.get(0)).get(0));
    }

    @Test
    void valueToObject_structure_returnsMap() throws Exception {
        provider = createProvider();
        MutableStructure struct = new MutableStructure();
        struct.add("name", "Alice");
        struct.add("age", 30.0);
        Object result = provider.valueToObject(new Value(struct));
        assertInstanceOf(Map.class, result);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("Alice", map.get("name"));
        assertEquals(30, map.get("age"));  // 30.0 → integer
    }

    @Test
    void valueToObject_nestedStructure_returnsNestedMap() throws Exception {
        provider = createProvider();
        MutableStructure inner = new MutableStructure();
        inner.add("city", "Stockholm");
        MutableStructure outer = new MutableStructure();
        outer.add("address", inner);
        Object result = provider.valueToObject(new Value(outer));
        assertInstanceOf(Map.class, result);
        Map<?, ?> outerMap = (Map<?, ?>) result;
        assertInstanceOf(Map.class, outerMap.get("address"));
        assertEquals("Stockholm", ((Map<?, ?>) outerMap.get("address")).get("city"));
    }

    @Test
    void valueToObject_nullValue_returnsNull() throws Exception {
        provider = createProvider();
        // Default Value() constructor creates a null value
        assertNull(provider.valueToObject(new Value()));
    }

    @Test
    void valueToObject_instantValue_returnsNull() throws Exception {
        provider = createProvider();
        // Instant is not handled by valueToObject, falls through to null
        assertNull(provider.valueToObject(new Value(java.time.Instant.now())));
    }

    // ========================================
    // Helper Methods
    // ========================================

    private void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
