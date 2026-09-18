# ADR-004: Transport Connection Lifecycle and Peer Coordination

## Status
Accepted

## Context
In `v0.3.5`, `TransportListener` defines a single abstract method:
```java
void onDataReceived(String senderId, byte[] payload);
```

While this enables raw frame ingress, higher-level peer coordination layers cannot observe when physical TCP connections are opened or closed.

## Decision

1. Extend `TransportListener` with Default Lifecycle Methods:
   ```Java
    default void onConnectionOpened(String connectionId) {}
    default void onConnectionClosed(String connectionId) {}
   ```
   
    Using Java default methods preserves @FunctionalInterface eligibility and backward compatibility.

2. Notify Lifecycle Events in `TcpTransport`:
Fire `onConnectionOpened` on accept/connect and `onConnectionClosed` on teardown.

3. Introduce `PeerConnectionCoordinator`:
To bridge `connectionId ↔ PeerId`, route incoming protocol frames, and coordinate presence state.

## Consequences

- 100% backward compatibility with all existing tests.
- Transport remains PeerId-agnostic.
- Real-time CONNECTED and AVAILABLE presence transitions.
