package dev.flipswitch.openfeature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagTest {

    @Test
    void getEffectiveValue_withStringValue_returnsStringValue() {
        Flag flag = new Flag("test-flag", "Boolean", "false", "true", "rule-0", "TARGETING_MATCH");

        assertEquals("true", flag.getEffectiveValue());
    }

    @Test
    void getEffectiveValue_withNullStringValue_returnsDefaultValue() {
        Flag flag = new Flag("test-flag", "Boolean", "false", null, "default", "DEFAULT");

        assertEquals("false", flag.getEffectiveValue());
    }

    @Test
    void getters_returnConstructorValues() {
        Flag flag = new Flag("my-flag", "String", "default", "evaluated", "rule-0", "TARGETING_MATCH");

        assertEquals("my-flag", flag.getKey());
        assertEquals("String", flag.getFlagType());
        assertEquals("default", flag.getDefaultStringValue());
        assertEquals("evaluated", flag.getStringValue());
        assertEquals("rule-0", flag.getVariant());
        assertEquals("TARGETING_MATCH", flag.getReason());
    }
}
