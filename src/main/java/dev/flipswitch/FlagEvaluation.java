package dev.flipswitch;

import java.util.List;
import java.util.Map;

/**
 * Represents the result of evaluating a single flag.
 */
public class FlagEvaluation {

    private final String key;
    private final Object value;
    private final String valueType;
    private final String reason;
    private final String variant;

    public FlagEvaluation(String key, Object value, String reason, String variant) {
        this(key, value, reason, variant, null);
    }

    public FlagEvaluation(String key, Object value, String reason, String variant, String metadataFlagType) {
        this.key = key;
        this.value = value;
        this.valueType = getTypeFromMetadataOrInfer(metadataFlagType, value);
        this.reason = reason;
        this.variant = variant;
    }

    private static String getTypeFromMetadataOrInfer(String metadataFlagType, Object value) {
        // Prefer metadata.flagType if available (especially useful for disabled flags)
        if (metadataFlagType != null && !metadataFlagType.isEmpty()) {
            // Map backend types to SDK types
            switch (metadataFlagType) {
                case "boolean": return "boolean";
                case "string": return "string";
                case "integer": return "integer";
                case "decimal": return "number";
                default: break;
            }
        }
        // Fall back to inferring from value
        return inferType(value);
    }

    private static String inferType(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof Boolean) {
            return "boolean";
        } else if (value instanceof Integer || value instanceof Long) {
            return "integer";
        } else if (value instanceof Double || value instanceof Float || value instanceof Number) {
            return "number";
        } else if (value instanceof String) {
            return "string";
        } else if (value instanceof List) {
            return "array";
        } else if (value instanceof Map) {
            return "object";
        }
        return "unknown";
    }

    public String getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }

    /**
     * Get the value as a displayable string.
     */
    public String getValueAsString() {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + value + "\"";
        } else {
            return value.toString();
        }
    }

    /**
     * Get the inferred type of the value (boolean, integer, number, string, array, object).
     */
    public String getValueType() {
        return valueType;
    }

    public String getReason() {
        return reason;
    }

    public String getVariant() {
        return variant;
    }

    // Convenience methods for typed access

    public boolean asBoolean() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return false;
    }

    public int asInt() {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    public double asDouble() {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    public String asString() {
        if (value instanceof String) {
            return (String) value;
        }
        return value != null ? value.toString() : null;
    }

    @Override
    public String toString() {
        return key + " (" + valueType + "): " + getValueAsString() +
               " [reason=" + reason + (variant != null ? ", variant=" + variant : "") + "]";
    }
}
