package com.aryntra.pravah.protocol;

/**
 * Contract for receiving high-level protocol and chat semantics events.
 * Higher application layers implement this interface to consume validated
 * domain events without dealing with wire bytes, framing, or parsing.
 */
public interface ProtocolListener {

    /**
     * Invoked when a peer successfully joins the logical communication session.
     *
     * @param peerId  the logical identity of the joining peer
     * @param message the original validated JOIN message
     */
    void onPeerJoined(String peerId, Message message);

    /**
     * Invoked when an application payload message is received from a joined peer.
     *
     * @param peerId  the logical identity of the sending peer
     * @param message the original validated MESSAGE
     */
    void onMessageReceived(String peerId, Message message);

    /**
     * Invoked when a peer departs from the logical communication session.
     *
     * @param peerId  the logical identity of the departing peer
     * @param message the original validated LEAVE message
     */
    void onPeerLeft(String peerId, Message message);
}