package dev.flipswitch.openfeature;

import dev.openfeature.sdk.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @AfterEach
    void tearDown() {
        OpenFeatureAPI.getInstance().shutdown();
    }

    private FlipswitchClient.BulkFetchResult createBulkResult(List<EvaluationResult> results) {
        return new FlipswitchClient.BulkFetchResult(results, false, null);
    }

    @Test
    void getMetadata_returnsFlipswitch() {
        assertEquals("flipswitch", provider.getMetadata().getName());
    }

    @Test
    void getBooleanEvaluation_withMatchingFlag_returnsEvaluatedValue() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature-enabled", true, "TARGETING_MATCH", "enabled", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "feature-enabled", false, null);

        assertTrue(result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
        assertEquals("enabled", result.getVariant());
    }

    @Test
    void getBooleanEvaluation_withFalseValue_returnsFalse() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature-disabled", false, "DEFAULT", "default", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "feature-disabled", true, null);

        assertFalse(result.getValue());
        assertEquals("DEFAULT", result.getReason());
        assertEquals("default", result.getVariant());
    }

    @Test
    void getBooleanEvaluation_withUnknownFlag_returnsPassedDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of()));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "unknown-flag", true, null);

        assertTrue(result.getValue());
        assertEquals(Reason.DEFAULT.toString(), result.getReason());
    }

    @Test
    void getBooleanEvaluation_withNonBooleanValue_returnsDefaultWithTypeError() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", "not-a-boolean", "TARGETING_MATCH", "variant", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation(
                "feature", true, null);

        assertTrue(result.getValue()); // Returns default
        assertEquals(Reason.ERROR.toString(), result.getReason());
        assertEquals(ErrorCode.TYPE_MISMATCH, result.getErrorCode());
    }

    @Test
    void getStringEvaluation_withMatchingFlag_returnsEvaluatedValue() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("variant", "treatment-a", "TARGETING_MATCH", "rule-0", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<String> result = provider.getStringEvaluation(
                "variant", "control", null);

        assertEquals("treatment-a", result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getStringEvaluation_withNonStringValue_convertsToString() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("variant", 42, "DEFAULT", "default", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<String> result = provider.getStringEvaluation(
                "variant", "fallback", null);

        assertEquals("42", result.getValue()); // Converted to string
        assertEquals("DEFAULT", result.getReason());
    }

    @Test
    void getIntegerEvaluation_withValidNumber_returnsInteger() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("max-items", 50, "TARGETING_MATCH", "rule-0", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Integer> result = provider.getIntegerEvaluation(
                "max-items", 10, null);

        assertEquals(50, result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getIntegerEvaluation_withNonNumber_returnsDefaultWithTypeError() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("max-items", "not-a-number", "DEFAULT", "default", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Integer> result = provider.getIntegerEvaluation(
                "max-items", 99, null);

        assertEquals(99, result.getValue());
        assertEquals(Reason.ERROR.toString(), result.getReason());
        assertEquals(ErrorCode.TYPE_MISMATCH, result.getErrorCode());
    }

    @Test
    void getDoubleEvaluation_withValidNumber_returnsDouble() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("rate-limit", 2.5, "TARGETING_MATCH", "rule-0", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Double> result = provider.getDoubleEvaluation(
                "rate-limit", 1.0, null);

        assertEquals(2.5, result.getValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getDoubleEvaluation_withNonNumber_returnsDefaultWithTypeError() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("rate-limit", "invalid", "DEFAULT", "default", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Double> result = provider.getDoubleEvaluation(
                "rate-limit", 3.14, null);

        assertEquals(3.14, result.getValue());
        assertEquals(Reason.ERROR.toString(), result.getReason());
        assertEquals(ErrorCode.TYPE_MISMATCH, result.getErrorCode());
    }

    @Test
    void getObjectEvaluation_withMap_returnsStructure() throws Exception {
        Map<String, Object> configValue = Map.of("key", "value", "enabled", true);
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("config", configValue, "TARGETING_MATCH", "rule-0", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Value> result = provider.getObjectEvaluation(
                "config", new Value("{}"), null);

        assertTrue(result.getValue().isStructure());
        assertEquals("value", result.getValue().asStructure().getValue("key").asString());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void getObjectEvaluation_withBoolean_returnsBooleanValue() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", true, "TARGETING_MATCH", "enabled", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Value> result = provider.getObjectEvaluation(
                "feature", new Value(false), null);

        assertTrue(result.getValue().isBoolean());
        assertTrue(result.getValue().asBoolean());
    }

    @Test
    void initialize_fetchesFlagsWithContext() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of()));

        dev.openfeature.sdk.EvaluationContext context = new ImmutableContext("user-123", Map.of(
                "email", new Value("test@example.com")
        ));
        provider.initialize(context);

        verify(mockClient).fetchFlags(argThat(evalContext ->
                "user-123".equals(evalContext.getTargetingKey()) &&
                        "test@example.com".equals(evalContext.getProperties().get("email"))
        ));
    }

    @Test
    void initialize_withNullContext_usesAnonymousIdentity() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of()));

        provider.initialize(null);

        verify(mockClient).fetchFlags(argThat(evalContext ->
                "anonymous".equals(evalContext.getTargetingKey())
        ));
    }

    @Test
    void refreshFlags_updatesCacheWithNewFlags() throws Exception {
        // Initial flags
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", true, "TARGETING_MATCH", "rule-0", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result1 = provider.getBooleanEvaluation("feature", false, null);
        assertTrue(result1.getValue());

        // Updated flags
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", false, "TARGETING_MATCH", "rule-1", null, null, null)
        )));
        provider.refreshFlags(null);

        ProviderEvaluation<Boolean> result2 = provider.getBooleanEvaluation("feature", false, null);
        assertFalse(result2.getValue());
    }

    @Test
    void refreshFlags_withNotModified_keepsCachedFlags() throws Exception {
        // Initial fetch - returns flags
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", true, "TARGETING_MATCH", "rule-0", null, null, null)
        )));
        provider.initialize(null);

        // Second fetch - returns 304 Not Modified
        when(mockClient.fetchFlags(any())).thenReturn(
                new FlipswitchClient.BulkFetchResult(List.of(), true, "\"etag\""));
        provider.refreshFlags(null);

        // Should still have the original value
        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation("feature", false, null);
        assertTrue(result.getValue());
    }

    @Test
    void shutdown_clearsFlagCache() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", true, "TARGETING_MATCH", "rule-0", null, null, null)
        )));
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
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", true, "SPLIT", "rollout", null, null, null)
        )));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation("feature", false, null);

        assertTrue(result.getValue());
        assertEquals("SPLIT", result.getReason());
        assertEquals("rollout", result.getVariant());
    }

    @Test
    void getBooleanEvaluation_withErrorResult_returnsDefault() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of(
                new EvaluationResult("feature", null, null, null, null, "FLAG_NOT_FOUND", "Flag not found")
        )));
        provider.initialize(null);

        ProviderEvaluation<Boolean> result = provider.getBooleanEvaluation("feature", true, null);

        assertTrue(result.getValue()); // Returns default
        assertEquals(Reason.DEFAULT.toString(), result.getReason());
    }

    // === Event Tests ===

    @Test
    void initialize_emitsProviderReadyOnSuccess() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of()));

        CountDownLatch readyLatch = new CountDownLatch(1);
        AtomicReference<ProviderEventDetails> eventDetails = new AtomicReference<>();

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.onProviderReady(details -> {
            eventDetails.set(details);
            readyLatch.countDown();
        });
        api.setProviderAndWait(provider);

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "PROVIDER_READY event should be emitted");
    }

    @Test
    void initialize_emitsProviderErrorOnFailure() throws Exception {
        when(mockClient.fetchFlags(any())).thenThrow(new FlipswitchException("Connection failed"));

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<ProviderEventDetails> eventDetails = new AtomicReference<>();

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.onProviderError(details -> {
            eventDetails.set(details);
            errorLatch.countDown();
        });

        try {
            api.setProviderAndWait(provider);
        } catch (Exception e) {
            // Expected - initialization failed
        }

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "PROVIDER_ERROR event should be emitted");
        assertNotNull(eventDetails.get());
        assertTrue(eventDetails.get().getMessage().contains("Initialization failed"));
    }

    @Test
    void refreshFlags_emitsConfigurationChanged() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of()));

        CountDownLatch changedLatch = new CountDownLatch(1);

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.onProviderConfigurationChanged(details -> changedLatch.countDown());
        api.setProviderAndWait(provider);

        // Now refresh - this should emit CONFIGURATION_CHANGED
        provider.refreshFlags(null);

        assertTrue(changedLatch.await(5, TimeUnit.SECONDS), "PROVIDER_CONFIGURATION_CHANGED event should be emitted");
    }

    @Test
    void markStale_emitsProviderStale() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(createBulkResult(List.of()));

        CountDownLatch staleLatch = new CountDownLatch(1);
        AtomicReference<ProviderEventDetails> eventDetails = new AtomicReference<>();

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.onProviderStale(details -> {
            eventDetails.set(details);
            staleLatch.countDown();
        });
        api.setProviderAndWait(provider);

        provider.markStale();

        assertTrue(staleLatch.await(5, TimeUnit.SECONDS), "PROVIDER_STALE event should be emitted");
        assertNotNull(eventDetails.get());
        assertEquals("Cache marked as stale", eventDetails.get().getMessage());
    }
}
