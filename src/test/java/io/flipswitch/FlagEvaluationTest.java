package io.flipswitch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlagEvaluationTest {

    // ========================================
    // Metadata type resolution tests
    // ========================================

    @Test
    void metadataType_string() {
        FlagEvaluation eval = new FlagEvaluation("k", "v", null, null, "string");
        assertEquals("string", eval.getValueType());
    }

    @Test
    void metadataType_integer() {
        FlagEvaluation eval = new FlagEvaluation("k", 1, null, null, "integer");
        assertEquals("integer", eval.getValueType());
    }

    @Test
    void metadataType_unknownFallsBackToInference() {
        FlagEvaluation eval = new FlagEvaluation("k", true, null, null, "custom");
        assertEquals("boolean", eval.getValueType());
    }

    @Test
    void metadataType_emptyStringFallsBackToInference() {
        FlagEvaluation eval = new FlagEvaluation("k", 42, null, null, "");
        assertEquals("integer", eval.getValueType());
    }

    // ========================================
    // getValue tests
    // ========================================

    @Test
    void getValue_returnsRawValue() {
        Object raw = List.of(1, 2, 3);
        FlagEvaluation eval = new FlagEvaluation("k", raw, null, null);
        assertSame(raw, eval.getValue());
    }

    // ========================================
    // getValueAsString tests
    // ========================================

    @Test
    void getValueAsString_null() {
        FlagEvaluation eval = new FlagEvaluation("k", null, null, null);
        assertEquals("null", eval.getValueAsString());
    }

    @Test
    void getValueAsString_string() {
        FlagEvaluation eval = new FlagEvaluation("k", "hello", null, null);
        assertEquals("\"hello\"", eval.getValueAsString());
    }

    @Test
    void getValueAsString_number() {
        FlagEvaluation eval = new FlagEvaluation("k", 42, null, null);
        assertEquals("42", eval.getValueAsString());
    }

    @Test
    void getValueAsString_boolean() {
        FlagEvaluation eval = new FlagEvaluation("k", true, null, null);
        assertEquals("true", eval.getValueAsString());
    }

    // ========================================
    // asDouble tests
    // ========================================

    @Test
    void asDouble_withNumber() {
        FlagEvaluation eval = new FlagEvaluation("k", 3.14, null, null);
        assertEquals(3.14, eval.asDouble(), 0.001);
    }

    @Test
    void asDouble_withNonNumber() {
        FlagEvaluation eval = new FlagEvaluation("k", "not a number", null, null);
        assertEquals(0.0, eval.asDouble(), 0.001);
    }

    // ========================================
    // asString tests
    // ========================================

    @Test
    void asString_withNull() {
        FlagEvaluation eval = new FlagEvaluation("k", null, null, null);
        assertNull(eval.asString());
    }

    @Test
    void asString_withString() {
        FlagEvaluation eval = new FlagEvaluation("k", "hello", null, null);
        assertEquals("hello", eval.asString());
    }

    @Test
    void asString_withNonString() {
        FlagEvaluation eval = new FlagEvaluation("k", 42, null, null);
        assertEquals("42", eval.asString());
    }

    // ========================================
    // toString tests
    // ========================================

    @Test
    void toString_withVariant() {
        FlagEvaluation eval = new FlagEvaluation("my-flag", "on", "TARGETING_MATCH", "v1");
        String str = eval.toString();
        assertTrue(str.contains("my-flag"));
        assertTrue(str.contains("string"));
        assertTrue(str.contains("TARGETING_MATCH"));
        assertTrue(str.contains("v1"));
    }

    @Test
    void toString_withoutVariant() {
        FlagEvaluation eval = new FlagEvaluation("my-flag", true, "DEFAULT", null);
        String str = eval.toString();
        assertTrue(str.contains("my-flag"));
        assertTrue(str.contains("boolean"));
        assertTrue(str.contains("DEFAULT"));
        assertFalse(str.contains("variant="));
    }
}
