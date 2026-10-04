package com.aryntra.pravah.android

import com.aryntra.pravah.connectivity.PathState
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.peer.discovery.DiscoveredPeer
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener
import com.aryntra.pravah.peer.presence.PeerPresenceManager
import com.aryntra.pravah.peer.presence.PeerPresenceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PravahAndroidDiscoveryTest {

    private val testDiscoveryPort = 49200

    @Test
    fun testScenario1_PeerDiscovery() {
        val peerAId = PeerId.of("android-peer-a")
        val peerBId = PeerId.of("android-peer-b")

        val discoveryA = PravahAndroidDiscoveryManager(peerAId, 9001, testDiscoveryPort, 50L)
        val discoveryB = PravahAndroidDiscoveryManager(peerBId, 9002, testDiscoveryPort, 50L)

        val discoveredLatch = CountDownLatch(1)
        var discoveredPeer: DiscoveredPeer? = null

        discoveryB.registerListener(object : PeerDiscoveryListener {
            override fun onPeerDiscovered(peer: DiscoveredPeer) {
                if (peer.peerId() == peerAId) {
                    discoveredPeer = peer
                    discoveredLatch.countDown()
                }
            }
        })

        discoveryA.start()
        discoveryB.start()

        assertTrue(discoveredLatch.await(4, TimeUnit.SECONDS), "Peer B failed to discover Peer A")
        assertNotNull(discoveredPeer)
        assertEquals(peerAId, discoveredPeer!!.peerId())
        assertEquals(9001, discoveredPeer!!.port())

        discoveryA.stop()
        discoveryB.stop()
    }

    @Test
    fun testScenario2_DuplicateDiscoveryIdempotence() {
        val manager = PravahAndroidDiscoveryManager(
            localPeerId = PeerId.of("local-node"),
            localTcpPort = 8080,
            discoveryPort = testDiscoveryPort + 1
        )

        val peerB = PeerId.of("peer-duplicate-test")
        val discovered1 = DiscoveredPeer(peerB, "192.168.1.50", 9090)
        val discovered2 = DiscoveredPeer(peerB, "192.168.1.50", 9090)
        val discovered3 = DiscoveredPeer(peerB, "192.168.1.50", 9090)

        // Simulate repeated discovery announcements
        manager.presenceBridge.onPeerDiscovered(discovered1)
        manager.presenceBridge.onPeerDiscovered(discovered2)
        manager.presenceBridge.onPeerDiscovered(discovered3)

        val connectivity = manager.connectivityRegistry.lookup(peerB).get()
        // Idempotency: All 3 announcements resolve to identical PathId so only 1 candidate path exists
        assertEquals(1, connectivity.candidatePaths().size)
        assertEquals(1, connectivity.allPaths().size)
    }

    @Test
    fun testScenario3_CandidatePathCreation() {
        val manager = PravahAndroidDiscoveryManager(
            localPeerId = PeerId.of("local-node"),
            localTcpPort = 8080,
            discoveryPort = testDiscoveryPort + 2
        )

        val peerRemote = PeerId.of("remote-peer")
        val discovered = DiscoveredPeer(peerRemote, "10.0.0.5", 7777)

        manager.presenceBridge.onPeerDiscovered(discovered)

        val connectivity = manager.connectivityRegistry.lookup(peerRemote).get()
        assertEquals(1, connectivity.candidatePaths().size)
        assertEquals(0, connectivity.activePaths().size)

        val path = connectivity.candidatePaths()[0]
        assertEquals(PathState.CANDIDATE, path.state())
        assertEquals("tcp", path.transportName())
        assertEquals("10.0.0.5", path.endpointAddress().host())
        assertEquals(7777, path.endpointAddress().port())
    }

    @Test
    fun testScenario4_ConnectionPromotionToActive() {
        val manager = PravahAndroidDiscoveryManager(
            localPeerId = PeerId.of("local-node"),
            localTcpPort = 8080,
            discoveryPort = testDiscoveryPort + 3
        )

        val peerRemote = PeerId.of("peer-promo")
        val discovered = DiscoveredPeer(peerRemote, "10.0.0.10", 8888)

        // 1. Discover -> CANDIDATE
        manager.presenceBridge.onPeerDiscovered(discovered)
        assertEquals(1, manager.connectivityRegistry.lookup(peerRemote).get().candidatePaths().size)

        // 2. Connect -> Promotes to ACTIVE
        manager.onPeerConnected(peerRemote, "conn-promo-123")

        val connectivity = manager.connectivityRegistry.lookup(peerRemote).get()
        assertEquals(1, connectivity.activePaths().size)
        val activePath = connectivity.activePaths()[0]
        assertEquals(PathState.ACTIVE, activePath.state())
        assertEquals("conn-promo-123", activePath.connectionId())
    }

    @Test
    fun testScenario5_DisconnectTransitionToInactive() {
        val manager = PravahAndroidDiscoveryManager(
            localPeerId = PeerId.of("local-node"),
            localTcpPort = 8080,
            discoveryPort = testDiscoveryPort + 4
        )

        val peerRemote = PeerId.of("peer-disconnect-test")
        val discovered = DiscoveredPeer(peerRemote, "10.0.0.12", 9999)

        manager.presenceBridge.onPeerDiscovered(discovered)
        manager.onPeerConnected(peerRemote, "conn-test-456")
        assertEquals(1, manager.connectivityRegistry.lookup(peerRemote).get().activePaths().size)

        // Disconnect
        manager.onPeerDisconnected(peerRemote)

        val connectivity = manager.connectivityRegistry.lookup(peerRemote).get()
        assertEquals(0, connectivity.activePaths().size)
        
        // Inactive paths check via allPaths() filter
        val inactivePaths = connectivity.allPaths().filter { it.state() == PathState.INACTIVE }
        assertEquals(1, inactivePaths.size)
        assertEquals(PathState.INACTIVE, inactivePaths[0].state())
        // PeerId identity is preserved
        assertEquals(peerRemote, connectivity.peerId())
    }

    @Test
    fun testScenario6_PresenceTransitions() {
        val shortTtlMs = 200L
        val manager = PravahAndroidDiscoveryManager(
            localPeerId = PeerId.of("local-node"),
            localTcpPort = 8080,
            discoveryPort = testDiscoveryPort + 5,
            presenceManager = PeerPresenceManager(shortTtlMs)
        )

        val peerRemote = PeerId.of("peer-presence-test")
        val discovered = DiscoveredPeer(peerRemote, "10.0.0.20", 5555)

        // 1. Discovery -> AVAILABLE
        manager.presenceBridge.onPeerDiscovered(discovered)
        val presence1 = manager.presenceManager.getPresence(peerRemote)
        assertNotNull(presence1)
        assertEquals(PeerPresenceState.AVAILABLE, presence1.state())

        // 2. Connect -> CONNECTED
        manager.onPeerConnected(peerRemote, "conn-pres-789")
        val presence2 = manager.presenceManager.getPresence(peerRemote)
        assertNotNull(presence2)
        assertEquals(PeerPresenceState.CONNECTED, presence2.state())

        // 3. Disconnect (within TTL) -> returns to AVAILABLE (since peer was seen on LAN)
        manager.onPeerDisconnected(peerRemote)
        val presence3 = manager.presenceManager.getPresence(peerRemote)
        assertNotNull(presence3)
        assertEquals(PeerPresenceState.AVAILABLE, presence3.state())

        // 4. Start presence manager scheduler and wait for TTL expiry -> UNAVAILABLE
        manager.presenceManager.start()
        Thread.sleep(shortTtlMs + 200L)
        val presence4 = manager.presenceManager.getPresence(peerRemote)
        assertNotNull(presence4)
        assertEquals(PeerPresenceState.UNAVAILABLE, presence4.state())
        manager.presenceManager.stop()
    }
}
