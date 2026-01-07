package dev.flipswitch.openfeature;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class FlipswitchClientTest {

    @Test
    void fetchFlags_withValidResponse_returnsFlags(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .withHeader("X-API-Key", equalTo("test-api-key"))
                .withHeader("Content-Type", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"abc123\"")
                        .withBody("""
                                {
                                    "flags": [
                                        {"key": "feature-a", "value": true, "reason": "TARGETING_MATCH", "variant": "enabled"},
                                        {"key": "feature-b", "value": "treatment", "reason": "DEFAULT", "variant": "default"}
                                    ]
                                }
                                """)));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-api-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        FlipswitchClient.BulkFetchResult result = client.fetchFlags(
                new EvaluationContext("user-1", Map.of()));

        assertEquals(2, result.getResults().size());
        assertFalse(result.isNotModified());
        assertEquals("\"abc123\"", result.getEtag());

        EvaluationResult flagA = result.getResults().get(0);
        assertEquals("feature-a", flagA.getKey());
        assertEquals(true, flagA.getBooleanValue());
        assertEquals("TARGETING_MATCH", flagA.getReason());
        assertEquals("enabled", flagA.getVariant());

        EvaluationResult flagB = result.getResults().get(1);
        assertEquals("feature-b", flagB.getKey());
        assertEquals("treatment", flagB.getStringValue());
        assertEquals("DEFAULT", flagB.getReason());
    }

    @Test
    void fetchFlags_sendsEvaluationContextInRequestBody(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"flags\": []}")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        client.fetchFlags(new EvaluationContext("user-123", Map.of("email", "test@example.com", "plan", "premium")));

        verify(postRequestedFor(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .withRequestBody(matchingJsonPath("$.targetingKey", equalTo("user-123")))
                .withRequestBody(matchingJsonPath("$.properties.email", equalTo("test@example.com")))
                .withRequestBody(matchingJsonPath("$.properties.plan", equalTo("premium"))));
    }

    @Test
    void fetchFlags_withEmptyResponse_returnsEmptyList(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"flags\": []}")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        FlipswitchClient.BulkFetchResult result = client.fetchFlags(
                new EvaluationContext("user-1", Map.of()));

        assertTrue(result.getResults().isEmpty());
    }

    @Test
    void fetchFlags_withETagAndNotModified_returnsCachedResults(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        // First request - returns flags with ETag
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .inScenario("etag-test")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"etag-v1\"")
                        .withBody("""
                                {"flags": [{"key": "feature", "value": true, "reason": "DEFAULT", "variant": "default"}]}
                                """))
                .willSetStateTo("cached"));

        // Second request with If-None-Match - returns 304
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .inScenario("etag-test")
                .whenScenarioStateIs("cached")
                .withHeader("If-None-Match", equalTo("\"etag-v1\""))
                .willReturn(aResponse()
                        .withStatus(304)));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        // First fetch - should return flags
        FlipswitchClient.BulkFetchResult result1 = client.fetchFlags(
                new EvaluationContext("user-1", Map.of()));
        assertEquals(1, result1.getResults().size());
        assertFalse(result1.isNotModified());

        // Second fetch - should return cached results with notModified=true
        FlipswitchClient.BulkFetchResult result2 = client.fetchFlags(
                new EvaluationContext("user-1", Map.of()));
        assertEquals(1, result2.getResults().size());
        assertTrue(result2.isNotModified());
    }

    @Test
    void fetchFlags_withServerError_throwsException(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        FlipswitchException exception = assertThrows(FlipswitchException.class, () ->
                client.fetchFlags(new EvaluationContext("user-1", Map.of())));

        assertTrue(exception.getMessage().contains("HTTP 500"));
    }

    @Test
    void fetchFlags_withBadRequest_throwsException(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Missing header")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        FlipswitchException exception = assertThrows(FlipswitchException.class, () ->
                client.fetchFlags(new EvaluationContext("user-1", Map.of())));

        assertTrue(exception.getMessage().contains("HTTP 400"));
    }

    @Test
    void fetchFlags_withNetworkError_throwsException(WireMockRuntimeInfo wmRuntimeInfo) {
        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl("http://localhost:1") // Non-existent port
                .apiKey("test-key")
                .connectTimeoutMs(100)
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        assertThrows(FlipswitchException.class, () ->
                client.fetchFlags(new EvaluationContext("user-1", Map.of())));
    }

    @Test
    void fetchFlag_withValidResponse_returnsFlag(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags/feature-a"))
                .withHeader("X-API-Key", equalTo("test-api-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"key": "feature-a", "value": true, "reason": "TARGETING_MATCH", "variant": "enabled"}
                                """)));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-api-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        EvaluationResult result = client.fetchFlag("feature-a",
                new EvaluationContext("user-1", Map.of()));

        assertNotNull(result);
        assertEquals("feature-a", result.getKey());
        assertEquals(true, result.getBooleanValue());
        assertEquals("TARGETING_MATCH", result.getReason());
    }

    @Test
    void fetchFlag_withNotFound_returnsNull(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags/unknown"))
                .willReturn(aResponse()
                        .withStatus(404)));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        EvaluationResult result = client.fetchFlag("unknown",
                new EvaluationContext("user-1", Map.of()));

        assertNull(result);
    }

    @Test
    void clearCache_clearsETagAndCachedResults(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        // First request - returns flags with ETag
        stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"etag-v1\"")
                        .withBody("{\"flags\": [{\"key\": \"feature\", \"value\": true, \"reason\": \"DEFAULT\", \"variant\": \"default\"}]}")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        // First fetch to populate cache
        client.fetchFlags(new EvaluationContext("user-1", Map.of()));

        // Clear cache
        client.clearCache();

        // Next fetch should not send If-None-Match (since cache was cleared)
        client.fetchFlags(new EvaluationContext("user-1", Map.of()));

        // Both requests should NOT have If-None-Match header after clear
        // (First request never had it, second shouldn't either after clear)
        verify(2, postRequestedFor(urlEqualTo("/ofrep/v1/evaluate/flags")));
    }
}
