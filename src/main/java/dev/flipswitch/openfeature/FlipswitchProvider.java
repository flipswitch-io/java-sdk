package dev.flipswitch.openfeature;

import dev.openfeature.sdk.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final Map<String, Flag> flagCache = new ConcurrentHashMap<>();
    private volatile EvaluationContext lastContext;

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
    public void initialize(EvaluationContext evaluationContext) throws Exception {
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
        Identity identity = contextToIdentity(lastContext);
        Flag flag = client.fetchFlag(flagKey, identity);
        if (flag != null) {
            flagCache.put(flag.getKey(), flag);
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
    public void refreshFlags(EvaluationContext context) throws Exception {
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
    private void refreshFlagsInternal(EvaluationContext context) throws Exception {
        this.lastContext = context;
        var flags = client.fetchFlags(contextToIdentity(context));
        flagCache.clear();
        for (Flag flag : flags) {
            flagCache.put(flag.getKey(), flag);
        }
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String key, Boolean defaultValue, EvaluationContext ctx) {
        Flag flag = flagCache.get(key);
        if (flag == null) {
            return ProviderEvaluation.<Boolean>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        String value = flag.getEffectiveValue();
        boolean result = Boolean.parseBoolean(value);

        return ProviderEvaluation.<Boolean>builder()
                .value(result)
                .variant(flag.getVariant())
                .reason(flag.getReason())
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String key, String defaultValue, EvaluationContext ctx) {
        Flag flag = flagCache.get(key);
        if (flag == null) {
            return ProviderEvaluation.<String>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        return ProviderEvaluation.<String>builder()
                .value(flag.getEffectiveValue())
                .variant(flag.getVariant())
                .reason(flag.getReason())
                .build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String key, Integer defaultValue, EvaluationContext ctx) {
        Flag flag = flagCache.get(key);
        if (flag == null) {
            return ProviderEvaluation.<Integer>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        try {
            int result = Integer.parseInt(flag.getEffectiveValue());
            return ProviderEvaluation.<Integer>builder()
                    .value(result)
                    .variant(flag.getVariant())
                    .reason(flag.getReason())
                    .build();
        } catch (NumberFormatException e) {
            return ProviderEvaluation.<Integer>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.PARSE_ERROR)
                    .errorMessage("Failed to parse integer: " + flag.getEffectiveValue())
                    .build();
        }
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String key, Double defaultValue, EvaluationContext ctx) {
        Flag flag = flagCache.get(key);
        if (flag == null) {
            return ProviderEvaluation.<Double>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        try {
            double result = Double.parseDouble(flag.getEffectiveValue());
            return ProviderEvaluation.<Double>builder()
                    .value(result)
                    .variant(flag.getVariant())
                    .reason(flag.getReason())
                    .build();
        } catch (NumberFormatException e) {
            return ProviderEvaluation.<Double>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.PARSE_ERROR)
                    .errorMessage("Failed to parse double: " + flag.getEffectiveValue())
                    .build();
        }
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String key, Value defaultValue, EvaluationContext ctx) {
        Flag flag = flagCache.get(key);
        if (flag == null) {
            return ProviderEvaluation.<Value>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }

        // For object evaluation, return the string value wrapped in a Value
        return ProviderEvaluation.<Value>builder()
                .value(new Value(flag.getEffectiveValue()))
                .variant(flag.getVariant())
                .reason(flag.getReason())
                .build();
    }

    private Identity contextToIdentity(EvaluationContext ctx) {
        if (ctx == null) {
            return new Identity("anonymous", Map.of());
        }

        String targetingKey = ctx.getTargetingKey();
        if (targetingKey == null || targetingKey.isEmpty()) {
            targetingKey = "anonymous";
        }

        Map<String, String> traits = new java.util.HashMap<>();
        for (String key : ctx.keySet()) {
            Value value = ctx.getValue(key);
            if (value != null && value.asString() != null) {
                traits.put(key, value.asString());
            }
        }

        return new Identity(targetingKey, traits);
    }
}
