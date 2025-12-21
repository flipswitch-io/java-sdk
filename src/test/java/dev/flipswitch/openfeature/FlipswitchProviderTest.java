package dev.flipswitch.openfeature;

import dev.openfeature.sdk.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FlipswitchProviderTest {

    private FlipswitchClient mockClient;
    private FlipswitchProvider provider;

    @BeforeEach
    void setUp() {
        mockClient = mock(FlipswitchClient.class);
        provider = new FlipswitchProvider(mockClient);
    }

    @Test
    void getMetadata_returnsFlipswitch() {
        assertEquals("flipswitch", provider.getMetadata().getName());
    }

    @Test
    void getBooleanEvaluation_withMatchingFlag_returnsEvaluatedValue() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("feature-enabled", "Boolean", "false", "true", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "feature-enabled", false, null);

        assertTrue(result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
        assertEquals("rule-0", result.getVariant());
    }

    @Test
    void getBooleanEvaluation_withDefaultValue_returnsDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("feature-disabled", "Boolean", "false", null, "default", "DEFAULT")
        ));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "feature-disabled", true, null);

        assertFalse(result.getValue()); // Uses flag's default, not the passed default
        assertEquals("DEFAULT", result.getReason());
        assertEquals("default", result.getVariant());
    }

    @Test
    void getBooleanEvaluation_withUnknownFlag_returnsPassedDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of());
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "unknown-flag", true, null);

        assertTrue(result.getValue());
        assertEquals(Reason.DEFAULT.toString(), result.getReason());
    }

    @Test
    void getStringEvaluation_withMatchingFlag_returnsEvaluatedValue() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("variant", "String", "control", "treatment-a", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        ProviderEvaluation<String> result = provider.getStringEvaluation(
                "variant", "control", null);

        assertEquals("treatment-a", result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getStringEvaluation_withDefaultValue_returnsDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("variant", "String", "control", null, "default", "DEFAULT")
        ));
        provider.initialize(null);

        ProviderEvaluation<String> result = provider.getStringEvaluation(
                "variant", "fallback", null);

        assertEquals("control", result.getValue());
        assertEquals("DEFAULT", result.getReason());
    }

    @Test
    void getIntegerEvaluation_withValidNumber_returnsInteger() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("max-items", "Integer", "10", "50", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        ProviderEvaluation<Integer> result = provider.getIntegerEvaluation(
                "max-items", 10, null);

        assertEquals(50, result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getIntegerEvaluation_withInvalidNumber_returnsErrorAndDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("max-items", "Integer", "not-a-number", null, "default", "DEFAULT")
        ));
        provider.initialize(null);

        ProviderEvaluation<Integer> result = provider.getIntegerEvaluation(
                "max-items", 99, null);

        assertEquals(99, result.getValue());
        assertEquals(Reason.ERROR.toString(), result.getReason());
        assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
    }

    @Test
    void getDoubleEvaluation_withValidNumber_returnsDouble() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("rate-limit", "Decimal", "1.0", "2.5", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        ProviderEvaluation<Double> result = provider.getDoubleEvaluation(
                "rate-limit", 1.0, null);

        assertEquals(2.5, result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getDoubleEvaluation_withInvalidNumber_returnsErrorAndDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("rate-limit", "Decimal", "invalid", null, "default", "DEFAULT")
        ));
        provider.initialize(null);

        ProviderEvaluation<Double> result = provider.getDoubleEvaluation(
                "rate-limit", 3.14, null);

        assertEquals(3.14, result.getValue());
        assertEquals(Reason.ERROR.toString(), result.getReason());
        assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
    }

    @Test
    void getObjectEvaluation_returnsStringAsValue() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("config", "String", "{}", "{\"key\":\"value\"}", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        ProviderEvaluation<Value> result = provider.getObjectEvaluation(
                "config", new Value("{}"), null);

        assertEquals("{\"key\":\"value\"}", result.getValue().asString());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void initialize_fetchesFlagsWithContext() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of());

        EvaluationContext context = new ImmutableContext("user-123", Map.of(
                "email", new Value("test@example.com")
        ));
        provider.initialize(context);

        verify(mockClient).fetchFlags(argThat(identity ->
                "user-123".equals(identity.getId()) &&
                        "test@example.com".equals(identity.getTraits().get("email"))
        ));
    }

    @Test
    void initialize_withNullContext_usesAnonymousIdentity() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of());

        provider.initialize(null);

        verify(mockClient).fetchFlags(argThat(identity ->
                "anonymous".equals(identity.getId())
        ));
    }

    @Test
    void refreshFlags_updatesCacheWithNewFlags() throws Exception {
        // Initial flags
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("feature", "Boolean", "false", "true", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result1 = provider.getBooleanEvaluation("feature", false, null);
        assertTrue(result1.getValue());

        // Updated flags
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("feature", "Boolean", "false", "false", "rule-1", "TARGETING_MATCH")
        ));
        provider.refreshFlags(null);

        ProviderEvaluation<Boolean> result2 = provider.getBooleanEvaluation("feature", false, null);
        assertFalse(result2.getValue());
    }

    @Test
    void shutdown_clearsFlagCache() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("feature", "Boolean", "false", "true", "rule-0", "TARGETING_MATCH")
        ));
        provider.initialize(null);

        // Flag exists before shutdown
        ProviderEvaluation<Boolean> result1 = provider.getBooleanEvaluation("feature", false, null);
        assertTrue(result1.getValue());

        provider.shutdown();

        // Flag not found after shutdown, returns passed default
        ProviderEvaluation<Boolean> result2 = provider.getBooleanEvaluation("feature", false, null);
        assertFalse(result2.getValue());
        assertEquals(Reason.DEFAULT.toString(), result2.getReason());
    }

    @Test
    void getBooleanEvaluation_withRollout_returnsSplitReason() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of(
                new Flag("feature", "Boolean", "false", "true", "rollout", "SPLIT")
        ));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation("feature", false, null);

        assertTrue(result.getValue());
        assertEquals("SPLIT", result.getReason());
        assertEquals("rollout", result.getVariant());
    }
}
