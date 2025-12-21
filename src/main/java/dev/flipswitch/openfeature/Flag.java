package dev.flipswitch.openfeature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a feature flag.
 */
public class Flag {

    private final String key;
    private final String flagType;
    private final String defaultStringValue;
    private final String stringValue;
    private final String variant;
    private final String reason;

    @JsonCreator
    public Flag(
            @JsonProperty("key") String key,
            @JsonProperty("flagType") String flagType,
            @JsonProperty("defaultStringValue") String defaultStringValue,
            @JsonProperty("stringValue") String stringValue,
            @JsonProperty("variant") String variant,
            @JsonProperty("reason") String reason) {
        this.key = key;
        this.flagType = flagType;
        this.defaultStringValue = defaultStringValue;
        this.stringValue = stringValue;
        this.variant = variant;
        this.reason = reason;
    }

    public String getKey() {
        return key;
    }

    public String getFlagType() {
        return flagType;
    }

    public String getDefaultStringValue() {
        return defaultStringValue;
    }

    public String getStringValue() {
        return stringValue;
    }

    public String getVariant() {
        return variant;
    }

    public String getReason() {
        return reason;
    }

    /**
     * Returns the effective value (evaluated value if available, otherwise default).
     */
    public String getEffectiveValue() {
        return stringValue != null ? stringValue : defaultStringValue;
    }
}
