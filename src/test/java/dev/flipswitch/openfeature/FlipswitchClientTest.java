package dev.flipswitch.openfeature;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class FlipswitchClientTest {

    @Test
    void fetchFlags_withValidResponse_returnsFlags(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/flags"))
                .withHeader("X-Environment-Key", equalTo("test-api-key"))
                .withHeader("Content-Type", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                    {"key": "feature-a", "flagType": "Boolean", "defaultStringValue": "false", "stringValue": "true"},
                                    {"key": "feature-b", "flagType": "String", "defaultStringValue": "default", "stringValue": null}
                                ]
                                """)));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-api-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        List<Flag> flags = client.fetchFlags(new Identity("user-1", Map.of()));

        assertEquals(2, flags.size());

        Flag flagA = flags.get(0);
        assertEquals("feature-a", flagA.getKey());
        assertEquals("Boolean", flagA.getFlagType());
        assertEquals("false", flagA.getDefaultStringValue());
        assertEquals("true", flagA.getStringValue());
        assertEquals("true", flagA.getEffectiveValue());

        Flag flagB = flags.get(1);
        assertEquals("feature-b", flagB.getKey());
        assertNull(flagB.getStringValue());
        assertEquals("default", flagB.getEffectiveValue());
    }

    @Test
    void fetchFlags_sendsIdentityInRequestBody(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/flags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        client.fetchFlags(new Identity("user-123", Map.of("email", "test@example.com", "plan", "premium")));

        verify(postRequestedFor(urlEqualTo("/api/v1/flags"))
                .withRequestBody(matchingJsonPath("$.id", equalTo("user-123")))
                .withRequestBody(matchingJsonPath("$.traits.email", equalTo("test@example.com")))
                .withRequestBody(matchingJsonPath("$.traits.plan", equalTo("premium"))));
    }

    @Test
    void fetchFlags_withEmptyResponse_returnsEmptyList(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/flags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);
        List<Flag> flags = client.fetchFlags(new Identity("user-1", Map.of()));

        assertTrue(flags.isEmpty());
    }

    @Test
    void fetchFlags_withServerError_throwsException(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/api/v1/flags"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        FlipswitchException exception = assertThrows(FlipswitchException.class, () ->
                client.fetchFlags(new Identity("user-1", Map.of())));

        assertTrue(exception.getMessage().contains("HTTP 500"));
    }

    @Test
    void fetchFlags_withBadRequest_throwsException(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/api/v1/flags"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Missing header")));

        FlipswitchConfig config = FlipswitchConfig.builder()
                .baseUrl(wmRuntimeInfo.getHttpBaseUrl())
                .apiKey("test-key")
                .build();

        FlipswitchClient client = new FlipswitchClient(config);

        FlipswitchException exception = assertThrows(FlipswitchException.class, () ->
                client.fetchFlags(new Identity("user-1", Map.of())));

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
                client.fetchFlags(new Identity("user-1", Map.of())));
    }
}
