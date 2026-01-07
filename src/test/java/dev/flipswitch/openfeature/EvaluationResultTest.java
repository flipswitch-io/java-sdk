package dev.flipswitch.openfeature;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationResultTest {

    @Test
    void getBooleanValue_withBoolean_returnsValue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", true, "TARGETING_MATCH", "enabled", null, null, null);

        assertEquals(true, result.getBooleanValue());
    }

    @Test
    void getBooleanValue_withNonBoolean_returnsNull() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", "true", "TARGETING_MATCH", "enabled", null, null, null);

        assertNull(result.getBooleanValue());
    }

    @Test
    void getStringValue_withString_returnsValue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", "hello", "TARGETING_MATCH", "variant-a", null, null, null);

        assertEquals("hello", result.getStringValue());
    }

    @Test
    void getStringValue_withNonString_returnsNull() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", 42, "TARGETING_MATCH", "variant-a", null, null, null);

        assertNull(result.getStringValue());
    }

    @Test
    void getIntegerValue_withInteger_returnsValue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", 42, "TARGETING_MATCH", "variant-a", null, null, null);

        assertEquals(42, result.getIntegerValue());
    }

    @Test
    void getIntegerValue_withDouble_returnsIntValue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", 42.0, "TARGETING_MATCH", "variant-a", null, null, null);

        assertEquals(42, result.getIntegerValue());
    }

    @Test
    void getIntegerValue_withNonNumber_returnsNull() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", "42", "TARGETING_MATCH", "variant-a", null, null, null);

        assertNull(result.getIntegerValue());
    }

    @Test
    void getDoubleValue_withDouble_returnsValue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", 3.14, "TARGETING_MATCH", "variant-a", null, null, null);

        assertEquals(3.14, result.getDoubleValue());
    }

    @Test
    void getDoubleValue_withInteger_returnsDoubleValue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", 42, "TARGETING_MATCH", "variant-a", null, null, null);

        assertEquals(42.0, result.getDoubleValue());
    }

    @Test
    void getStructureValue_withMap_returnsMap() {
        Map<String, Object> map = Map.of("key", "value", "count", 42);
        EvaluationResult result = new EvaluationResult(
                "test-flag", map, "TARGETING_MATCH", "variant-a", null, null, null);

        assertEquals(map, result.getStructureValue());
    }

    @Test
    void getStructureValue_withNonMap_returnsNull() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", "not a map", "TARGETING_MATCH", "variant-a", null, null, null);

        assertNull(result.getStructureValue());
    }

    @Test
    void isError_withErrorCode_returnsTrue() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", null, null, null, null, "FLAG_NOT_FOUND", "Flag not found");

        assertTrue(result.isError());
    }

    @Test
    void isError_withoutErrorCode_returnsFalse() {
        EvaluationResult result = new EvaluationResult(
                "test-flag", true, "TARGETING_MATCH", "enabled", null, null, null);

        assertFalse(result.isError());
    }

    @Test
    void getters_returnConstructorValues() {
        Map<String, Object> metadata = Map.of("source", "database");
        EvaluationResult result = new EvaluationResult(
                "my-flag", true, "TARGETING_MATCH", "enabled", metadata, null, null);

        assertEquals("my-flag", result.getKey());
        assertEquals(true, result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
        assertEquals("enabled", result.getVariant());
        assertEquals(metadata, result.getMetadata());
        assertNull(result.getErrorCode());
        assertNull(result.getErrorDetails());
    }
}
