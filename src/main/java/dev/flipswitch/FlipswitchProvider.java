package dev.flipswitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.openfeature.contrib.providers.ofrep.OfrepProvider;
import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions;
import dev.openfeature.sdk.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final String DEFAULT_BASE_URL = "https://api.flipswitch.dev";

    private final String baseUrl;
    private final String apiKey;
    private final boolean enableRealtime;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OfrepProvider ofrepProvider;
    private final CopyOnWriteArrayList<Consumer<FlagChangeEvent>> flagChangeListeners;

    private SseClient sseClient;
    private volatile ProviderState state = ProviderState.NOT_READY;

    private FlipswitchProvider(Builder builder) {
        this.baseUrl = builder.baseUrl.replaceAll("/$", "");
        this.apiKey = builder.apiKey;
        this.enableRealtime = builder.enableRealtime;
        this.httpClient = builder.httpClient != null ? builder.httpClient : new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.flagChangeListeners = new CopyOnWriteArrayList<>();

        // Create underlying OFREP provider for flag evaluation
        OfrepProviderOptions ofrepOptions = OfrepProviderOptions.builder()
                .baseUrl(this.baseUrl + "/ofrep/v1")
                .headers(ImmutableMap.of("X-API-Key", ImmutableList.of(this.apiKey)))
                .build();
        this.ofrepProvider = OfrepProvider.constructProvider(ofrepOptions);
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
        Request request = new Request.Builder()
                .url(baseUrl + "/ofrep/v1/evaluate/flags")
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
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

    @Override
    public void shutdown() {
        if (sseClient != null) {
            sseClient.close();
            sseClient = null;
        }
        ofrepProvider.shutdown();
        state = ProviderState.NOT_READY;
        log.info("Flipswitch provider shut down");
    }

    /**
     * Start the SSE connection for real-time updates.
     */
    private void startSseConnection() {
        sseClient = new SseClient(
                baseUrl,
                apiKey,
                this::handleFlagChange,
                status -> {
                    if (status == SseClient.ConnectionStatus.ERROR) {
                        state = ProviderState.STALE;
                        emitProviderStale(ProviderEventDetails.builder().build());
                    } else if (status == SseClient.ConnectionStatus.CONNECTED && state == ProviderState.STALE) {
                        state = ProviderState.READY;
                        emitProviderReady(ProviderEventDetails.builder().build());
                    }
                }
        );
        sseClient.connect();
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

            ObjectNode bodyNode = objectMapper.createObjectNode();
            ObjectNode contextNode = transformContext(context);
            bodyNode.set("context", contextNode);

            RequestBody body = RequestBody.create(objectMapper.writeValueAsString(bodyNode), JSON);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Failed to evaluate all flags: {}", response.code());
                    return results;
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                JsonNode result = objectMapper.readTree(responseBody);

                JsonNode flags = result.get("flags");
                if (flags != null && flags.isArray()) {
                    for (JsonNode flag : flags) {
                        String key = flag.has("key") ? flag.get("key").asText() : null;
                        JsonNode value = flag.get("value");
                        String reason = flag.has("reason") ? flag.get("reason").asText() : null;
                        String variant = flag.has("variant") ? flag.get("variant").asText() : null;
                        String flagType = extractFlagTypeFromMetadata(flag);

                        if (key != null) {
                            results.add(new FlagEvaluation(key, value, reason, variant, flagType));
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

            ObjectNode bodyNode = objectMapper.createObjectNode();
            ObjectNode contextNode = transformContext(context);
            bodyNode.set("context", contextNode);

            RequestBody body = RequestBody.create(objectMapper.writeValueAsString(bodyNode), JSON);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                JsonNode result = objectMapper.readTree(responseBody);

                String key = result.has("key") ? result.get("key").asText() : flagKey;
                JsonNode value = result.get("value");
                String reason = result.has("reason") ? result.get("reason").asText() : null;
                String variant = result.has("variant") ? result.get("variant").asText() : null;
                String flagType = extractFlagTypeFromMetadata(result);

                return new FlagEvaluation(key, value, reason, variant, flagType);
            }
        } catch (Exception e) {
            log.error("Error evaluating flag '{}': {}", flagKey, e.getMessage());
            return null;
        }
    }

    /**
     * Extract flagType from metadata if available.
     */
    private String extractFlagTypeFromMetadata(JsonNode flagNode) {
        if (flagNode.has("metadata")) {
            JsonNode metadata = flagNode.get("metadata");
            if (metadata.has("flagType")) {
                return metadata.get("flagType").asText();
            }
        }
        return null;
    }

    /**
     * Transform OpenFeature context to OFREP context format.
     */
    private ObjectNode transformContext(EvaluationContext context) {
        ObjectNode node = objectMapper.createObjectNode();

        if (context.getTargetingKey() != null) {
            node.put("targetingKey", context.getTargetingKey());
        }

        for (Map.Entry<String, Value> entry : context.asMap().entrySet()) {
            String key = entry.getKey();
            if (!"targetingKey".equals(key)) {
                addValueToNode(node, key, entry.getValue());
            }
        }

        return node;
    }

    /**
     * Add an OpenFeature Value to a JSON ObjectNode.
     */
    private void addValueToNode(ObjectNode node, String key, Value value) {
        if (value.isBoolean()) {
            node.put(key, value.asBoolean());
        } else if (value.isString()) {
            node.put(key, value.asString());
        } else if (value.isNumber()) {
            if (value.asDouble() == Math.floor(value.asDouble())) {
                node.put(key, value.asInteger());
            } else {
                node.put(key, value.asDouble());
            }
        } else if (value.isList()) {
            var arrayNode = objectMapper.createArrayNode();
            for (Value item : value.asList()) {
                arrayNode.add(valueToJsonNode(item));
            }
            node.set(key, arrayNode);
        } else if (value.isStructure()) {
            ObjectNode structNode = objectMapper.createObjectNode();
            for (Map.Entry<String, Value> entry : value.asStructure().asMap().entrySet()) {
                addValueToNode(structNode, entry.getKey(), entry.getValue());
            }
            node.set(key, structNode);
        }
    }

    private JsonNode valueToJsonNode(Value value) {
        if (value.isBoolean()) {
            return objectMapper.getNodeFactory().booleanNode(value.asBoolean());
        } else if (value.isString()) {
            return objectMapper.getNodeFactory().textNode(value.asString());
        } else if (value.isNumber()) {
            if (value.asDouble() == Math.floor(value.asDouble())) {
                return objectMapper.getNodeFactory().numberNode(value.asInteger());
            } else {
                return objectMapper.getNodeFactory().numberNode(value.asDouble());
            }
        }
        return objectMapper.getNodeFactory().nullNode();
    }

    /**
     * Builder for FlipswitchProvider.
     */
    public static class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private boolean enableRealtime = true;
        private OkHttpClient httpClient;

        private Builder(String apiKey) {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            this.apiKey = apiKey;
        }

        /**
         * Set the Flipswitch server base URL.
         * Defaults to https://api.flipswitch.dev
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
         * Build the provider.
         */
        public FlipswitchProvider build() {
            return new FlipswitchProvider(this);
        }
    }
}
