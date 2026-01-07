package dev.flipswitch.openfeature;

import dev.openfeature.sdk.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenFeature provider for Flipswitch feature flag service.
 * <p>
 * Extends EventProvider to support OpenFeature events:
 * <ul>
 *   <li>PROVIDER_READY - emitted after successful initialization</li>
 *   <li>PROVIDER_ERROR - emitted on initialization or refresh failure</li>
 *   <li>PROVIDER_STALE - emitted when cache may be outdated</li>
 *   <li>PROVIDER_CONFIGURATION_CHANGED - emitted when flags are refreshed</li>
 * </ul>
 * <p>
 * When streaming is enabled, the provider connects to the server's SSE endpoint
 * and automatically refreshes flags when changes are detected.
 */
public class FlipswitchProvider extends EventProvider {

    private final FlipswitchClient client;
    private final FlipswitchSseClient sseClient;
    private final Map<String, EvaluationResult> flagCache = new ConcurrentHashMap<>();
    private volatile dev.openfeature.sdk.EvaluationContext lastContext;

    /**
     * Creates a new FlipswitchProvider.
     *
     * @param config The configuration for connecting to Flipswitch
     */
    public FlipswitchProvider(FlipswitchConfig config) {
        this.client = new FlipswitchClient(config);
        if (config.isStreamingEnabled()) {
            this.sseClient = new FlipswitchSseClient(
                    config,
                    this::onFlagChange,
                    this::onStale
            );
        } else {
            this.sseClient = null;
        }
    }

    /**
     * Creates a new FlipswitchProvider with a custom client (for testing).
     */
    FlipswitchProvider(FlipswitchClient client) {
        this.client = client;
        this.sseClient = null;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "flipswitch";
    }

    @Override
    public void initialize(dev.openfeature.sdk.EvaluationContext evaluationContext) throws Exception {
        try {
            refreshFlagsInternal(evaluationContext);
            if (sseClient != null) {
                sseClient.connect();
            }
            emitProviderReady(ProviderEventDetails.builder().build());
        } catch (Exception e) {
            emitProviderError(ProviderEventDetails.builder()
                    .message("Initialization failed: " + e.getMessage())
                    .build());
            throw e;
        }
    }

    @Override
    public void shutdown() {
        if (sseClient != null) {
            sseClient.close();
        }
        flagCache.clear();
    }

    /**
     * Callback invoked when an SSE flag-change event is received.
     * If a specific flagKey is provided, only that flag is refreshed.
     * If flagKey is null (e.g., segment change), all flags are refreshed.
     */
    private void onFlagChange(FlagChangeEvent event) {
        try {
            if (event.getFlagKey() != null) {
                // Targeted refresh - only fetch the changed flag
                refreshSingleFlag(event.getFlagKey());
            } else {
                // Segment change or bulk update - refresh all flags
                refreshFlagsInternal(lastContext);
            }
            emitProviderConfigurationChanged(ProviderEventDetails.builder().build());
        } catch (Exception e) {
            emitProviderError(ProviderEventDetails.builder()
                    .message("Failed to refresh flags after SSE event: " + e.getMessage())
                    .build());
        }
    }

    /**
     * Refreshes a single flag in the cache.
     */
    private void refreshSingleFlag(String flagKey) throws Exception {
        EvaluationContext context = contextToEvaluationContext(lastContext);
        EvaluationResult result = client.fetchFlag(flagKey, context);
        if (result != null) {
            flagCache.put(result.getKey(), result);
        } else {
            // Flag was deleted or doesn't exist anymore
            flagCache.remove(flagKey);
        }
    }

    /**
     * Callback invoked when the SSE connection becomes stale (no heartbeat).
     */
    private void onStale() {
        emitProviderStale(ProviderEventDetails.builder()
                .message("SSE connection stale - no heartbeat received")
                .build());
    }

    /**
     * Refreshes the flag cache by fetching flags from the server.
     * Emits PROVIDER_CONFIGURATION_CHANGED on success.
     *
     * @param context The evaluation context to use for fetching flags
     * @throws Exception if the refresh fails
     */
    public void refreshFlags(dev.openfeature.sdk.EvaluationContext context) throws Exception {
        refreshFlagsInternal(context);
        emitProviderConfigurationChanged(ProviderEventDetails.builder().build());
    }

    /**
     * Marks the provider cache as potentially stale.
     * Call this when you suspect the cache may be outdated.
     */
    public void markStale() {
        emitProviderStale(ProviderEventDetails.builder()
                .message("Cache marked as stale")
                .build());
    }

