package dev.flipswitch.openfeature;

/**
 * Exception thrown when there's an error communicating with Flipswitch.
 */
public class FlipswitchException extends Exception {

    public FlipswitchException(String message) {
        super(message);
    }

    public FlipswitchException(String message, Throwable cause) {
        super(message, cause);
    }
}
