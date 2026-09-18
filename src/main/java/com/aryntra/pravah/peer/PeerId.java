package com.aryntra.pravah.peer;

import java.util.Objects;
import java.util.UUID;

/**
 * Logical identity of a peer in the Pravah network.
 *
 * A PeerId represents WHO is communicating, not HOW.
 * It is deliberately decoupled from transport connection IDs,
 * IP addresses, ports, or socket references.
 *
 * S3.1 - Phase 3: Peer Identity
 */
public final class PeerId {

    private final String value;

    private PeerId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("PeerId value must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("PeerId value must not be blank");
        }
        this.value = trimmed;
    }

    /**
     * Creates a PeerId from an explicit string value.
     */
    public static PeerId of(String value) {
        return new PeerId(value);
    }

    /**
     * Generates a new random PeerId using UUID.
     */
    public static PeerId generate() {
        return new PeerId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerId peerId = (PeerId) o;
        return value.equals(peerId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "PeerId[" + value + "]";
    }
}