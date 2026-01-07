package dev.flipswitch.openfeature;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client for communicating with the Flipswitch Flag API using OFREP protocol.
 */
public class FlipswitchClient {

    private final FlipswitchConfig config;
    private final ObjectMapper objectMapper;

    // ETag caching
    private String cachedETag;
    private List<EvaluationResult> cachedResults;

    public FlipswitchClient(FlipswitchConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Result of a bulk fetch operation.
     * Contains the results and whether the cache was used (304 response).
     */
    public static class BulkFetchResult {
        private final List<EvaluationResult> results;
        private final boolean notModified;
        private final String etag;

        public BulkFetchResult(List<EvaluationResult> results, boolean notModified, String etag) {
            this.results = results;
            this.notModified = notModified;
            this.etag = etag;
        }

        public List<EvaluationResult> getResults() {
            return results;
        }

        public boolean isNotModified() {
            return notModified;
        }

        public String getEtag() {
            return etag;
        }
    }

    /**
     * Fetches all flags from the server for the given evaluation context.
     * Uses ETag caching - returns cached results if server returns 304.
     */
    public BulkFetchResult fetchFlags(EvaluationContext context) throws FlipswitchException {
        try {
            URL url = new URL(config.getBaseUrl() + "/ofrep/v1/evaluate/flags");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key", config.getApiKey());

            // Add If-None-Match header if we have a cached ETag
            if (cachedETag != null) {
                conn.setRequestProperty("If-None-Match", cachedETag);
            }

            String requestBody = objectMapper.writeValueAsString(context);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();

            // Handle 304 Not Modified - return cached results
            if (responseCode == 304 && cachedResults != null) {
                return new BulkFetchResult(cachedResults, true, cachedETag);
            }

            if (responseCode != 200) {
                throw new FlipswitchException("Failed to fetch flags: HTTP " + responseCode);
            }

            // Parse the response
            BulkEvaluationSuccess response = objectMapper.readValue(
                    conn.getInputStream(),
                    BulkEvaluationSuccess.class
            );

            // Store new ETag and results for caching
            String newETag = conn.getHeaderField("ETag");
            if (newETag != null) {
                cachedETag = newETag;
                cachedResults = response.getFlags();
            }

            return new BulkFetchResult(response.getFlags(), false, newETag);

        } catch (IOException e) {
            throw new FlipswitchException("Failed to fetch flags", e);
        }
    }

    /**
     * Fetches a single flag from the server for the given evaluation context.
     *
     * @param flagKey The flag key to fetch
     * @param context The evaluation context for flag evaluation
     * @return The evaluated flag result, or null if not found
     * @throws FlipswitchException if there's a communication error
     */
    public EvaluationResult fetchFlag(String flagKey, EvaluationContext context) throws FlipswitchException {
        try {
            URL url = new URL(config.getBaseUrl() + "/ofrep/v1/evaluate/flags/" + flagKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key", config.getApiKey());

            String requestBody = objectMapper.writeValueAsString(context);
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

            return objectMapper.readValue(conn.getInputStream(), EvaluationResult.class);

        } catch (IOException e) {
            throw new FlipswitchException("Failed to fetch flag", e);
        }
    }

    /**
     * Clears the cached ETag and results.
     * Useful when you want to force a fresh fetch.
     */
    public void clearCache() {
        cachedETag = null;
        cachedResults = null;
    }
}
