package com.aryntra.pravah.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TransportContractVerificationTest {

    /**
     * Test double satisfying the Transport contract purely in memory.
     * Demonstrates that higher layers do not need concrete networking classes.
     */
    static class InMemoryTransport implements Transport {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private TransportListener listener;

        @Override
        public String getName() {
            return "in-memory-loopback";
        }

        @Override
        public void start() {
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void send(String destinationId, byte[] payload) {
            if (!isRunning()) {
                throw new IllegalStateException("Transport is not running");
            }
            if (listener != null) {
                // In-memory loopback dispatch simulating remote peer delivery
                listener.onDataReceived(destinationId, payload);
            }
        }

        @Override
        public void setListener(TransportListener listener) {
            this.listener = listener;
        }
    }

    @Test
    @DisplayName("S0.4 Verification: Consumer depends purely on Transport contract")
    void testTransportContractDecoupling() {
        Transport transport = new InMemoryTransport();
        assertEquals("in-memory-loopback", transport.getName());
        assertFalse(transport.isRunning());

        transport.start();
        assertTrue(transport.isRunning());

        AtomicReference<String> receivedSender = new AtomicReference<>();
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        transport.setListener((senderId, payload) -> {
            receivedSender.set(senderId);
            receivedMessage.set(new String(payload, StandardCharsets.UTF_8));
        });

        byte[] payload = "Hello Pravah".getBytes(StandardCharsets.UTF_8);
        transport.send("peer-test-1", payload);

        assertEquals("peer-test-1", receivedSender.get());
        assertEquals("Hello Pravah", receivedMessage.get());

        transport.stop();
        assertFalse(transport.isRunning());
    }
}