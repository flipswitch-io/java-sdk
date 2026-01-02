package dev.flipswitch.openfeature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client for communicating with the Flipswitch Flag API.
 */
public class FlipswitchClient {

    private final FlipswitchConfig config;
    private final ObjectMapper objectMapper;

    public FlipswitchClient(FlipswitchConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Fetches all flags from the server for the given identity.
     */
    public List<Flag> fetchFlags(Identity identity) throws FlipswitchException {
        try {
            URL url = new URL(config.getBaseUrl() + "/api/v1/flags");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Environment-Key", config.getApiKey());

            String requestBody = objectMapper.writeValueAsString(identity);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new FlipswitchException("Failed to fetch flags: HTTP " + responseCode);
            }

            return objectMapper.readValue(
                    conn.getInputStream(),
                    new TypeReference<List<Flag>>() {}
            );

        } catch (IOException e) {
            throw new FlipswitchException("Failed to fetch flags", e);
        }
    }

    /**
     * Fetches a single flag from the server for the given identity.
     *
     * @param flagKey  The flag key to fetch
     * @param identity The user identity for flag evaluation
     * @return The evaluated flag, or null if not found
     * @throws FlipswitchException if there's a communication error
     */
    public Flag fetchFlag(String flagKey, Identity identity) throws FlipswitchException {
        try {
            URL url = new URL(config.getBaseUrl() + "/api/v1/flags/" + flagKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Environment-Key", config.getApiKey());

            String requestBody = objectMapper.writeValueAsString(identity);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                return null; // Flag not found
            }
            if (responseCode != 200) {
                throw new FlipswitchException("Failed to fetch flag: HTTP " + responseCode);
            }

            return objectMapper.readValue(conn.getInputStream(), Flag.class);

        } catch (IOException e) {
            throw new FlipswitchException("Failed to fetch flag", e);
        }
    }
}
