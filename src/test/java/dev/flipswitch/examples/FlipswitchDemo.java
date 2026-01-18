package dev.flipswitch.examples;

import dev.flipswitch.FlagEvaluation;
import dev.flipswitch.FlipswitchProvider;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;

import java.util.List;

/**
 * Sample application demonstrating Flipswitch integration with real-time SSE support.
 *
 * <p>Run this demo with:
 * <pre>{@code
 * mvn compile test-compile exec:java -Dexec.mainClass="dev.flipswitch.examples.FlipswitchDemo" \
 *     -Dexec.args="your-api-key"
 * }</pre>
 */
public class FlipswitchDemo {

    private static FlipswitchProvider provider;
    private static MutableContext context;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FlipswitchDemo <api-key>");
            System.exit(1);
        }

        String apiKey = args[0];

        System.out.println("Flipswitch Java SDK Demo");
        System.out.println("========================\n");

        // API key is required, all other options have sensible defaults
        provider = FlipswitchProvider.builder(apiKey).build();

        // Register the provider with OpenFeature
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        try {
            api.setProviderAndWait(provider);
        } catch (Exception e) {
            System.err.println("Failed to connect to Flipswitch: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Connected! SSE Status: " + provider.getSseStatus());

        // Create evaluation context with user information
        context = new MutableContext("user-123");
        context.add("email", "user@example.com");
        context.add("plan", "premium");
        context.add("country", "SE");

        // Add a listener for flag changes - re-evaluate and show new value
        provider.addFlagChangeListener(event -> {
            String flagKey = event.getFlagKey();
            System.out.println("\n*** Flag changed: " + (flagKey != null ? flagKey : "all flags") + " ***");

            if (flagKey != null) {
                // Re-evaluate the specific flag that changed
                FlagEvaluation eval = provider.evaluateFlag(flagKey, context);
                if (eval != null) {
                    printFlag(eval);
                }
            } else {
                // Bulk invalidation - re-evaluate all flags
                printAllFlags();
            }
            System.out.println();
        });

        System.out.println("\nEvaluating flags for user: user-123");
        System.out.println("Context: email=user@example.com, plan=premium, country=SE\n");

        printAllFlags();

        // Keep the application running to demonstrate real-time updates
        System.out.println("\n--- Listening for real-time flag updates (Ctrl+C to exit) ---");
        System.out.println("Change a flag in the Flipswitch dashboard to see it here!\n");

        // Keep running for 5 minutes to demonstrate real-time updates
        Thread.sleep(300000);

        // Cleanup
        provider.shutdown();
        System.out.println("\nDemo complete!");
    }

    private static void printAllFlags() {
        List<FlagEvaluation> flags = provider.evaluateAllFlags(context);

        if (flags.isEmpty()) {
            System.out.println("No flags found.");
            return;
        }

        System.out.println("Flags (" + flags.size() + "):");
        System.out.println("-".repeat(60));

        for (FlagEvaluation flag : flags) {
            printFlag(flag);
        }
    }

    private static void printFlag(FlagEvaluation flag) {
        String variant = flag.getVariant() != null ? ", variant=" + flag.getVariant() : "";
        System.out.printf("  %-30s (%s) = %s%n",
                flag.getKey(),
                flag.getValueType(),
                flag.getValueAsString());
        System.out.printf("    └─ reason=%s%s%n", flag.getReason(), variant);
    }
}
