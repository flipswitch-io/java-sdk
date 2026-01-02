# Flipswitch OpenFeature Provider for Java

An [OpenFeature](https://openfeature.dev) provider for the Flipswitch feature flag service.

## Installation

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'dev.flipswitch:flipswitch-openfeature-java:0.1.0'
}
```

Or Maven `pom.xml`:

```xml
<dependency>
    <groupId>dev.flipswitch</groupId>
    <artifactId>flipswitch-openfeature-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Usage

```java
import dev.flipswitch.openfeature.FlipswitchConfig;
import dev.flipswitch.openfeature.FlipswitchProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Client;

// Configure the provider
FlipswitchConfig config = FlipswitchConfig.builder()
    .baseUrl("https://your-flipswitch-server.com")
    .apiKey("your-api-key")
    .build();

// Set as the OpenFeature provider
OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new FlipswitchProvider(config));

// Get a client and evaluate flags
Client client = api.getClient();

boolean featureEnabled = client.getBooleanValue("my-feature", false);
String variant = client.getStringValue("experiment-variant", "control");
```

## Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `baseUrl` | `http://localhost:8080` | Flipswitch server URL |
| `apiKey` | (required) | Your environment API key |
| `connectTimeoutMs` | `5000` | Connection timeout in milliseconds |
| `readTimeoutMs` | `10000` | Read timeout in milliseconds |
| `streamingEnabled` | `false` | Enable SSE streaming for real-time updates |
| `reconnectDelayMs` | `1000` | Initial reconnect delay for SSE |
| `maxReconnectDelayMs` | `30000` | Maximum reconnect delay (exponential backoff) |
| `heartbeatTimeoutMs` | `60000` | Mark stale if no heartbeat received |

## Evaluation Context

Pass user attributes via the evaluation context:

```java
EvaluationContext context = new ImmutableContext("user-123", Map.of(
    "email", new Value("user@example.com"),
    "plan", new Value("premium"),
    "country", new Value("SE")
));

boolean result = client.getBooleanValue("premium-feature", false, context);
```

## Events

The provider emits OpenFeature events for lifecycle and state changes:

| Event | When Emitted |
|-------|--------------|
| `PROVIDER_READY` | After successful initialization |
| `PROVIDER_ERROR` | On initialization or refresh failure |
| `PROVIDER_CONFIGURATION_CHANGED` | When flags are refreshed via `refreshFlags()` |
| `PROVIDER_STALE` | When `markStale()` is called |

### Listening to Events

```java
OpenFeatureAPI api = OpenFeatureAPI.getInstance();

// Listen for provider ready
api.onProviderReady(details -> {
    System.out.println("Provider is ready!");
});

// Listen for configuration changes
api.onProviderConfigurationChanged(details -> {
    System.out.println("Flags have been updated");
});

// Listen for errors
api.onProviderError(details -> {
    System.err.println("Provider error: " + details.getMessage());
});
```

### Real-Time Updates (SSE Streaming)

Enable Server-Sent Events (SSE) for automatic, real-time flag updates:

```java
FlipswitchConfig config = FlipswitchConfig.builder()
    .baseUrl("https://your-flipswitch-server.com")
    .apiKey("your-api-key")
    .streamingEnabled(true)  // Enable SSE
    .build();

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new FlipswitchProvider(config));

// Flags automatically update when changed on the server
// Listen for updates:
api.onProviderConfigurationChanged(details -> {
    System.out.println("Flags updated from server!");
});
```

When streaming is enabled:
- The provider connects to the server's SSE endpoint after initialization
- Flag changes are pushed from the server instantly
- `PROVIDER_CONFIGURATION_CHANGED` is emitted automatically on updates
- `PROVIDER_STALE` is emitted if no heartbeat is received (configurable timeout)
- Connection automatically reconnects with exponential backoff on failure

### Manual Refresh

To manually refresh flags (without SSE):

```java
FlipswitchProvider provider = new FlipswitchProvider(config);
api.setProviderAndWait(provider);

// Later, refresh flags - emits PROVIDER_CONFIGURATION_CHANGED
provider.refreshFlags(null);

// Or mark cache as stale - emits PROVIDER_STALE
provider.markStale();
```

## Building

```bash
./gradlew build
```

## License

Apache License 2.0
