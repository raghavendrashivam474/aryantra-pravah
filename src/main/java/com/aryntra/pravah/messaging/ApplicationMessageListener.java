package com.aryntra.pravah.messaging;

/**
 * Consumer callback for application-level message events.
 */
@FunctionalInterface
public interface ApplicationMessageListener {

    /**
     * Invoked when an application message is received from a peer.
     *
     * @param message the received application message
     */
    void onMessage(ApplicationMessage message);
}
