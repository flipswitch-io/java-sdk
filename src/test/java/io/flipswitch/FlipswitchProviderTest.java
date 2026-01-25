package io.flipswitch;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderState;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        private boolean failInit = false;
        private int initFailCode = 401;

        void setFlagResponse(String flagKey, Supplier<MockResponse> responseSupplier) {
            flagResponses.put(flagKey, responseSupplier);
        }

        void setBulkResponse(Supplier<MockResponse> responseSupplier) {
            this.bulkResponse = responseSupplier;
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
}
