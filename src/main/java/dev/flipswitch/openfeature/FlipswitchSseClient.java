package dev.flipswitch.openfeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SSE client for receiving real-time flag change notifications.
 * Automatically reconnects with exponential backoff on connection failures.
 */
public class FlipswitchSseClient implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(FlipswitchSseClient.class.getName());

    private final FlipswitchConfig config;
    private final Consumer<FlagChangeEvent> onFlagChange;
    private final Runnable onStale;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reconnectDelay = new AtomicInteger();

    private volatile String lastEventId;
    private volatile long lastHeartbeat;

    /**
     * Creates a new SSE client.
     *
     * @param config       Configuration including server URL and API key
     * @param onFlagChange Callback invoked when flags change
     * @param onStale      Callback invoked when connection becomes stale (no heartbeat)
     */
    public FlipswitchSseClient(
            FlipswitchConfig config,
            Consumer<FlagChangeEvent> onFlagChange,
            Runnable onStale) {
        this.config = config;
        this.onFlagChange = onFlagChange;
        this.onStale = onStale;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flipswitch-sse");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the SSE connection in a background thread.
     */
    public void connect() {
        if (running.compareAndSet(false, true)) {
            reconnectDelay.set(config.getReconnectDelayMs());
            lastHeartbeat = System.currentTimeMillis();
            executor.submit(this::connectionLoop);
            startHeartbeatMonitor();
        }
    }

    /**
     * Stops the SSE connection and releases resources.
     */
    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
    }

    private void connectionLoop() {
        while (running.get()) {
            try {
                connectAndStream();
            } catch (Exception e) {
                if (running.get()) {
                    int delay = reconnectDelay.get();
                    logger.log(Level.WARNING, "SSE connection failed, reconnecting in " + delay + "ms", e);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Exponential backoff with max
                    reconnectDelay.updateAndGet(d ->
                            Math.min(d * 2, config.getMaxReconnectDelayMs()));
                }
            }
        }
    }

    private void connectAndStream() throws IOException, InterruptedException {
        String sseUrl = config.getBaseUrl() + "/api/v1/flags/events";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .header("X-API-Key", config.getApiKey())
                .header("Accept", "text/event-stream")
                .GET();

        // Support resume via Last-Event-ID
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }

        HttpRequest request = requestBuilder.build();

        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("SSE connection failed: HTTP " + response.statusCode());
        }

        logger.info("SSE connection established");
        // Reset backoff on successful connection
        reconnectDelay.set(config.getReconnectDelayMs());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body()))) {
            parseEventStream(reader);
        }
    }

    private void parseEventStream(BufferedReader reader) throws IOException {
        StringBuilder data = new StringBuilder();
        String eventType = null;
        String eventId = null;

        String line;
        while (running.get() && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // Empty line marks end of event
                processEvent(eventType, eventId, data.toString().trim());
                data.setLength(0);
                eventType = null;
                eventId = null;
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("id:")) {
                eventId = line.substring(3).trim();
            }
            // Ignore comments (lines starting with :) and other fields
        }
    }

    private void processEvent(String eventType, String eventId, String data) {
        if (eventId != null) {
            lastEventId = eventId;
        }

        if ("heartbeat".equals(eventType)) {
            lastHeartbeat = System.currentTimeMillis();
            logger.fine("Received heartbeat");
        } else if ("flag-change".equals(eventType)) {
            lastHeartbeat = System.currentTimeMillis();
            logger.info("Received flag-change event: " + data);
            try {
                // Parse JSON data: {"environmentId":123,"flagKey":"my-feature","timestamp":"..."}
                FlagChangeEvent event = parseFlagChangeEvent(data);
                onFlagChange.accept(event);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to parse flag-change event", e);
                // Still trigger refresh even if parsing fails
                onFlagChange.accept(new FlagChangeEvent(0, null, Instant.now()));
            }
        }
    }

    private FlagChangeEvent parseFlagChangeEvent(String data) {
        // Simple JSON parsing without external deps
        // Format: {"environmentId":123,"flagKey":"my-feature","timestamp":"2024-01-02T19:00:00Z"}
        // or:     {"environmentId":123,"flagKey":null,"timestamp":"2024-01-02T19:00:00Z"}

        int environmentId = parseEnvironmentId(data);
        String flagKey = parseFlagKey(data);
        Instant timestamp = parseTimestamp(data);

        return new FlagChangeEvent(environmentId, flagKey, timestamp);
    }

    private int parseEnvironmentId(String data) {
        // Parse "environmentId":123
        int start = data.indexOf("\"environmentId\":");
        if (start >= 0) {
            start += 16; // length of "environmentId":
            int end = start;
            while (end < data.length() && Character.isDigit(data.charAt(end))) {
                end++;
            }
            if (end > start) {
                return Integer.parseInt(data.substring(start, end));
            }
        }
        return 0;
    }

    private String parseFlagKey(String data) {
        // Parse "flagKey":"my-feature" or "flagKey":null
        int start = data.indexOf("\"flagKey\":");
        if (start >= 0) {
            start += 10; // length of "flagKey":
            // Skip whitespace
            while (start < data.length() && Character.isWhitespace(data.charAt(start))) {
                start++;
            }
            if (start < data.length()) {
                if (data.charAt(start) == 'n') {
                    // null value
                    return null;
                } else if (data.charAt(start) == '"') {
                    // String value
                    start++; // skip opening quote
                    int end = data.indexOf("\"", start);
                    if (end > start) {
                        return data.substring(start, end);
                    }
                }
            }
        }
        return null;
    }

    private Instant parseTimestamp(String data) {
        // Parse "timestamp":"2024-01-02T19:00:00Z"
        int start = data.indexOf("\"timestamp\":\"");
        if (start >= 0) {
            start += 13; // length of "timestamp":"
            int end = data.indexOf("\"", start);
            if (end > start) {
                return Instant.parse(data.substring(start, end));
            }
        }
        return Instant.now();
    }

    private void startHeartbeatMonitor() {
        Thread monitor = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    long elapsed = System.currentTimeMillis() - lastHeartbeat;
                    if (elapsed > config.getHeartbeatTimeoutMs()) {
                        logger.warning("No heartbeat for " + elapsed + "ms, marking stale");
                        onStale.run();
                        lastHeartbeat = System.currentTimeMillis(); // Reset to avoid spam
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "flipswitch-heartbeat-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }
}
