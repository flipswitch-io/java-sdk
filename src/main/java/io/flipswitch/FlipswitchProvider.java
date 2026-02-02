package io.flipswitch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import dev.openfeature.contrib.providers.ofrep.OfrepProvider;
import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions;
import dev.openfeature.sdk.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Flipswitch OpenFeature provider with real-time SSE support.
 * <p>
 * This provider wraps the OFREP provider for flag evaluation and adds
 * real-time updates via Server-Sent Events (SSE).
 * </p>
 *
 * <pre>{@code
 * // API key is required, all other options have sensible defaults
 * FlipswitchProvider provider = FlipswitchProvider.builder("your-api-key").build();
 *
 * OpenFeatureAPI.getInstance().setProviderAndWait(provider);
 * Client client = OpenFeatureAPI.getInstance().getClient();
 *
 * boolean darkMode = client.getBooleanValue("dark-mode", false);
 * }</pre>
 */
public class FlipswitchProvider extends EventProvider {

    private static final Logger log = LoggerFactory.getLogger(FlipswitchProvider.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String DEFAULT_BASE_URL = "https://api.flipswitch.io";
    private static final String SDK_VERSION = getVersionFromManifest();
    private static final long DEFAULT_POLLING_INTERVAL_MS = 30000;
    private static final int DEFAULT_MAX_SSE_RETRIES = 5;

    private static String getVersionFromManifest() {
        String version = FlipswitchProvider.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    private final String baseUrl;
    private final String apiKey;
    private final boolean enableRealtime;
    private final OkHttpClient httpClient;
    private final Moshi moshi;
    private final JsonAdapter<Map<String, Object>> mapAdapter;
    private final OfrepProvider ofrepProvider;
    private final CopyOnWriteArrayList<Consumer<FlagChangeEvent>> flagChangeListeners;

    // Polling fallback configuration
    private final boolean enablePollingFallback;
    private final long pollingIntervalMs;
    private final int maxSseRetries;
    private final ScheduledExecutorService pollingScheduler;
    private volatile int sseRetryCount = 0;
    private volatile boolean pollingActive = false;
    private java.util.concurrent.ScheduledFuture<?> pollingFuture;

    private SseClient sseClient;
    private volatile ProviderState state = ProviderState.NOT_READY;

    private FlipswitchProvider(Builder builder) {
        this.baseUrl = builder.baseUrl.replaceAll("/$", "");
        this.apiKey = builder.apiKey;
        this.enableRealtime = builder.enableRealtime;
        this.httpClient = builder.httpClient != null ? builder.httpClient : new OkHttpClient();
        this.moshi = new Moshi.Builder().build();
        Type mapType = Types.newParameterizedType(Map.class, String.class, Object.class);
        this.mapAdapter = moshi.adapter(mapType);
        this.flagChangeListeners = new CopyOnWriteArrayList<>();

        // Polling fallback configuration
        this.enablePollingFallback = builder.enablePollingFallback;
        this.pollingIntervalMs = builder.pollingIntervalMs;
        this.maxSseRetries = builder.maxSseRetries;
        this.pollingScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flipswitch-polling");
            t.setDaemon(true);
            return t;
        });

        // Create underlying OFREP provider for flag evaluation
        ImmutableMap.Builder<String, ImmutableList<String>> headersBuilder = ImmutableMap.builder();
        headersBuilder.put("X-API-Key", ImmutableList.of(this.apiKey));
        headersBuilder.put("X-Flipswitch-SDK", ImmutableList.of(getTelemetrySdkHeader()));
        headersBuilder.put("X-Flipswitch-Runtime", ImmutableList.of(getTelemetryRuntimeHeader()));
        headersBuilder.put("X-Flipswitch-OS", ImmutableList.of(getTelemetryOsHeader()));
        headersBuilder.put("X-Flipswitch-Features", ImmutableList.of(getTelemetryFeaturesHeader()));

        OfrepProviderOptions ofrepOptions = OfrepProviderOptions.builder()
                .baseUrl(this.baseUrl)
                .headers(headersBuilder.build())
                .build();
        this.ofrepProvider = OfrepProvider.constructProvider(ofrepOptions);
    }

    private String getTelemetrySdkHeader() {
        return "java/" + SDK_VERSION;
    }

    private String getTelemetryRuntimeHeader() {
        return "java/" + System.getProperty("java.version", "unknown");
    }

    private String getTelemetryOsHeader() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown").toLowerCase();

        // Normalize OS name
        if (os.contains("mac") || os.contains("darwin")) {
            os = "darwin";
        } else if (os.contains("win")) {
            os = "windows";
        } else if (os.contains("linux")) {
            os = "linux";
        }

