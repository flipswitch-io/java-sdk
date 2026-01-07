package dev.flipswitch.openfeature;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * OFREP evaluation context for flag evaluation requests.
 * Matches the OpenFeature Remote Evaluation Protocol specification.
 */
public class EvaluationContext {

    @JsonProperty("targetingKey")
    private final String targetingKey;

    @JsonProperty("properties")
    private final Map<String, Object> properties;

    public EvaluationContext(String targetingKey, Map<String, Object> properties) {
        this.targetingKey = targetingKey;
        this.properties = properties;
    }

    public String getTargetingKey() {
        return targetingKey;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
