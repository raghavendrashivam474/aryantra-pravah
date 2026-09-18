package com.aryntra.pravah.messaging.reliability;

/**
 * Passive listener for observing automatic delivery retry attempts.
 *
 * <p>Implementing classes must handle events quickly and without throwing
 * exceptions. The emitting system will protect delivery processing from
 * any listener-side failures.</p>
 */
@FunctionalInterface
public interface RetryAttemptListener {
    void onRetryAttempt(RetryAttemptEvent event);
}