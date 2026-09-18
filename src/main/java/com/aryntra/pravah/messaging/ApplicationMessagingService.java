package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

/**
 * Application messaging boundary contract.
 * Allows sending and receiving high-level messages without exposing transport, sockets, or framing.
 */
public interface ApplicationMessagingService {

    /**
     * Sends an application message to a destination peer.
     *
     * @param destination the recipient peer
     * @param message     the application message to send
     */
    void send(PeerId destination, ApplicationMessage message);

    /**
     * Helper to send text content to a destination peer.
     *
     * @param destination the recipient peer
     * @param content     the text content
     * @return the created ApplicationMessage that was dispatched
     */
    ApplicationMessage sendText(PeerId destination, String content);

    /**
     * Registers a listener for inbound application messages.
     *
     * @param listener the listener to add
     */
    void addListener(ApplicationMessageListener listener);

    /**
     * Unregisters a previously added listener.
     *
     * @param listener the listener to remove
     */
    void removeListener(ApplicationMessageListener listener);
}