    /**
     * Internal method to refresh flags without emitting events.
     * Used by initialize() and onContextChanged() which emit their own events.
     */
    private void refreshFlagsInternal(dev.openfeature.sdk.EvaluationContext context) throws Exception {
        this.lastContext = context;
        FlipswitchClient.BulkFetchResult result = client.fetchFlags(contextToEvaluationContext(context));

        // Only clear and update cache if we got new data (not a 304 response)
        if (!result.isNotModified()) {
            flagCache.clear();
            for (EvaluationResult flag : result.getResults()) {
                flagCache.put(flag.getKey(), flag);
            }
        }
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String key, Boolean defaultValue, dev.openfeature.sdk.EvaluationContext ctx) {
        EvaluationResult result = flagCache.get(key);
        if (result == null || result.isError()) {
            return ProviderEvaluation.<Boolean>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        Boolean value = result.getBooleanValue();
        if (value == null) {
            return ProviderEvaluation.<Boolean>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("Flag value is not a boolean")
                    .build();
        }

        return ProviderEvaluation.<Boolean>builder()
                .value(value)
                .variant(result.getVariant())
                .reason(result.getReason())
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String key, String defaultValue, dev.openfeature.sdk.EvaluationContext ctx) {
        EvaluationResult result = flagCache.get(key);
        if (result == null || result.isError()) {
            return ProviderEvaluation.<String>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        String value = result.getStringValue();
        if (value == null) {
            // Try to convert to string
            Object rawValue = result.getValue();
            if (rawValue != null) {
                value = rawValue.toString();
            } else {
                return ProviderEvaluation.<String>builder()
                        .value(defaultValue)
                        .reason(Reason.ERROR.toString())
                        .errorCode(ErrorCode.TYPE_MISMATCH)
                        .errorMessage("Flag value is null")
                        .build();
            }
        }

        return ProviderEvaluation.<String>builder()
                .value(value)
                .variant(result.getVariant())
                .reason(result.getReason())
                .build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String key, Integer defaultValue, dev.openfeature.sdk.EvaluationContext ctx) {
        EvaluationResult result = flagCache.get(key);
        if (result == null || result.isError()) {
            return ProviderEvaluation.<Integer>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        Integer value = result.getIntegerValue();
        if (value == null) {
            return ProviderEvaluation.<Integer>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("Flag value is not an integer")
                    .build();
        }

        return ProviderEvaluation.<Integer>builder()
                .value(value)
                .variant(result.getVariant())
                .reason(result.getReason())
                .build();
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String key, Double defaultValue, dev.openfeature.sdk.EvaluationContext ctx) {
        EvaluationResult result = flagCache.get(key);
        if (result == null || result.isError()) {
            return ProviderEvaluation.<Double>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        Double value = result.getDoubleValue();
        if (value == null) {
            return ProviderEvaluation.<Double>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("Flag value is not a number")
                    .build();
        }

        return ProviderEvaluation.<Double>builder()
                .value(value)
                .variant(result.getVariant())
                .reason(result.getReason())
                .build();
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String key, Value defaultValue, dev.openfeature.sdk.EvaluationContext ctx) {
        EvaluationResult result = flagCache.get(key);
        if (result == null || result.isError()) {
            return ProviderEvaluation.<Value>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        // Convert the value to an OpenFeature Value
        Value value = convertToValue(result.getValue());
        if (value == null) {
            return ProviderEvaluation.<Value>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("Unable to convert flag value to object")
                    .build();
        }

        return ProviderEvaluation.<Value>builder()
                .value(value)
                .variant(result.getVariant())
                .reason(result.getReason())
                .build();
    }

    /**
     * Converts an OFREP value to an OpenFeature Value.
     */
    @SuppressWarnings("unchecked")
    private Value convertToValue(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Boolean) {
            return new Value((Boolean) obj);
        }
        if (obj instanceof String) {
            return new Value((String) obj);
        }
        if (obj instanceof Integer) {
            return new Value((Integer) obj);
        }
        if (obj instanceof Number) {
            return new Value(((Number) obj).doubleValue());
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            MutableStructure structure = new MutableStructure();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Value entryValue = convertToValue(entry.getValue());
                if (entryValue != null) {
                    structure.add(entry.getKey(), entryValue);
                }
            }
            return new Value(structure);
        }
        // Fallback: convert to string
        return new Value(obj.toString());
    }

    /**
     * Converts an OpenFeature EvaluationContext to an OFREP EvaluationContext.
     */
    private EvaluationContext contextToEvaluationContext(dev.openfeature.sdk.EvaluationContext ctx) {
        if (ctx == null) {
            return new EvaluationContext("anonymous", Map.of());
        }

        String targetingKey = ctx.getTargetingKey();
        if (targetingKey == null || targetingKey.isEmpty()) {
            targetingKey = "anonymous";
        }

        Map<String, Object> properties = new java.util.HashMap<>();
        for (String key : ctx.keySet()) {
            Value value = ctx.getValue(key);
            if (value != null) {
                properties.put(key, convertValueToObject(value));
            }
        }

        return new EvaluationContext(targetingKey, properties);
    }

    /**
     * Converts an OpenFeature Value to a plain Java object.
     */
    private Object convertValueToObject(Value value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            double d = value.asDouble();
            if (d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return (int) d;
            }
            return d;
        }
        if (value.isStructure()) {
            Map<String, Object> map = new java.util.HashMap<>();
            Structure structure = value.asStructure();
            for (String key : structure.keySet()) {
                map.put(key, convertValueToObject(structure.getValue(key)));
            }
            return map;
        }
        // Fallback
        return value.asString();
    }
}
