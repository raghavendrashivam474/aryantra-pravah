package com.aryntra.pravah.connectivity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a communication path between peers.
 *
 * <p>A PathId identifies a specific way to reach a peer, distinct from
 * the logical {@link com.aryntra.pravah.peer.PeerId} and any raw connection ID.</p>
 *
 * S6.1 - Phase 6: Connectivity Evolution
 */
public final class PathId {

    private final String value;

    private PathId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("PathId value must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("PathId value must not be blank");
        }
        this.value = trimmed;
    }

    public static PathId of(String value) {
        return new PathId(value);
    }

    public static PathId generate() {
        return new PathId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathId pathId = (PathId) o;
        return value.equals(pathId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "PathId[" + value + "]";
    }
}
