package io.flipswitch;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SSE client for real-time flag change notifications.
 * Handles automatic reconnection with exponential backoff.
 */
public class SseClient {

    private static final Logger log = LoggerFactory.getLogger(SseClient.class);

    private static final long MIN_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> telemetryHeaders;
    private final Consumer<FlagChangeEvent> onFlagChange;
    private final Consumer<ConnectionStatus> onStatusChange;
    private final OkHttpClient httpClient;
    private final JsonAdapter<FlagChangeEvent> eventAdapter;
    private final JsonAdapter<ConfigUpdatedEvent> configEventAdapter;
    private final ScheduledExecutorService scheduler;

    private EventSource eventSource;
    private final AtomicLong retryDelay = new AtomicLong(MIN_RETRY_DELAY_MS);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ConnectionStatus status = ConnectionStatus.DISCONNECTED;

    /**
     * Connection status enum.
     */
    public enum ConnectionStatus {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    /**
     * Create a new SSE client.
     *
     * @param baseUrl          The Flipswitch server base URL
     * @param apiKey           The environment API key
     * @param telemetryHeaders Optional telemetry headers to send with SSE requests (can be null)
     * @param onFlagChange     Callback for flag change events
     * @param onStatusChange   Callback for connection status changes (can be null)
     */
    public SseClient(String baseUrl, String apiKey,
                     Map<String, String> telemetryHeaders,
                     Consumer<FlagChangeEvent> onFlagChange,
                     Consumer<ConnectionStatus> onStatusChange) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.telemetryHeaders = telemetryHeaders;
        this.onFlagChange = onFlagChange;
        this.onStatusChange = onStatusChange;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for SSE
                .build();
        Moshi moshi = new Moshi.Builder().build();
        this.eventAdapter = moshi.adapter(FlagChangeEvent.class);
        this.configEventAdapter = moshi.adapter(ConfigUpdatedEvent.class);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flipswitch-sse-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the SSE connection.
     */
    public void connect() {
        if (closed.get()) {
            return;
        }

        closeEventSource();
        updateStatus(ConnectionStatus.CONNECTING);

        String url = baseUrl + "/api/v1/flags/events";

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");

        // Add telemetry headers
        if (telemetryHeaders != null) {
            for (Map.Entry<String, String> entry : telemetryHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        Request request = requestBuilder.build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        eventSource = factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                log.info("SSE connection established");
                updateStatus(ConnectionStatus.CONNECTED);
                retryDelay.set(MIN_RETRY_DELAY_MS);
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                handleEvent(type, data);
            }

            @Override
            public void onClosed(EventSource eventSource) {
                log.info("SSE connection closed");
                updateStatus(ConnectionStatus.DISCONNECTED);
                scheduleReconnect();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                if (!closed.get()) {
                    log.error("SSE connection error: {}", t != null ? t.getMessage() : "unknown");
                    updateStatus(ConnectionStatus.ERROR);
                    scheduleReconnect();
                }
            }
        });
    }

    /**
     * Handle incoming SSE events.
     */
    private void handleEvent(String type, String data) {
        if ("heartbeat".equals(type)) {
            log.trace("Heartbeat received");
            return;
        }

        try {
            if ("flag-updated".equals(type)) {
                // Single flag was modified
                FlagChangeEvent event = eventAdapter.fromJson(data);
                log.debug("Flag updated event: {}", event);
                if (event != null) {
                    onFlagChange.accept(event);
                }
            } else if ("config-updated".equals(type)) {
                // Configuration changed, need to refresh all flags
                ConfigUpdatedEvent configEvent = configEventAdapter.fromJson(data);
                log.debug("Config updated event: {}", configEvent);

                // Log warning for api-key-rotated
                if (configEvent != null && "api-key-rotated".equals(configEvent.reason())) {
                    log.warn("API key has been rotated. You may need to update your API key configuration.");
                }

                // Create a FlagChangeEvent with null flagKey to trigger full refresh
                FlagChangeEvent event = new FlagChangeEvent(null, configEvent != null ? configEvent.timestamp() : null);
                onFlagChange.accept(event);
            } else if ("flag-change".equals(type)) {
                // Legacy event format for backward compatibility
                FlagChangeEvent event = eventAdapter.fromJson(data);
                log.debug("Flag change event: {}", event);
                if (event != null) {
                    onFlagChange.accept(event);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse {} event: {}", type, e.getMessage());
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     */
    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }

        long delay = retryDelay.get();
        log.info("Scheduling SSE reconnect in {}ms", delay);

        scheduler.schedule(() -> {
            if (!closed.get()) {
                connect();
                // Increase backoff for next attempt
                retryDelay.updateAndGet(d -> Math.min(d * 2, MAX_RETRY_DELAY_MS));
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Update and broadcast connection status.
     */
    private void updateStatus(ConnectionStatus newStatus) {
        this.status = newStatus;
        if (onStatusChange != null) {
            try {
                onStatusChange.accept(newStatus);
            } catch (Exception e) {
                log.error("Error in status change callback: {}", e.getMessage());
            }
        }
    }

    /**
     * Get current connection status.
     */
    public ConnectionStatus getStatus() {
        return status;
    }

    /**
     * Close the SSE connection and stop reconnection attempts.
     */
    public void close() {
        closed.set(true);
        updateStatus(ConnectionStatus.DISCONNECTED);
        closeEventSource();
        scheduler.shutdownNow();
    }

    private void closeEventSource() {
        if (eventSource != null) {
            eventSource.cancel();
            eventSource = null;
        }
    }
}
