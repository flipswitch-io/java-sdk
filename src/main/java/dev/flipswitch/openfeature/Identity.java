package dev.flipswitch.openfeature;

import java.util.Map;

/**
 * Represents a user identity for flag evaluation.
 */
public class Identity {

    private final String id;
    private final Map<String, String> traits;

    public Identity(String id, Map<String, String> traits) {
        this.id = id;
        this.traits = traits;
    }

    public String getId() {
        return id;
    }

    public Map<String, String> getTraits() {
        return traits;
    }
}
