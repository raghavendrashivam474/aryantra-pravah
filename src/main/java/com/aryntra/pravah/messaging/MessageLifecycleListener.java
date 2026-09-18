package com.aryntra.pravah.messaging;

/**
 * Listener to receive updates on application message state transitions.
 */
@FunctionalInterface
public interface MessageLifecycleListener {

    /**
     * Invoked when a tracked message transitions to a new state.
     *
     * @param messageId the message being updated
     * @param newState  the new state
     */
    void onStateChanged(String messageId, MessageState newState);
}