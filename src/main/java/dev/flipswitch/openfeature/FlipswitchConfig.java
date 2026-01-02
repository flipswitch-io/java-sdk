package dev.flipswitch.openfeature;

/**
 * Configuration for connecting to Flipswitch.
 */
public class FlipswitchConfig {

    private final String baseUrl;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean streamingEnabled;
    private final int reconnectDelayMs;
    private final int maxReconnectDelayMs;
    private final int heartbeatTimeoutMs;

    private FlipswitchConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.streamingEnabled = builder.streamingEnabled;
        this.reconnectDelayMs = builder.reconnectDelayMs;
        this.maxReconnectDelayMs = builder.maxReconnectDelayMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public int getReconnectDelayMs() {
        return reconnectDelayMs;
    }

    public int getMaxReconnectDelayMs() {
        return maxReconnectDelayMs;
    }

    public int getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl = "http://localhost:8080";
        private String apiKey;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
        private boolean streamingEnabled = false;
        private int reconnectDelayMs = 1000;
        private int maxReconnectDelayMs = 30000;
        private int heartbeatTimeoutMs = 60000;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        /**
         * Enable SSE streaming for real-time flag updates.
         * When enabled, the provider will connect to the server's SSE endpoint
         * and automatically refresh flags when changes are detected.
         */
        public Builder streamingEnabled(boolean streamingEnabled) {
            this.streamingEnabled = streamingEnabled;
            return this;
        }

        /**
         * Initial delay before reconnecting after a connection failure.
         * Default: 1000ms
         */
        public Builder reconnectDelayMs(int reconnectDelayMs) {
            this.reconnectDelayMs = reconnectDelayMs;
            return this;
        }

        /**
         * Maximum delay between reconnection attempts (for exponential backoff).
         * Default: 30000ms
         */
        public Builder maxReconnectDelayMs(int maxReconnectDelayMs) {
            this.maxReconnectDelayMs = maxReconnectDelayMs;
            return this;
        }

        /**
         * Time without heartbeat before the provider is marked as stale.
         * Default: 60000ms
         */
        public Builder heartbeatTimeoutMs(int heartbeatTimeoutMs) {
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            return this;
        }

        public FlipswitchConfig build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            return new FlipswitchConfig(this);
        }
    }
}
