package io.flipswitch;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SseClient.
 *
 * Since handleEvent is private, event parsing is tested by sending SSE events
 * through MockWebServer and verifying the callbacks that fire.
 */
class SseClientTest {

    private MockWebServer mockServer;
    private SseClient sseClient;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sseClient != null) {
            sseClient.close();
        }
        mockServer.close();
    }

    /**
     * Build an SSE-formatted body string from one or more SSE frames.
     * Each frame is: "event: {type}\ndata: {data}\n\n"
     */
    private static String sseFrame(String eventType, String data) {
        return "event: " + eventType + "\n" +
               "data: " + data + "\n" +
               "\n";
    }

    /**
     * Create a MockResponse that serves SSE content with the given body.
     */
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
     * Create an SseClient pointing at the mock server with the given callbacks.
     */
    private SseClient createClient(Consumer<FlagChangeEvent> onFlagChange,
                                   Consumer<SseClient.ConnectionStatus> onStatusChange) {
        String baseUrl = mockServer.url("/").toString();
        return new SseClient(baseUrl, "test-api-key", null, onFlagChange, onStatusChange);
    }

    // ========================================
    // Unit Tests - No server needed
    // ========================================

    @Test
    void testInitialStatusIsDisconnected() throws Exception {
        mockServer.start();
        sseClient = createClient(event -> {}, status -> {});

        assertEquals(SseClient.ConnectionStatus.DISCONNECTED, sseClient.getStatus());
    }

    @Test
    void testCloseUpdatesStatusToDisconnected() throws Exception {
        mockServer.start();
        CountDownLatch connectingLatch = new CountDownLatch(1);

        sseClient = createClient(event -> {}, status -> {
            if (status == SseClient.ConnectionStatus.CONNECTING) {
                connectingLatch.countDown();
            }
        });

        // Enqueue a response that holds the connection open
        mockServer.enqueue(sseResponse("event: heartbeat\ndata: {}\n\n"));
        sseClient.connect();

        // Wait for the connecting status, then close
        connectingLatch.await(5, TimeUnit.SECONDS);
        sseClient.close();

        assertEquals(SseClient.ConnectionStatus.DISCONNECTED, sseClient.getStatus());
    }

    @Test
    void testClosePreventsFurtherConnections() throws Exception {
        mockServer.start();
        List<SseClient.ConnectionStatus> statuses = new CopyOnWriteArrayList<>();

        sseClient = createClient(event -> {}, statuses::add);

        sseClient.close();

        // After close, connect() should be a no-op
        sseClient.connect();

        // Give it a moment to ensure nothing happens
        Thread.sleep(500);

        // The only status change should be DISCONNECTED from close()
        // connect() should not have produced a CONNECTING status
        assertTrue(statuses.stream().noneMatch(s -> s == SseClient.ConnectionStatus.CONNECTING),
                "connect() should be a no-op after close()");
    }

    @Test
    void testStatusChangeCallbackInvoked() throws Exception {
        mockServer.start();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        List<SseClient.ConnectionStatus> statuses = new CopyOnWriteArrayList<>();

        sseClient = createClient(event -> {}, status -> {
            statuses.add(status);
            if (status == SseClient.ConnectionStatus.CONNECTED) {
                connectedLatch.countDown();
            }
        });

        // Serve an SSE response with a heartbeat to keep it alive briefly
        mockServer.enqueue(sseResponse(sseFrame("heartbeat", "{}")));
        sseClient.connect();

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Should reach CONNECTED status");

        // Should have seen CONNECTING then CONNECTED
        assertTrue(statuses.size() >= 2, "Expected at least 2 status changes, got: " + statuses);
        assertEquals(SseClient.ConnectionStatus.CONNECTING, statuses.get(0));
        assertEquals(SseClient.ConnectionStatus.CONNECTED, statuses.get(1));
    }

    // ========================================
    // Event Handling Tests via MockWebServer
    // ========================================

    @Test
    void testHandleEvent_FlagUpdated() throws Exception {
        mockServer.start();
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<FlagChangeEvent> receivedEvent = new AtomicReference<>();

        sseClient = createClient(event -> {
            receivedEvent.set(event);
            eventLatch.countDown();
        }, status -> {});

        String json = "{\"flagKey\":\"test-flag\",\"timestamp\":\"2024-01-01T00:00:00Z\"}";
        mockServer.enqueue(sseResponse(sseFrame("flag-updated", json)));
        sseClient.connect();

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Should receive flag-updated event");

        FlagChangeEvent event = receivedEvent.get();
        assertNotNull(event);
        assertEquals("test-flag", event.flagKey());
        assertEquals("2024-01-01T00:00:00Z", event.timestamp());
    }

    @Test
    void testHandleEvent_ConfigUpdated() throws Exception {
        mockServer.start();
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<FlagChangeEvent> receivedEvent = new AtomicReference<>();

        sseClient = createClient(event -> {
            receivedEvent.set(event);
            eventLatch.countDown();
        }, status -> {});

        String json = "{\"timestamp\":\"2024-01-01T12:00:00Z\"}";
        mockServer.enqueue(sseResponse(sseFrame("config-updated", json)));
        sseClient.connect();

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Should receive config-updated event");

        FlagChangeEvent event = receivedEvent.get();
        assertNotNull(event);
        assertNull(event.flagKey(), "config-updated should produce FlagChangeEvent with null flagKey");
        assertEquals("2024-01-01T12:00:00Z", event.timestamp());
    }

    @Test
    void testHandleEvent_ApiKeyRotated() throws Exception {
        mockServer.start();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        List<FlagChangeEvent> receivedEvents = new CopyOnWriteArrayList<>();

        sseClient = createClient(receivedEvents::add, status -> {
            if (status == SseClient.ConnectionStatus.CONNECTED) {
                connectedLatch.countDown();
            }
        });

        String json = "{\"validUntil\":\"2024-06-01T00:00:00Z\",\"timestamp\":\"2024-01-01T00:00:00Z\"}";
        mockServer.enqueue(sseResponse(sseFrame("api-key-rotated", json)));
        sseClient.connect();

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Should connect");

        // Wait a bit for event processing
        Thread.sleep(1000);

        // api-key-rotated should NOT trigger onFlagChange
        assertTrue(receivedEvents.isEmpty(),
                "api-key-rotated should not trigger flag change callback, but got: " + receivedEvents);
    }

    @Test
    void testHandleEvent_Heartbeat() throws Exception {
        mockServer.start();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        List<FlagChangeEvent> receivedEvents = new CopyOnWriteArrayList<>();

        sseClient = createClient(receivedEvents::add, status -> {
            if (status == SseClient.ConnectionStatus.CONNECTED) {
                connectedLatch.countDown();
            }
        });

        mockServer.enqueue(sseResponse(sseFrame("heartbeat", "{}")));
        sseClient.connect();

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Should connect");

        // Wait a bit for event processing
        Thread.sleep(1000);

        // Heartbeat should NOT trigger onFlagChange
        assertTrue(receivedEvents.isEmpty(),
                "heartbeat should not trigger flag change callback, but got: " + receivedEvents);
    }

    @Test
    void testHandleEvent_MalformedJson() throws Exception {
        mockServer.start();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        List<FlagChangeEvent> receivedEvents = new CopyOnWriteArrayList<>();

        sseClient = createClient(receivedEvents::add, status -> {
            if (status == SseClient.ConnectionStatus.CONNECTED) {
                connectedLatch.countDown();
            }
        });

        // Send malformed JSON for a flag-updated event
        mockServer.enqueue(sseResponse(sseFrame("flag-updated", "this is not json")));
        sseClient.connect();

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Should connect");

        // Wait a bit to ensure no crash and no event delivered
        Thread.sleep(1000);

        assertTrue(receivedEvents.isEmpty(),
                "Malformed JSON should not trigger flag change callback");
    }

    @Test
    void testExponentialBackoff_DelayDoubles() throws Exception {
        mockServer.start();

        // Track connection attempts by counting requests
        List<Long> requestTimestamps = new CopyOnWriteArrayList<>();
        CountDownLatch requestLatch = new CountDownLatch(3);

        mockServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                requestTimestamps.add(System.currentTimeMillis());
                requestLatch.countDown();
                // Return an error so the client reconnects
                return new MockResponse.Builder()
                        .code(500)
                        .body("Internal Server Error")
                        .build();
            }
        });

        sseClient = createClient(event -> {}, status -> {});
        sseClient.connect();

        // Wait for at least 3 connection attempts (initial + 2 retries)
        // The minimum backoff is 1000ms, then 2000ms, so we need at least ~3s
        assertTrue(requestLatch.await(15, TimeUnit.SECONDS),
                "Should have at least 3 connection attempts");

        // Verify that delays increase between attempts
        assertTrue(requestTimestamps.size() >= 3,
                "Expected at least 3 requests, got: " + requestTimestamps.size());

        long delay1 = requestTimestamps.get(1) - requestTimestamps.get(0);
        long delay2 = requestTimestamps.get(2) - requestTimestamps.get(1);

        // The second delay should be roughly double the first (with some tolerance)
        // First retry delay is MIN_RETRY_DELAY_MS (1000ms), second is 2000ms
        // Allow generous tolerance for scheduling jitter
        assertTrue(delay2 > delay1,
                "Second delay (" + delay2 + "ms) should be greater than first delay (" + delay1 + "ms)");
    }

    // ========================================
    // Integration Tests with MockWebServer
    // ========================================

    @Test
    void testConnection_ReceivesConnectedStatus() throws Exception {
        mockServer.start();
        CompletableFuture<SseClient.ConnectionStatus> connectedFuture = new CompletableFuture<>();

        sseClient = createClient(event -> {}, status -> {
            if (status == SseClient.ConnectionStatus.CONNECTED) {
                connectedFuture.complete(status);
            }
        });

        mockServer.enqueue(sseResponse(sseFrame("heartbeat", "{}")));
        sseClient.connect();

        SseClient.ConnectionStatus status = connectedFuture.get(5, TimeUnit.SECONDS);
        assertEquals(SseClient.ConnectionStatus.CONNECTED, status);
    }

    @Test
    void testEventDelivery_FlagUpdated() throws Exception {
        mockServer.start();
        CountDownLatch eventLatch = new CountDownLatch(2);
        List<FlagChangeEvent> events = new CopyOnWriteArrayList<>();

        sseClient = createClient(event -> {
            events.add(event);
            eventLatch.countDown();
        }, status -> {});

        // Send two flag-updated events in a single SSE stream
        String body = sseFrame("flag-updated", "{\"flagKey\":\"flag-a\",\"timestamp\":\"2024-01-01T00:00:00Z\"}")
                     + sseFrame("flag-updated", "{\"flagKey\":\"flag-b\",\"timestamp\":\"2024-01-01T01:00:00Z\"}");
        mockServer.enqueue(sseResponse(body));
        sseClient.connect();

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Should receive 2 flag-updated events");
        assertEquals(2, events.size());
        assertEquals("flag-a", events.get(0).flagKey());
        assertEquals("flag-b", events.get(1).flagKey());
    }

    @Test
    void testEventDelivery_ConfigUpdated() throws Exception {
        mockServer.start();
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<FlagChangeEvent> receivedEvent = new AtomicReference<>();

        sseClient = createClient(event -> {
            receivedEvent.set(event);
            eventLatch.countDown();
        }, status -> {});

        String body = sseFrame("config-updated", "{\"timestamp\":\"2024-03-15T10:30:00Z\"}");
        mockServer.enqueue(sseResponse(body));
        sseClient.connect();

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Should receive config-updated event");

        FlagChangeEvent event = receivedEvent.get();
        assertNotNull(event);
        assertNull(event.flagKey(), "config-updated should yield null flagKey for full refresh");
        assertEquals("2024-03-15T10:30:00Z", event.timestamp());
    }

    @Test
    void testReconnection_OnServerClose() throws Exception {
        mockServer.start();
        CountDownLatch secondConnectLatch = new CountDownLatch(2);
        List<SseClient.ConnectionStatus> statuses = new CopyOnWriteArrayList<>();

        sseClient = createClient(event -> {}, status -> {
            statuses.add(status);
            if (status == SseClient.ConnectionStatus.CONNECTED) {
                secondConnectLatch.countDown();
            }
        });

        // First response closes immediately (empty body triggers onClosed),
        // then second response succeeds
        mockServer.enqueue(sseResponse(""));
        mockServer.enqueue(sseResponse(sseFrame("heartbeat", "{}")));

        sseClient.connect();

        // Wait for both connections (initial + reconnect)
        assertTrue(secondConnectLatch.await(10, TimeUnit.SECONDS),
                "Should reconnect after server closes connection. Statuses: " + statuses);

        // Verify the status progression includes a reconnection cycle
        // Expected: CONNECTING -> CONNECTED -> DISCONNECTED -> CONNECTING -> CONNECTED
        // (or CONNECTING -> DISCONNECTED -> CONNECTING -> CONNECTED if the empty body doesn't trigger onOpen)
        long connectedCount = statuses.stream()
                .filter(s -> s == SseClient.ConnectionStatus.CONNECTED)
                .count();
        assertTrue(connectedCount >= 2,
                "Should have connected at least twice (initial + reconnect). Statuses: " + statuses);
    }

    @Test
    void testErrorHandling_Non200Status() throws Exception {
        mockServer.start();
        CountDownLatch errorLatch = new CountDownLatch(1);
        List<SseClient.ConnectionStatus> statuses = new CopyOnWriteArrayList<>();

        sseClient = createClient(event -> {}, status -> {
            statuses.add(status);
            if (status == SseClient.ConnectionStatus.ERROR) {
                errorLatch.countDown();
            }
        });

        // Return 401 Unauthorized
        mockServer.enqueue(new MockResponse.Builder()
                .code(401)
                .body("Unauthorized")
                .build());

        // Enqueue a second response for the reconnect attempt so the test can proceed
        mockServer.enqueue(sseResponse(sseFrame("heartbeat", "{}")));

        sseClient.connect();

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS),
                "Should receive ERROR status on non-200 response. Statuses: " + statuses);

        assertTrue(statuses.contains(SseClient.ConnectionStatus.ERROR),
                "Status history should contain ERROR. Statuses: " + statuses);
    }

    // ========================================
    // Telemetry Header Tests
    // ========================================

    @Test
    void testTelemetryHeadersSentWithRequest() throws Exception {
        mockServer.start();
        CountDownLatch requestLatch = new CountDownLatch(1);

        mockServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                requestLatch.countDown();
                return sseResponse(sseFrame("heartbeat", "{}"));
            }
        });

        Map<String, String> telemetryHeaders = Map.of(
                "X-SDK-Version", "0.1.0",
                "X-SDK-Language", "java"
        );

        String baseUrl = mockServer.url("/").toString();
        sseClient = new SseClient(baseUrl, "test-api-key", telemetryHeaders,
                event -> {}, status -> {});
        sseClient.connect();

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Should receive request");

        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-api-key", request.getHeaders().get("X-API-Key"));
        assertEquals("0.1.0", request.getHeaders().get("X-SDK-Version"));
        assertEquals("java", request.getHeaders().get("X-SDK-Language"));
        assertEquals("text/event-stream", request.getHeaders().get("Accept"));
    }

    @Test
    void testRequestTargetsCorrectEndpoint() throws Exception {
        mockServer.start();
        CountDownLatch requestLatch = new CountDownLatch(1);

        mockServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                requestLatch.countDown();
                return sseResponse(sseFrame("heartbeat", "{}"));
            }
        });

        sseClient = createClient(event -> {}, status -> {});
        sseClient.connect();

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Should receive request");

        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/v1/flags/events", request.getUrl().encodedPath());
    }
}
