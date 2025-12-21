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

    @JsonCreator
    public Flag(
            @JsonProperty("key") String key,
            @JsonProperty("flagType") String flagType,
            @JsonProperty("defaultStringValue") String defaultStringValue,
            @JsonProperty("stringValue") String stringValue) {
        this.key = key;
        this.flagType = flagType;
        this.defaultStringValue = defaultStringValue;
        this.stringValue = stringValue;
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

    /**
     * Returns the effective value (evaluated value if available, otherwise default).
     */
    public String getEffectiveValue() {
        return stringValue != null ? stringValue : defaultStringValue;
    }
}
