package com.aryntra.pravah.transport;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Aggregates multiple concrete physical and virtual transports (e.g., TCP, Bluetooth RFCOMM)
 * behind the unified {@link Transport} interface.
 *
 * <p>Architectural Responsibilities (ADR / S8.4):
 * <ul>
 *   <li><b>Delegation &amp; Dispatch:</b> Routes {@link #send(String, byte[])} to the responsible underlying transport based on active connection tracking or scheme prefix.</li>
 *   <li><b>Lifecycle Orchestration:</b> Coordinates {@link #start()} and {@link #stop()} across all registered transports safely and idempotently.</li>
 *   <li><b>Listener Aggregation:</b> Subscribes to child transports and funnels connection opened/closed/data events to a single external {@link TransportListener}.</li>
 *   <li><b>Layer Isolation:</b> Hides concrete transport mechanics (Bluetooth sockets, TCP sockets) from upper routing and protocol layers.</li>
 * </ul>
 * </p>
 *
 * S8.4 - Phase 8: Hybrid Transport Architecture
 */
public class CompositeTransport implements Transport {

    private static final Logger LOGGER = Logger.getLogger(CompositeTransport.class.getName());

    private final List<Transport> transports = new CopyOnWriteArrayList<>();
    private final Map<String, Transport> transportsByScheme = new ConcurrentHashMap<>();
    private final Map<String, Transport> connectionTransportMap = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile TransportListener externalListener;

    /**
     * Internal listener attached to all underlying child transports.
     */
    private final TransportListener childListener = new TransportListener() {
        @Override
        public void onDataReceived(String senderId, byte[] payload) {
            TransportListener l = externalListener;
            if (l != null) {
                l.onDataReceived(senderId, payload);
            }
        }

        @Override
        public void onConnectionOpened(String connectionId) {
            TransportListener l = externalListener;
            if (l != null) {
                l.onConnectionOpened(connectionId);
            }
        }

        @Override
        public void onConnectionClosed(String connectionId) {
            connectionTransportMap.remove(connectionId);
            TransportListener l = externalListener;
            if (l != null) {
                l.onConnectionClosed(connectionId);
            }
        }
    };

    /**
     * Constructs an empty CompositeTransport. Transports can be added via {@link #registerTransport}.
     */
    public CompositeTransport() {
    }

    /**
     * Constructs a CompositeTransport pre-populated with the specified transports.
     *
     * @param initialTransports the transports to aggregate
     */
    public CompositeTransport(Transport... initialTransports) {
        if (initialTransports != null) {
            for (Transport t : initialTransports) {
                if (t != null) {
                    registerTransport(t);
                }
            }
        }
    }

    /**
     * Registers an underlying transport with auto-detected scheme names based on transport identity.
     *
     * @param transport the transport to register (must not be null)
     * @return this composite transport for method chaining
     */
    public synchronized CompositeTransport registerTransport(Transport transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        if (!transports.contains(transport)) {
            transports.add(transport);
            transport.setListener(new ChildTransportForwarder(transport));

            // Infer scheme from name
            String name = transport.getName().toLowerCase();
            if (name.contains("bluetooth") || name.contains("rfcomm") || name.contains("bt")) {
                transportsByScheme.put("bt", transport);
                transportsByScheme.put("bluetooth", transport);
            } else if (name.contains("tcp") || name.contains("lan") || name.contains("wifi")) {
                transportsByScheme.put("tcp", transport);
            }
            transportsByScheme.put(name, transport);

            // If composite is already running, start newly added transport
            if (running.get() && !transport.isRunning()) {
                transport.start();
            }
        }
        return this;
    }

    /**
     * Registers an underlying transport under an explicit scheme name prefix.
     *
     * @param scheme    the scheme prefix (e.g., "tcp", "bt", "bluetooth")
     * @param transport the transport to register (must not be null)
     * @return this composite transport for method chaining
     */
    public synchronized CompositeTransport registerTransport(String scheme, Transport transport) {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        String cleanScheme = scheme.trim().toLowerCase();
        transportsByScheme.put(cleanScheme, transport);
        registerTransport(transport);
        return this;
    }

    @Override
    public String getName() {
        return "CompositeTransport[" + transports.size() + " transports]";
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            LOGGER.fine(() -> "Starting CompositeTransport with " + transports.size() + " child transports");
            for (Transport transport : transports) {
                if (!transport.isRunning()) {
                    transport.start();
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.fine("Stopping CompositeTransport");
            for (Transport transport : transports) {
                if (transport.isRunning()) {
                    transport.stop();
                }
            }
            connectionTransportMap.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void send(String destinationId, byte[] payload) {
        Objects.requireNonNull(destinationId, "destinationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        if (!running.get()) {
            throw new IllegalStateException("Cannot send payload: CompositeTransport is not running");
        }

        Transport targetTransport = resolveTransportForDestination(destinationId);
        if (targetTransport == null) {
            throw new IllegalArgumentException("No registered or active transport can handle destination: " + destinationId);
        }

        targetTransport.send(destinationId, payload);
    }

    @Override
    public void setListener(TransportListener listener) {
        this.externalListener = listener;
    }

    @Override
    public TransportCapabilities getCapabilities() {
        boolean anyReliable = transports.stream().anyMatch(t -> t.getCapabilities().reliable());
        boolean anyConnOriented = transports.stream().anyMatch(t -> t.getCapabilities().connectionOriented());
        boolean allUnicast = transports.stream().allMatch(t -> t.getCapabilities().unicast());
        boolean anyMultiplexing = transports.stream().anyMatch(t -> t.getCapabilities().supportsMultiplexing());

        return new TransportCapabilities(anyReliable, anyConnOriented, allUnicast, anyMultiplexing);
    }

    /**
     * Resolves which transport owns or should handle a given destination/connection ID.
     */
    public Transport resolveTransportForDestination(String destinationId) {
        if (destinationId == null) {
            return null;
        }

        // 1. Direct connection cache
        Transport cached = connectionTransportMap.get(destinationId);
        if (cached != null) {
            return cached;
        }

        // 2. Scheme prefix checks
        if (destinationId.startsWith("bt:") || destinationId.startsWith("bluetooth:")) {
            Transport bt = transportsByScheme.get("bt");
            if (bt == null) bt = transportsByScheme.get("bluetooth");
            if (bt != null) return bt;
        }

        if (destinationId.startsWith("tcp:") || destinationId.startsWith("tcp://")) {
            Transport tcp = transportsByScheme.get("tcp");
            if (tcp != null) return tcp;
        }

        // 3. Registered transports scheme map direct hit
        int colonIdx = destinationId.indexOf(':');
        if (colonIdx > 0) {
            String prefix = destinationId.substring(0, colonIdx).toLowerCase();
            Transport byPrefix = transportsByScheme.get(prefix);
            if (byPrefix != null) {
                return byPrefix;
            }
        }

        // 4. Default fallback: first transport in registered list
        if (!transports.isEmpty()) {
            return transports.get(0);
        }

        return null;
    }

    public List<Transport> getTransports() {
        return Collections.unmodifiableList(transports);
    }

    public Optional<Transport> getTransportForScheme(String scheme) {
        if (scheme == null) return Optional.empty();
        return Optional.ofNullable(transportsByScheme.get(scheme.trim().toLowerCase()));
    }

    private class ChildTransportForwarder implements TransportListener {
        private final Transport sourceTransport;

        ChildTransportForwarder(Transport sourceTransport) {
            this.sourceTransport = sourceTransport;
        }

        @Override
        public void onDataReceived(String senderId, byte[] payload) {
            connectionTransportMap.put(senderId, sourceTransport);
            childListener.onDataReceived(senderId, payload);
        }

        @Override
        public void onConnectionOpened(String connectionId) {
            connectionTransportMap.put(connectionId, sourceTransport);
            childListener.onConnectionOpened(connectionId);
        }

        @Override
        public void onConnectionClosed(String connectionId) {
            childListener.onConnectionClosed(connectionId);
        }
    }
}