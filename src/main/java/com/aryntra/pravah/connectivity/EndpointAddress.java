package com.aryntra.pravah.connectivity;

import java.util.Objects;

/**
 * Value object representing a network or transport-level endpoint address.
 *
 * <p>An EndpointAddress encapsulates WHERE a peer can be contacted without
 * dictating the physical transport mechanism or requiring an active connection.</p>
 *
 * S6.1 - Phase 6: Connectivity Evolution
 */
public record EndpointAddress(
        String host,
        int port,
        String transportScheme
) {

    public EndpointAddress {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 0-65535, got: " + port);
        }
        if (transportScheme == null || transportScheme.isBlank()) {
            transportScheme = "tcp";
        }
        transportScheme = transportScheme.trim().toLowerCase();
    }

    /**
     * Factory for standard TCP/IP endpoint (host + port).
     */
    public static EndpointAddress tcp(String host, int port) {
        return new EndpointAddress(host, port, "tcp");
    }

    /**
     * Factory for generic endpoint with custom scheme.
     */
    public static EndpointAddress of(String transportScheme, String host, int port) {
        return new EndpointAddress(host, port, transportScheme);
    }

    /**
     * Returns a formatted URI-like representation of this endpoint address.
     */
    public String toUriString() {
        if (port > 0) {
            return transportScheme + "://" + host + ":" + port;
        }
        return transportScheme + "://" + host;
    }

    @Override
    public String toString() {
        return toUriString();
    }
}
