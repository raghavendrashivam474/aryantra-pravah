package com.aryntra.pravah.transport;

/**
 * Minimal strict contract for any physical or virtual transport mechanism in Pravah.
 * Higher layers depend only on this contract, never on concrete network classes.
 */
public interface Transport {

    String getName();

    void start();

    void stop();

    boolean isRunning();

    void send(String destinationId, byte[] payload);

    void setListener(TransportListener listener);
}