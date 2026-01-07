package dev.flipswitch.openfeature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * OFREP evaluation result for a single flag.
 * Matches the OpenFeature Remote Evaluation Protocol specification.
 */
public class EvaluationResult {

    private final String key;
    private final Object value;
    private final String reason;
    private final String variant;
    private final Map<String, Object> metadata;

    // Error fields (present when evaluation fails)
    private final String errorCode;
    private final String errorDetails;

    @JsonCreator
    public EvaluationResult(
            @JsonProperty("key") String key,
            @JsonProperty("value") Object value,
            @JsonProperty("reason") String reason,
            @JsonProperty("variant") String variant,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("errorDetails") String errorDetails) {
        this.key = key;
        this.value = value;
        this.reason = reason;
        this.variant = variant;
        this.metadata = metadata;
        this.errorCode = errorCode;
        this.errorDetails = errorDetails;
    }

    public String getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }

    public String getReason() {
        return reason;
    }

    public String getVariant() {
        return variant;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    /**
     * Returns true if this result represents an error.
     */
    public boolean isError() {
        return errorCode != null;
    }

    /**
     * Returns the value as a Boolean, or null if not a boolean.
     */
    public Boolean getBooleanValue() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    /**
     * Returns the value as a String, or null if not a string.
     */
    public String getStringValue() {
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    /**
     * Returns the value as an Integer, or null if not an integer.
     */
    public Integer getIntegerValue() {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * Returns the value as a Double, or null if not a number.
     */
    public Double getDoubleValue() {
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Returns the value as a Map (structure), or null if not a map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStructureValue() {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }
}
