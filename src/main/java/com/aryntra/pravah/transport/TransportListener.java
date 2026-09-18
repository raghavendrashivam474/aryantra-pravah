package com.aryntra.pravah.transport;

/**
 * Contract for receiving raw frame data and transport events from a transport implementation.
 */
@FunctionalInterface
public interface TransportListener {
    void onDataReceived(String senderId, byte[] payload);
}