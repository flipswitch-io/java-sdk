package dev.flipswitch.openfeature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagTest {

    @Test
    void getEffectiveValue_withStringValue_returnsStringValue() {
        Flag flag = new Flag("test-flag", "Boolean", "false", "true");

        assertEquals("true", flag.getEffectiveValue());
    }

    @Test
    void getEffectiveValue_withNullStringValue_returnsDefaultValue() {
        Flag flag = new Flag("test-flag", "Boolean", "false", null);

        assertEquals("false", flag.getEffectiveValue());
    }

    @Test
    void getters_returnConstructorValues() {
        Flag flag = new Flag("my-flag", "String", "default", "evaluated");

        assertEquals("my-flag", flag.getKey());
        assertEquals("String", flag.getFlagType());
        assertEquals("default", flag.getDefaultStringValue());
        assertEquals("evaluated", flag.getStringValue());
    }
}
