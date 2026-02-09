package io.flipswitch;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.MutableContext;
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
    // Helper Methods
    // ========================================

    private void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
