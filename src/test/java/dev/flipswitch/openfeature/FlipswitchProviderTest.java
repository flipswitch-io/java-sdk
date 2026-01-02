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

    // === Event Tests ===

    @Test
    void initialize_emitsProviderReadyOnSuccess() throws Exception {
        when(mockClient.fetchFlags(any())).thenReturn(List.of());

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
        when(mockClient.fetchFlags(any())).thenReturn(List.of());

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
        when(mockClient.fetchFlags(any())).thenReturn(List.of());

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
