package dev.flipswitch;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents the result of evaluating a single flag.
 */
public class FlagEvaluation {

    private final String key;
    private final JsonNode value;
    private final String valueType;
    private final String reason;
    private final String variant;

    public FlagEvaluation(String key, JsonNode value, String reason, String variant) {
        this(key, value, reason, variant, null);
    }

    public FlagEvaluation(String key, JsonNode value, String reason, String variant, String metadataFlagType) {
        this.key = key;
        this.value = value;
        this.valueType = getTypeFromMetadataOrInfer(metadataFlagType, value);
        this.reason = reason;
        this.variant = variant;
    }

    private static String getTypeFromMetadataOrInfer(String metadataFlagType, JsonNode value) {
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

    private static String inferType(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        } else if (node.isBoolean()) {
            return "boolean";
        } else if (node.isInt() || node.isLong()) {
            return "integer";
        } else if (node.isDouble() || node.isFloat() || node.isNumber()) {
            return "number";
        } else if (node.isTextual()) {
            return "string";
        } else if (node.isArray()) {
            return "array";
        } else if (node.isObject()) {
            return "object";
        }
        return "unknown";
    }

    public String getKey() {
        return key;
    }

    public JsonNode getValue() {
        return value;
    }

    /**
     * Get the value as a displayable string.
     */
    public String getValueAsString() {
        if (value == null || value.isNull()) {
            return "null";
        } else if (value.isTextual()) {
            return "\"" + value.asText() + "\"";
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
        return value != null && value.asBoolean();
    }

    public int asInt() {
        return value != null ? value.asInt() : 0;
    }

    public double asDouble() {
        return value != null ? value.asDouble() : 0.0;
    }

    public String asString() {
        return value != null ? value.asText() : null;
    }

    @Override
    public String toString() {
        return key + " (" + valueType + "): " + getValueAsString() +
               " [reason=" + reason + (variant != null ? ", variant=" + variant : "") + "]";
    }
}
