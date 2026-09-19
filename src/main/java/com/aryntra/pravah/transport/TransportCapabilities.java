package com.aryntra.pravah.transport;

import java.util.Objects;

/**
 * Immutable description of the technical capabilities and behavioral contracts
 * of a physical or virtual transport mechanism.
 *
 * <p>Avoids over-generalization and models only the core properties needed
 * for transport evaluation and routing decisions.</p>
 *
 * S6.3 - Phase 6: Transport Capability Model
 */
public record TransportCapabilities(
        boolean reliable,
        boolean connectionOriented,
        boolean unicast,
        boolean supportsMultiplexing
) {

    /**
     * Standard capabilities for a TCP transport.
     * Guaranteed in-order stream delivery, connection-oriented, unicast, no native multiplexing.
     */
    public static TransportCapabilities tcp() {
        return new TransportCapabilities(true, true, true, false);
    }

    /**
     * Standard capabilities for an unconfirmed LAN UDP broadcast transport.
     * Unreliable, connectionless, non-unicast broadcast.
     */
    public static TransportCapabilities lanBroadcast() {
        return new TransportCapabilities(false, false, false, false);
    }

    /**
     * Standard default capabilities (safe conservative fallback).
     */
    public static TransportCapabilities defaultCapabilities() {
        return tcp();
    }

    /**
     * Builder for constructing custom transport capabilities.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean reliable = true;
        private boolean connectionOriented = true;
        private boolean unicast = true;
        private boolean supportsMultiplexing = false;

        public Builder reliable(boolean reliable) {
            this.reliable = reliable;
            return this;
        }

        public Builder connectionOriented(boolean connectionOriented) {
            this.connectionOriented = connectionOriented;
            return this;
        }

        public Builder unicast(boolean unicast) {
            this.unicast = unicast;
            return this;
        }

        public Builder supportsMultiplexing(boolean supportsMultiplexing) {
            this.supportsMultiplexing = supportsMultiplexing;
            return this;
        }

        public TransportCapabilities build() {
            return new TransportCapabilities(reliable, connectionOriented, unicast, supportsMultiplexing);
        }
    }
}
