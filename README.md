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

## Building

```bash
./gradlew build
```

## License

Apache License 2.0
