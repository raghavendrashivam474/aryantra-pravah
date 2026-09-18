package com.aryntra.pravah.transport;

/**
 * Contract for receiving raw frame data and transport lifecycle events from a transport implementation.
 *
 * <p>Uses default methods for lifecycle hooks so that functional lambda consumers
 * implementing only {@link #onDataReceived(String, byte[])} remain 100% compatible.
 */
@FunctionalInterface
public interface TransportListener {

    /**
     * Invoked when raw byte payload data is received from a connection.
     *
     * @param senderId the transport-specific connection/sender identifier
     * @param payload  the received raw bytes
     */
    void onDataReceived(String senderId, byte[] payload);

    /**
     * Invoked when a new transport connection is established (inbound or outbound).
     *
     * @param connectionId the unique identifier for the established connection
     */
    default void onConnectionOpened(String connectionId) {
        // Default no-op for backward compatibility
    }

    /**
     * Invoked when a transport connection is closed, disconnected, or dropped.
     *
     * @param connectionId the identifier of the closed connection
     */
    default void onConnectionClosed(String connectionId) {
        // Default no-op for backward compatibility
    }
}