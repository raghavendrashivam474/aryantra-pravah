package com.aryntra.pravah.android

import com.aryntra.pravah.connectivity.PeerConnectivityRegistry
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.peer.PeerRegistry
import com.aryntra.pravah.peer.discovery.DiscoveredPeer
import com.aryntra.pravah.peer.discovery.LanPeerDiscovery
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener
import com.aryntra.pravah.peer.presence.PeerPresenceBridge
import com.aryntra.pravah.peer.presence.PeerPresenceManager
import java.util.Collections
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Android LAN Peer Discovery Coordinator.
 *
 * Encapsulates UDP broadcast discovery and coordinates with:
 * 1. PeerPresenceBridge (Presence updates)
 * 2. PeerConnectivityRegistry (Deterministic CANDIDATE path creation)
 * 3. PeerRegistry (Logical peer registration)
 *
 * Guarantees:
 * - PeerId remains logical (WHO), separate from EndpointAddress (WHERE).
 * - Discovery produces CANDIDATE path, never ACTIVE.
 * - Repeated discovery announcements are idempotent.
 */
class PravahAndroidDiscoveryManager(
    val localPeerId: PeerId,
    val localTcpPort: Int,
    val discoveryPort: Int = 49152,
    val broadcastIntervalMs: Long = 100L,
    val registry: PeerRegistry = PeerRegistry(),
    val presenceManager: PeerPresenceManager = PeerPresenceManager(2000L),
    val connectivityRegistry: PeerConnectivityRegistry = PeerConnectivityRegistry()
) {
    private val logger = Logger.getLogger(PravahAndroidDiscoveryManager::class.java.name)

    // Bridge coordinating Discovery -> Presence -> Registry -> Connectivity
    val presenceBridge: PeerPresenceBridge = PeerPresenceBridge(registry, presenceManager, connectivityRegistry)

    // Underlying LAN peer discovery engine
    private val discoveryEngine: LanPeerDiscovery = LanPeerDiscovery(
        localPeerId,
        localTcpPort,
        discoveryPort,
        broadcastIntervalMs
    )

    private val customListeners = Collections.synchronizedList(mutableListOf<PeerDiscoveryListener>())

    init {
        // Register the presence bridge as the authoritative discovery listener
        discoveryEngine.registerListener(presenceBridge)

        // Also notify any custom Android-level listeners
        discoveryEngine.registerListener(object : PeerDiscoveryListener {
            override fun onPeerDiscovered(peer: DiscoveredPeer) {
                synchronized(customListeners) {
                    for (listener in customListeners) {
                        try {
                            listener.onPeerDiscovered(peer)
                        } catch (e: Exception) {
                            logger.log(Level.WARNING, "Error in custom peer discovery listener", e)
                        }
                    }
                }
            }
        })
    }

    /**
     * Starts LAN peer discovery broadcasting and listening.
     */
    @Synchronized
    fun start() {
        if (!discoveryEngine.isRunning) {
            logger.info("Starting Android Peer Discovery on UDP port $discoveryPort...")
            presenceManager.start()
            discoveryEngine.start()
        }
    }

    /**
     * Stops LAN peer discovery and releases UDP socket resources.
     */
    @Synchronized
    fun stop() {
        if (discoveryEngine.isRunning) {
            logger.info("Stopping Android Peer Discovery...")
            discoveryEngine.stop()
            presenceManager.stop()
        }
    }

    val isRunning: Boolean
        get() = discoveryEngine.isRunning

    fun registerListener(listener: PeerDiscoveryListener) {
        customListeners.add(listener)
    }

    fun unregisterListener(listener: PeerDiscoveryListener) {
        customListeners.remove(listener)
    }

    /**
     * Connect promotion hook: updates Presence, Registry, and promotes CANDIDATE path to ACTIVE.
     */
    fun onPeerConnected(peerId: PeerId, connectionId: String) {
        presenceBridge.handlePeerConnected(peerId, connectionId)
    }

    /**
     * Disconnect hook: updates Presence, Registry, and deactivates ACTIVE paths to INACTIVE.
     */
    fun onPeerDisconnected(peerId: PeerId) {
        presenceBridge.handlePeerDisconnected(peerId)
    }
}
