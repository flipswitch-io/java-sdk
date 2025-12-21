package dev.flipswitch.openfeature;

import dev.openfeature.sdk.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenFeature provider for Flipswitch feature flag service.
 */
public class FlipswitchProvider implements FeatureProvider {

    private final FlipswitchClient client;
    private final Map<String, Flag> flagCache = new ConcurrentHashMap<>();

    /**
     * Creates a new FlipswitchProvider.
     *
     * @param config The configuration for connecting to Flipswitch
     */
    public FlipswitchProvider(FlipswitchConfig config) {
        this.client = new FlipswitchClient(config);
    }

    /**
     * Creates a new FlipswitchProvider with a custom client (for testing).
     */
    FlipswitchProvider(FlipswitchClient client) {
        this.client = client;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "flipswitch";
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        refreshFlags(evaluationContext);
    }

    @Override
    public void shutdown() {
        flagCache.clear();
    }

    /**
     * Refreshes the flag cache by fetching flags from the server.
     */
    public void refreshFlags(EvaluationContext context) throws Exception {
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
                .reason(flag.getStringValue() != null ? Reason.TARGETING_MATCH.toString() : Reason.DEFAULT.toString())
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
                .reason(flag.getStringValue() != null ? Reason.TARGETING_MATCH.toString() : Reason.DEFAULT.toString())
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
                    .reason(flag.getStringValue() != null ? Reason.TARGETING_MATCH.toString() : Reason.DEFAULT.toString())
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
                    .reason(flag.getStringValue() != null ? Reason.TARGETING_MATCH.toString() : Reason.DEFAULT.toString())
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
                .reason(flag.getStringValue() != null ? Reason.TARGETING_MATCH.toString() : Reason.DEFAULT.toString())
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
