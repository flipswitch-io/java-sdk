# Flipswitch Java SDK

Flipswitch SDK for Java with real-time SSE support.

This SDK provides an OpenFeature-compatible provider that wraps OFREP flag evaluation with automatic cache invalidation via Server-Sent Events (SSE). When flags change in your Flipswitch dashboard, connected clients receive updates in real-time.

## Requirements

- Java 17+
- OpenFeature SDK 1.x

## Installation

### Maven

```xml
<dependency>
    <groupId>dev.flipswitch</groupId>
    <artifactId>flipswitch-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'dev.flipswitch:flipswitch-sdk:0.1.0'
```

## Quick Start

```java
import dev.flipswitch.FlipswitchProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.MutableContext;

// API key is required, all other options have sensible defaults
FlipswitchProvider provider = FlipswitchProvider.builder("your-environment-api-key").build();

// Register with OpenFeature
OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(provider);

// Get a client
Client client = api.getClient();

// Evaluate flags
boolean darkMode = client.getBooleanValue("dark-mode", false);
String welcome = client.getStringValue("welcome-message", "Hello!");
int maxItems = client.getIntegerValue("max-items-per-page", 10);
```

## Configuration Options

```java
FlipswitchProvider provider = FlipswitchProvider.builder("your-api-key")
    .baseUrl("https://custom.server.com")    // Optional: defaults to https://api.flipswitch.io
    .enableRealtime(true)                    // Optional: defaults to true
    .cacheTtl(Duration.ofSeconds(60))        // Optional: defaults to 60s
    .httpClient(customOkHttpClient)          // Optional: custom OkHttpClient
    .build();
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey` | `String` | *required* | Environment API key from dashboard |
| `baseUrl` | `String` | `https://api.flipswitch.io` | Your Flipswitch server URL |
| `enableRealtime` | `boolean` | `true` | Enable SSE for real-time flag updates |
| `cacheTtl` | `Duration` | `60s` | Cache time-to-live |
| `httpClient` | `OkHttpClient` | default | Custom HTTP client |

## Evaluation Context

Pass user attributes for targeting:

```java
MutableContext context = new MutableContext("user-123");
context.add("email", "user@example.com");
context.add("plan", "premium");
context.add("country", "SE");

boolean showFeature = client.getBooleanValue("new-feature", false, context);
```

## Real-Time Updates

When `enableRealtime(true)` is set, the SDK maintains a Server-Sent Events (SSE) connection to receive instant flag change notifications. When a flag changes:

1. The SSE client receives a `flag-change` event
2. The local cache is immediately invalidated
3. Next flag evaluation fetches the fresh value

### Event Listeners

```java
provider.addFlagChangeListener(event -> {
    System.out.println("Flag changed: " + event.getFlagKey());
    System.out.println("Timestamp: " + event.getTimestamp());
});
```

### Connection Status

```java
// Check current SSE status
SseClient.ConnectionStatus status = provider.getSseStatus();
// CONNECTING, CONNECTED, DISCONNECTED, ERROR

// Force reconnect
provider.reconnectSse();
```

## Detailed Evaluation

Get full evaluation details including variant and reason:

```java
FlagEvaluationDetails<Boolean> details = client.getBooleanDetails("feature-flag", false, context);

System.out.println("Value: " + details.getValue());
System.out.println("Variant: " + details.getVariant());
System.out.println("Reason: " + details.getReason());
```

## Object Flags

For complex flag values (JSON objects):

```java
Value config = client.getObjectValue("feature-config", new Value(), context);

// Access structure
if (config.isStructure()) {
    Structure s = config.asStructure();
    String theme = s.getValue("theme").asString();
    int timeout = s.getValue("timeout").asInteger();
}
```

## Bulk Flag Evaluation

Evaluate all flags at once or get detailed evaluation results:

```java
// Evaluate all flags
List<FlagEvaluation> flags = provider.evaluateAllFlags(context);
for (FlagEvaluation flag : flags) {
    System.out.println(flag.getKey() + " (" + flag.getValueType() + "): " + flag.getValueAsString());
}

// Evaluate a single flag with full details
FlagEvaluation flag = provider.evaluateFlag("dark-mode", context);
if (flag != null) {
    System.out.println("Value: " + flag.getValue());
    System.out.println("Reason: " + flag.getReason());
    System.out.println("Variant: " + flag.getVariant());
}
```

## Reconnection Strategy

The SSE client automatically reconnects with exponential backoff:
- Initial delay: 1 second
- Maximum delay: 30 seconds
- Backoff multiplier: 2x

When reconnected, the provider state changes from `STALE` back to `READY`.

## Shutdown

Always shutdown the provider when done:

```java
provider.shutdown();
// or
OpenFeatureAPI.getInstance().shutdown();
```

## Spring Boot Integration

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

## Demo

A complete working demo is included in the test sources. To run it:

```bash
cd sdks/java
mvn compile test-compile exec:java -Dexec.mainClass="dev.flipswitch.examples.FlipswitchDemo" \
    -Dexec.args="<your-api-key>"
```

The demo will:
1. Connect to Flipswitch and validate your API key
2. Load and display all flags with their types and values
3. Listen for real-time flag changes and display updates

See [FlipswitchDemo.java](./src/test/java/dev/flipswitch/examples/FlipswitchDemo.java) for the full source.

## License

MIT