        // Normalize architecture
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            arch = "amd64";
        } else if (arch.equals("aarch64")) {
            arch = "arm64";
        }

        return os + "/" + arch;
    }

    private String getTelemetryFeaturesHeader() {
        return "sse=" + enableRealtime;
    }

    /**
     * Create a new builder for FlipswitchProvider.
     *
     * @param apiKey The environment API key (required)
     */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    @Override
    public Metadata getMetadata() {
        return () -> "flipswitch";
    }

    @Override
    public ProviderState getState() {
        return state;
    }

    @Override
    public void initialize(EvaluationContext context) throws Exception {
        state = ProviderState.NOT_READY;

        // Validate API key first (OFREP provider doesn't throw on auth errors during init)
        validateApiKey();

        // Initialize the underlying OFREP provider
        ofrepProvider.initialize(context);

        // Start SSE connection
        if (enableRealtime) {
            startSseConnection();
        }

        state = ProviderState.READY;
        log.info("Flipswitch provider initialized (realtime={})", enableRealtime);
    }

    /**
     * Validate the API key by making a bulk evaluation request.
     */
    private void validateApiKey() throws IOException {
        RequestBody body = RequestBody.create("{\"context\":{\"targetingKey\":\"_init_\"}}", JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl + "/ofrep/v1/evaluate/flags")
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .post(body);

        addTelemetryHeaders(requestBuilder);

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (response.code() == 401 || response.code() == 403) {
                state = ProviderState.ERROR;
                throw new IOException("Invalid API key");
            }
            if (!response.isSuccessful() && response.code() != 404) {
                state = ProviderState.ERROR;
                throw new IOException("Failed to connect to Flipswitch: " + response.code());
            }
        }
    }

    /**
     * Add telemetry headers to the request.
     */
    private void addTelemetryHeaders(Request.Builder requestBuilder) {
        requestBuilder.header("X-Flipswitch-SDK", getTelemetrySdkHeader());
        requestBuilder.header("X-Flipswitch-Runtime", getTelemetryRuntimeHeader());
        requestBuilder.header("X-Flipswitch-OS", getTelemetryOsHeader());
        requestBuilder.header("X-Flipswitch-Features", getTelemetryFeaturesHeader());
    }

    @Override
    public void shutdown() {
        // Stop polling if active
        stopPolling();
        pollingScheduler.shutdownNow();

        if (sseClient != null) {
            sseClient.close();
            sseClient = null;
        }
        ofrepProvider.shutdown();
        state = ProviderState.NOT_READY;
        log.info("Flipswitch provider shut down");
    }

    /**
     * Start polling fallback when SSE fails.
     */
    private void startPollingFallback() {
        if (pollingActive || !enablePollingFallback) {
            return;
        }

        log.info("Starting polling fallback (interval: {}ms)", pollingIntervalMs);
        pollingActive = true;
        pollingFuture = pollingScheduler.scheduleAtFixedRate(
                this::pollFlags,
                pollingIntervalMs,
                pollingIntervalMs,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
    }

    /**
     * Poll for flag updates.
     */
    private void pollFlags() {
        // The OFREP Java provider doesn't expose cache invalidation,
        // but emitting configuration changed will trigger re-evaluation
        log.debug("Polling: checking for flag updates");
        emitProviderConfigurationChanged(ProviderEventDetails.builder().build());
    }

    /**
     * Stop polling fallback.
     */
    private void stopPolling() {
        if (!pollingActive) {
            return;
        }

        pollingActive = false;
        if (pollingFuture != null) {
            pollingFuture.cancel(false);
            pollingFuture = null;
        }
    }

    /**
     * Check if polling fallback is active.
     */
    public boolean isPollingActive() {
        return pollingActive;
    }

    /**
     * Start the SSE connection for real-time updates.
     */
    private void startSseConnection() {
        Map<String, String> telemetryHeaders = getTelemetryHeadersMap();
        sseClient = new SseClient(
                baseUrl,
                apiKey,
                telemetryHeaders,
                this::handleFlagChange,
                status -> {
                    if (status == SseClient.ConnectionStatus.ERROR) {
                        sseRetryCount++;
                        log.warn("SSE connection error (retry {}), provider is stale", sseRetryCount);

                        // Check if we should fall back to polling
                        if (sseRetryCount >= maxSseRetries && enablePollingFallback) {
                            log.warn("SSE failed after {} retries - falling back to polling", sseRetryCount);
                            startPollingFallback();
                        }

                        state = ProviderState.STALE;
                        emitProviderStale(ProviderEventDetails.builder().build());
                    } else if (status == SseClient.ConnectionStatus.CONNECTED) {
                        // SSE connected - reset retry count and stop polling
                        sseRetryCount = 0;

                        if (pollingActive) {
                            log.info("SSE reconnected - stopping polling fallback");
                            stopPolling();
                        }

                        if (state == ProviderState.STALE) {
                            state = ProviderState.READY;
                            emitProviderReady(ProviderEventDetails.builder().build());
                        }
                    }
                }
        );
        sseClient.connect();
    }

    /**
     * Get telemetry headers as a map.
     */
    private Map<String, String> getTelemetryHeadersMap() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Flipswitch-SDK", getTelemetrySdkHeader());
        headers.put("X-Flipswitch-Runtime", getTelemetryRuntimeHeader());
        headers.put("X-Flipswitch-OS", getTelemetryOsHeader());
        headers.put("X-Flipswitch-Features", getTelemetryFeaturesHeader());
        return headers;
    }

    /**
     * Handle a flag change event from SSE.
     * Emits PROVIDER_CONFIGURATION_CHANGED to trigger re-evaluation.
     */
    private void handleFlagChange(FlagChangeEvent event) {
        // Notify user-registered listeners
        for (Consumer<FlagChangeEvent> listener : flagChangeListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in flag change listener: {}", e.getMessage());
            }
        }

        // Emit configuration changed event - OpenFeature clients will re-evaluate flags
        emitProviderConfigurationChanged(ProviderEventDetails.builder().build());
    }

    /**
     * Add a listener for flag change events.
     */
    public void addFlagChangeListener(Consumer<FlagChangeEvent> listener) {
        flagChangeListeners.add(listener);
    }

    /**
     * Remove a flag change listener.
     */
    public void removeFlagChangeListener(Consumer<FlagChangeEvent> listener) {
        flagChangeListeners.remove(listener);
    }

    /**
     * Get SSE connection status.
     */
    public SseClient.ConnectionStatus getSseStatus() {
        return sseClient != null ? sseClient.getStatus() : SseClient.ConnectionStatus.DISCONNECTED;
    }

    /**
     * Force reconnect SSE connection.
     */
    public void reconnectSse() {
        if (enableRealtime && sseClient != null) {
            sseClient.close();
            startSseConnection();
        }
    }

    // ===============================
    // Flag Resolution Methods - Delegated to OFREP Provider
    // ===============================

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
                                                            EvaluationContext ctx) {
        return ofrepProvider.getBooleanEvaluation(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue,
                                                          EvaluationContext ctx) {
        return ofrepProvider.getStringEvaluation(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue,
                                                            EvaluationContext ctx) {
        return ofrepProvider.getIntegerEvaluation(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue,
                                                          EvaluationContext ctx) {
        return ofrepProvider.getDoubleEvaluation(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue,
                                                         EvaluationContext ctx) {
        return ofrepProvider.getObjectEvaluation(key, defaultValue, ctx);
    }

    // ===============================
    // Bulk Flag Evaluation (Direct HTTP - OFREP providers don't expose bulk API)
    // ===============================

    /**
     * Evaluate all flags for the given context.
     * Returns a list of all flag evaluations with their keys, values, types, and reasons.
     *
     * Note: This method makes direct HTTP calls since OFREP providers don't expose
     * the bulk evaluation API.
     *
     * @param context The evaluation context
     * @return List of flag evaluations
     */
    public List<FlagEvaluation> evaluateAllFlags(EvaluationContext context) {
        List<FlagEvaluation> results = new ArrayList<>();

        try {
            String url = baseUrl + "/ofrep/v1/evaluate/flags";

            Map<String, Object> bodyMap = new LinkedHashMap<>();
            Map<String, Object> contextMap = transformContext(context);
            bodyMap.put("context", contextMap);

            RequestBody body = RequestBody.create(mapAdapter.toJson(bodyMap), JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .post(body);

            addTelemetryHeaders(requestBuilder);

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Failed to evaluate all flags: {}", response.code());
                    return results;
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                Map<String, Object> result = mapAdapter.fromJson(responseBody);

                if (result != null) {
                    Object flagsObj = result.get("flags");
                    if (flagsObj instanceof List<?> flags) {
                        for (Object flagObj : flags) {
                            if (flagObj instanceof Map<?, ?> flag) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> flagMap = (Map<String, Object>) flag;
                                String key = getStringValue(flagMap, "key");
                                Object value = flagMap.get("value");
                                String reason = getStringValue(flagMap, "reason");
                                String variant = getStringValue(flagMap, "variant");
                                String flagType = extractFlagTypeFromMetadata(flagMap);

                                if (key != null) {
                                    results.add(new FlagEvaluation(key, value, reason, variant, flagType));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating all flags: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Evaluate a single flag and return its evaluation result.
     *
     * Note: This method makes direct HTTP calls for demo purposes.
     * For standard flag evaluation, use the OpenFeature client methods.
     *
     * @param flagKey The flag key to evaluate
     * @param context The evaluation context
     * @return The flag evaluation, or null if the flag doesn't exist
     */
    public FlagEvaluation evaluateFlag(String flagKey, EvaluationContext context) {
        try {
            String url = baseUrl + "/ofrep/v1/evaluate/flags/" + flagKey;

            Map<String, Object> bodyMap = new LinkedHashMap<>();
            Map<String, Object> contextMap = transformContext(context);
            bodyMap.put("context", contextMap);

            RequestBody body = RequestBody.create(mapAdapter.toJson(bodyMap), JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .post(body);

            addTelemetryHeaders(requestBuilder);

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                Map<String, Object> result = mapAdapter.fromJson(responseBody);

                if (result != null) {
                    String key = getStringValue(result, "key");
                    if (key == null) key = flagKey;
                    Object value = result.get("value");
                    String reason = getStringValue(result, "reason");
                    String variant = getStringValue(result, "variant");
                    String flagType = extractFlagTypeFromMetadata(result);

                    return new FlagEvaluation(key, value, reason, variant, flagType);
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating flag '{}': {}", flagKey, e.getMessage());
        }
        return null;
    }

    /**
     * Safely get a String value from a map.
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Extract flagType from metadata if available.
     */
    private String extractFlagTypeFromMetadata(Map<String, Object> flagMap) {
        Object metadataObj = flagMap.get("metadata");
        if (metadataObj instanceof Map<?, ?> metadata) {
            Object flagType = metadata.get("flagType");
            if (flagType instanceof String) {
                return (String) flagType;
            }
        }
        return null;
    }

    /**
     * Transform OpenFeature context to OFREP context format.
     */
    private Map<String, Object> transformContext(EvaluationContext context) {
        Map<String, Object> map = new LinkedHashMap<>();

        if (context.getTargetingKey() != null) {
            map.put("targetingKey", context.getTargetingKey());
        }

        for (Map.Entry<String, Value> entry : context.asMap().entrySet()) {
            String key = entry.getKey();
            if (!"targetingKey".equals(key)) {
                map.put(key, valueToObject(entry.getValue()));
            }
        }

        return map;
    }

    /**
     * Convert an OpenFeature Value to a plain Java object.
     */
    private Object valueToObject(Value value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.isString()) {
            return value.asString();
        } else if (value.isNumber()) {
            if (value.asDouble() == Math.floor(value.asDouble())) {
                return value.asInteger();
            } else {
                return value.asDouble();
            }
        } else if (value.isList()) {
            List<Object> list = new ArrayList<>();
            for (Value item : value.asList()) {
                list.add(valueToObject(item));
            }
            return list;
        } else if (value.isStructure()) {
            Map<String, Object> structMap = new LinkedHashMap<>();
            for (Map.Entry<String, Value> entry : value.asStructure().asMap().entrySet()) {
                structMap.put(entry.getKey(), valueToObject(entry.getValue()));
            }
            return structMap;
        }
        return null;
    }

    /**
     * Builder for FlipswitchProvider.
     */
    public static class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private boolean enableRealtime = true;
        private OkHttpClient httpClient;
        private boolean enablePollingFallback = true;
        private long pollingIntervalMs = DEFAULT_POLLING_INTERVAL_MS;
        private int maxSseRetries = DEFAULT_MAX_SSE_RETRIES;

        private Builder(String apiKey) {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            this.apiKey = apiKey;
        }

        /**
         * Set the Flipswitch server base URL.
         * Defaults to https://api.flipswitch.io
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Enable or disable real-time SSE updates.
         * Defaults to true.
         */
        public Builder enableRealtime(boolean enableRealtime) {
            this.enableRealtime = enableRealtime;
            return this;
        }

        /**
         * Set a custom OkHttpClient.
         */
        public Builder httpClient(OkHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Enable or disable polling fallback when SSE fails.
         * Defaults to true.
         */
        public Builder enablePollingFallback(boolean enablePollingFallback) {
            this.enablePollingFallback = enablePollingFallback;
            return this;
        }

        /**
         * Set the polling interval in milliseconds for fallback mode.
         * Defaults to 30000 (30 seconds).
         */
        public Builder pollingIntervalMs(long pollingIntervalMs) {
            this.pollingIntervalMs = pollingIntervalMs;
            return this;
        }

        /**
         * Set the maximum SSE retry attempts before falling back to polling.
         * Defaults to 5.
         */
        public Builder maxSseRetries(int maxSseRetries) {
            this.maxSseRetries = maxSseRetries;
            return this;
        }

        /**
         * Build the provider.
         */
        public FlipswitchProvider build() {
            return new FlipswitchProvider(this);
        }
    }
}
