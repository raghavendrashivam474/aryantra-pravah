package com.aryntra.pravah.peer.discovery.bluetooth;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.discovery.DiscoveredAddressCandidate;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers nearby Bluetooth RFCOMM peers and announces local peer reachability.
 * In the core JVM runtime, uses a shared Bluetooth Airspace directory to simulate
 * Bluetooth inquiries without requiring Android SDK or physical hardware.
 *
 * S8.3 - Phase 8: Hybrid Discovery & Addressing
 */
public class BluetoothPeerDiscovery {
    private static final Logger LOGGER = Logger.getLogger(BluetoothPeerDiscovery.class.getName());

    // Thread-safe Airspace Directory for discovery simulation
    private static final Set<BluetoothPeerDiscovery> ACTIVE_DISCOVERY_NODES = ConcurrentHashMap.newKeySet();

    private final PeerId localPeerId;
    private final String localMacAddress;
    private final int localChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Consumer<DiscoveredAddressCandidate>> listeners = new CopyOnWriteArrayList<>();

    public BluetoothPeerDiscovery(PeerId localPeerId, String localMacAddress, int localChannel) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
        Objects.requireNonNull(localMacAddress, "localMacAddress must not be null");
        if (!localMacAddress.toUpperCase().matches("^([0-9A-F]{2}[:-]){5}([0-9A-F]{2})$")) {
            throw new IllegalArgumentException("Invalid Bluetooth MAC format: " + localMacAddress);
        }
        this.localMacAddress = localMacAddress.toUpperCase();
        if (localChannel < 1 || localChannel > 30) {
            throw new IllegalArgumentException("RFCOMM channel must be 1-30, got: " + localChannel);
        }
        this.localChannel = localChannel;
    }

    public void addCandidateListener(Consumer<DiscoveredAddressCandidate> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeCandidateListener(Consumer<DiscoveredAddressCandidate> listener) {
        listeners.remove(listener);
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            ACTIVE_DISCOVERY_NODES.add(this);
            LOGGER.log(Level.INFO, "Bluetooth Peer Discovery started for peer {0} at {1}:{2}",
                    new Object[]{localPeerId.value(), localMacAddress, localChannel});
            
            // Perform simulated inquiry scan across active airspace
            scan();
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            ACTIVE_DISCOVERY_NODES.remove(this);
            LOGGER.log(Level.INFO, "Bluetooth Peer Discovery stopped for peer {0}", localPeerId.value());
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public void scan() {
        if (!running.get()) return;

        for (BluetoothPeerDiscovery otherNode : ACTIVE_DISCOVERY_NODES) {
            if (otherNode != this && otherNode.isRunning() && !otherNode.localPeerId.equals(this.localPeerId)) {
                // Discover the remote node
                DiscoveredAddressCandidate candidateForThis = DiscoveredAddressCandidate.bluetooth(
                        otherNode.localPeerId, otherNode.localMacAddress, otherNode.localChannel
                );
                notifyCandidate(candidateForThis);

                // Reciprocally notify the remote node of this peer
                DiscoveredAddressCandidate candidateForOther = DiscoveredAddressCandidate.bluetooth(
                        this.localPeerId, this.localMacAddress, this.localChannel
                );
                otherNode.notifyCandidate(candidateForOther);
            }
        }
    }

    private void notifyCandidate(DiscoveredAddressCandidate candidate) {
        for (Consumer<DiscoveredAddressCandidate> listener : listeners) {
            try {
                listener.accept(candidate);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in Bluetooth candidate listener", e);
            }
        }
    }
}