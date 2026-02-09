# Flipswitch Java SDK 

[![CI](https://github.com/flipswitch-io/java-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/flipswitch-io/java-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.flipswitch/flipswitch-sdk.svg)](https://search.maven.org/artifact/io.flipswitch/flipswitch-sdk)
[![codecov](https://codecov.io/gh/flipswitch-io/java-sdk/branch/main/graph/badge.svg)](https://codecov.io/gh/flipswitch-io/java-sdk)

Flipswitch SDK for Java with real-time SSE support for OpenFeature.

This SDK provides an OpenFeature-compatible provider that wraps OFREP flag evaluation with automatic cache invalidation via Server-Sent Events (SSE). When flags change in your Flipswitch dashboard, connected clients receive updates in real-time.

## Overview

- **OpenFeature Compatible**: Works with the OpenFeature standard for feature flags
- **Real-Time Updates**: SSE connection delivers instant flag changes
- **Polling Fallback**: Automatic fallback when SSE connection fails
- **Builder Pattern**: Fluent API for easy configuration

## Requirements

- Java 17+
- OpenFeature SDK 1.x

## Installation

### Maven

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.flipswitch</groupId>
    <artifactId>flipswitch-sdk</artifactId>
</dependency>
```

Check [Maven Central](https://central.sonatype.com/artifact/io.flipswitch/flipswitch-sdk) for the latest version.

### Gradle

```groovy
implementation 'io.flipswitch:flipswitch-sdk'
```

Check [Maven Central](https://central.sonatype.com/artifact/io.flipswitch/flipswitch-sdk) for the latest version.

## Quick Start

```java
import io.flipswitch.FlipswitchProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Client;

// Create provider with API key
FlipswitchProvider provider = FlipswitchProvider.builder("your-environment-api-key").build();

// Register with OpenFeature
OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(provider);

// Get a client and evaluate flags
Client client = api.getClient();

boolean darkMode = client.getBooleanValue("dark-mode", false);
String welcome = client.getStringValue("welcome-message", "Hello!");
int maxItems = client.getIntegerValue("max-items-per-page", 10);
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey` | `String` | *required* | Environment API key from dashboard |
| `baseUrl` | `String` | `https://api.flipswitch.io` | Your Flipswitch server URL |
| `enableRealtime` | `boolean` | `true` | Enable SSE for real-time flag updates |
| `httpClient` | `OkHttpClient` | default | Custom HTTP client |
| `enablePollingFallback` | `boolean` | `true` | Fall back to polling when SSE fails |
| `pollingIntervalMs` | `long` | `30000` | Polling interval in milliseconds |
| `maxSseRetries` | `int` | `5` | Max SSE retries before polling fallback |

```java
FlipswitchProvider provider = FlipswitchProvider.builder("your-api-key")
    .baseUrl("https://custom.server.com")
    .enableRealtime(true)
    .enablePollingFallback(true)
    .pollingIntervalMs(30000)
    .maxSseRetries(5)
    .httpClient(customOkHttpClient)
    .build();
```

## Usage Examples

### Basic Flag Evaluation

```java
Client client = api.getClient();

// Boolean flag
boolean darkMode = client.getBooleanValue("dark-mode", false);

// String flag
String welcomeMessage = client.getStringValue("welcome-message", "Hello!");

// Integer flag
int maxItems = client.getIntegerValue("max-items", 10);

// Double flag
double discount = client.getDoubleValue("discount-rate", 0.0);

// Object flag
Value config = client.getObjectValue("feature-config", new Value());
```

### Evaluation Context

Target specific users or segments:

```java
import dev.openfeature.sdk.MutableContext;

MutableContext context = new MutableContext("user-123");
context.add("email", "user@example.com");
context.add("plan", "premium");
context.add("country", "US");
context.add("betaUser", true);

boolean showFeature = client.getBooleanValue("new-feature", false, context);
```

### Real-Time Updates (SSE)

Listen for flag changes:

```java
provider.addFlagChangeListener(event -> {
    if (event.flagKey() != null) {
        System.out.println("Flag changed: " + event.flagKey());
    } else {
        System.out.println("All flags invalidated");
    }
    System.out.println("Timestamp: " + event.getTimestampAsInstant());
});

// Check SSE status
SseClient.ConnectionStatus status = provider.getSseStatus();
// CONNECTING, CONNECTED, DISCONNECTED, ERROR

// Force reconnect
provider.reconnectSse();
```

### Bulk Flag Evaluation

Evaluate all flags at once:

```java
List<FlagEvaluation> flags = provider.evaluateAllFlags(context);
for (FlagEvaluation flag : flags) {
    System.out.println(flag.key() + " (" + flag.valueType() + "): " + flag.getValueAsString());
    System.out.println("  Reason: " + flag.reason() + ", Variant: " + flag.variant());
}

// Single flag with full details
FlagEvaluation flag = provider.evaluateFlag("dark-mode", context);
if (flag != null) {
    System.out.println("Value: " + flag.value());
    System.out.println("Reason: " + flag.reason());
    System.out.println("Variant: " + flag.variant());
}
```

### Detailed Evaluation

Get full evaluation details including variant and reason:

```java
FlagEvaluationDetails<Boolean> details = client.getBooleanDetails("feature-flag", false, context);

System.out.println("Value: " + details.getValue());
System.out.println("Variant: " + details.getVariant());
System.out.println("Reason: " + details.getReason());
```

## Advanced Features

### Polling Fallback

When SSE connection fails repeatedly, the SDK falls back to polling:

```java
FlipswitchProvider provider = FlipswitchProvider.builder("your-api-key")
    .enablePollingFallback(true)  // default: true
    .pollingIntervalMs(30000)     // Poll every 30 seconds
    .maxSseRetries(5)             // Fall back after 5 failed SSE attempts
    .build();

// Check if polling is active
if (provider.isPollingActive()) {
    System.out.println("Polling fallback is active");
}
```

### Custom HTTP Client

Provide a custom OkHttpClient:

```java
OkHttpClient customClient = new OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build();

FlipswitchProvider provider = FlipswitchProvider.builder("your-api-key")
    .httpClient(customClient)
    .build();
```

### Object Flags

For complex flag values (JSON objects):

```java
Value config = client.getObjectValue("feature-config", new Value(), context);

if (config.isStructure()) {
    Structure s = config.asStructure();
    String theme = s.getValue("theme").asString();
    int timeout = s.getValue("timeout").asInteger();
}
```

## Framework Integration

### Spring Boot

```java
@Configuration
public class FeatureFlagConfig {

    @Bean
    public FlipswitchProvider flipswitchProvider(
            @Value("${flipswitch.api-key}") String apiKey) {
        return FlipswitchProvider.builder(apiKey).build();
    }

    @Bean
    public Client openFeatureClient(FlipswitchProvider provider) throws Exception {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(provider);
        return api.getClient();
    }
}
```

```java
@RestController
public class MyController {

    private final Client featureClient;

    public MyController(Client featureClient) {
        this.featureClient = featureClient;
    }

    @GetMapping("/")
    public String index(@RequestHeader("X-User-ID") String userId) {
        MutableContext context = new MutableContext(userId);

        if (featureClient.getBooleanValue("new-feature", false, context)) {
            return "New feature enabled!";
        }
        return "Standard feature";
    }
}
```

### Micronaut

```java
@Factory
public class FeatureFlagFactory {

    @Singleton
    public FlipswitchProvider flipswitchProvider(@Value("${flipswitch.api-key}") String apiKey) {
        return FlipswitchProvider.builder(apiKey).build();
    }

    @Singleton
    public Client openFeatureClient(FlipswitchProvider provider) throws Exception {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(provider);
        return api.getClient();
    }
}
```

## Error Handling

The SDK handles errors gracefully:

```java
try {
    FlipswitchProvider provider = FlipswitchProvider.builder("your-api-key").build();
    OpenFeatureAPI.getInstance().setProviderAndWait(provider);
} catch (Exception e) {
    log.error("Failed to initialize provider: {}", e.getMessage());
    // Provider will use default values
}

// Flag evaluation never throws - returns default value on error
boolean value = client.getBooleanValue("my-flag", false);
```

## Logging

The SDK uses SLF4J for logging:

```xml
<!-- logback.xml -->
<configuration>
    <logger name="io.flipswitch" level="DEBUG"/>
</configuration>
```

```
// You'll see logs like:
// INFO io.flipswitch.FlipswitchProvider - Flipswitch provider initialized (realtime=true)
// DEBUG io.flipswitch.SseClient - SSE connection established
// WARN io.flipswitch.FlipswitchProvider - SSE connection error (retry 1), provider is stale
// INFO io.flipswitch.FlipswitchProvider - Starting polling fallback
```

## Testing

Mock the provider in your tests:

```java
import static org.mockito.Mockito.*;

@Test
void testWithMockProvider() {
    Client mockClient = mock(Client.class);
    when(mockClient.getBooleanValue("dark-mode", false)).thenReturn(true);

    // Use mock client in your tests
    assertTrue(mockClient.getBooleanValue("dark-mode", false));
}
```

Or use InMemoryProvider:

```java
@Test
void testWithInMemoryProvider() throws Exception {
    InMemoryProvider provider = new InMemoryProvider(Map.of(
        "dark-mode", new InMemoryProvider.Flag<>(Boolean.class, true),
        "max-items", new InMemoryProvider.Flag<>(Integer.class, 10)
    ));

    OpenFeatureAPI.getInstance().setProviderAndWait(provider);
    Client client = OpenFeatureAPI.getInstance().getClient();

    assertTrue(client.getBooleanValue("dark-mode", false));
}
```

## API Reference

### FlipswitchProvider

```java
public class FlipswitchProvider extends EventProvider {
    // Builder pattern constructor
    public static Builder builder(String apiKey);

    // OpenFeature Provider interface
    public Metadata getMetadata();
    public ProviderState getState();
    public void initialize(EvaluationContext context) throws Exception;
    public void shutdown();
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx);
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx);
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx);
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx);
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx);

    // Flipswitch-specific methods
    public SseClient.ConnectionStatus getSseStatus();
    public void reconnectSse();
    public boolean isPollingActive();
    public void addFlagChangeListener(Consumer<FlagChangeEvent> listener);
    public void removeFlagChangeListener(Consumer<FlagChangeEvent> listener);
    public List<FlagEvaluation> evaluateAllFlags(EvaluationContext context);
    public FlagEvaluation evaluateFlag(String flagKey, EvaluationContext context);
}
```

### Types

```java
public record FlagChangeEvent(String flagKey, String timestamp) {
    public Instant getTimestampAsInstant();
}

public enum ConnectionStatus {
    CONNECTING, CONNECTED, DISCONNECTED, ERROR
}

public record FlagEvaluation(
    String key,
    Object value,
    String reason,
    String variant,
    String valueType
) {
    public String getValueAsString();
}
```

## Troubleshooting

### SSE Connection Fails

- Check that your API key is valid
- Verify your server URL is correct
- Check for network/firewall issues blocking SSE
- The SDK will automatically fall back to polling

### Flags Not Updating in Real-Time

- Ensure `enableRealtime(true)` is set (default)
- Check SSE status with `provider.getSseStatus()`
- Check logs for error messages

### Provider Initialization Fails

- Verify your API key is correct
- Check network connectivity to the Flipswitch server
- Review logs for detailed error messages

## Reconnection Strategy

The SSE client automatically reconnects with exponential backoff:
- Initial delay: 1 second
- Maximum delay: 30 seconds
- Backoff multiplier: 2x

When reconnected, the provider state changes from `STALE` back to `READY`.

## Demo

Run the included demo:

```bash
mvn compile test-compile exec:java -Dexec.mainClass="io.flipswitch.examples.FlipswitchDemo" \
    -Dexec.args="<your-api-key>"
```

The demo will connect, display all flags, and listen for real-time updates.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

MIT - see [LICENSE](LICENSE) for details.
