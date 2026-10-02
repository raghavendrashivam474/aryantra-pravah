package com.aryntra.pravah.android

import com.aryntra.pravah.messaging.ApplicationMessage
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.messaging.DefaultApplicationMessagingService
import com.aryntra.pravah.messaging.InMemoryMessageHistoryStore
import com.aryntra.pravah.messaging.MessageHistoryStore
import com.aryntra.pravah.messaging.reliability.DeliveryOutbox
import com.aryntra.pravah.messaging.reliability.InMemoryDeliveryOutbox
import com.aryntra.pravah.messaging.reliability.OutboxState
import com.aryntra.pravah.peer.PeerConnectionCoordinator
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.peer.PeerRegistry
import com.aryntra.pravah.peer.PeerRouter
import com.aryntra.pravah.peer.discovery.DiscoveredPeer
import com.aryntra.pravah.peer.discovery.LanPeerDiscovery
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener
import com.aryntra.pravah.peer.presence.PeerPresenceBridge
import com.aryntra.pravah.peer.presence.PeerPresenceManager
import com.aryntra.pravah.protocol.FrameEncoder
import com.aryntra.pravah.protocol.Message
import com.aryntra.pravah.protocol.MessageEncoder
import com.aryntra.pravah.protocol.MessageType
import com.aryntra.pravah.protocol.PeerState
import com.aryntra.pravah.transport.tcp.TcpTransport
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * S7.4/S7.5 - Android Messaging + Discovery Integration Manager.
 * Thin composition of existing Pravah components. No new architecture.
 */
class PravahAndroidMessagingManager(
    val localPeerId: PeerId,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val discoveryPort: Int = 49152
) : AutoCloseable {

    private val logger = Logger.getLogger(PravahAndroidMessagingManager::class.java.name)

    val transport = TcpTransport(host, port)
    val registry = PeerRegistry()
    val presenceManager = PeerPresenceManager(2000L)
    val presenceBridge = PeerPresenceBridge(registry, presenceManager)
    val coordinator = PeerConnectionCoordinator(transport, registry, presenceBridge)
    val router = PeerRouter(registry, transport)
    val historyStore: MessageHistoryStore = InMemoryMessageHistoryStore()
    val outbox: DeliveryOutbox = InMemoryDeliveryOutbox()
    val messaging = DefaultApplicationMessagingService(
        localPeerId, router, coordinator, historyStore, outbox
    )

    // Lazy discovery engine created when bound port is known
    private var discoveryEngine: LanPeerDiscovery? = null
    private val _discoveredPeers = CopyOnWriteArrayList<DiscoveredPeer>()
    val discoveredPeers: List<DiscoveredPeer> get() = Collections.unmodifiableList(_discoveredPeers)

    private var discoveryListener: ((DiscoveredPeer) -> Unit)? = null

    val boundPort: Int get() = transport.boundPort
    val isRunning: Boolean get() = transport.isRunning

    fun start() {
        transport.start()
        presenceManager.start()
    }

    fun stop() {
        stopDiscovery()
        presenceManager.stop()
        transport.stop()
    }

    @Synchronized
    fun startDiscovery() {
        if (discoveryEngine == null || !discoveryEngine!!.isRunning) {
            val tcpPort = if (boundPort > 0) boundPort else (if (port > 0) port else 8080)
            discoveryEngine = LanPeerDiscovery(localPeerId, tcpPort, discoveryPort, 200L).apply {
                registerListener(object : PeerDiscoveryListener {
                    override fun onPeerDiscovered(peer: DiscoveredPeer) {
                        if (peer.peerId() != localPeerId && _discoveredPeers.none { it.peerId() == peer.peerId() }) {
                            _discoveredPeers.add(peer)
                            presenceBridge.onPeerDiscovered(peer)
                            discoveryListener?.invoke(peer)
                        }
                    }
                })
                start()
            }
        }
    }

    @Synchronized
    fun stopDiscovery() {
        discoveryEngine?.stop()
        discoveryEngine = null
    }

    val isDiscovering: Boolean get() = discoveryEngine?.isRunning ?: false

    fun setDiscoveryListener(listener: (DiscoveredPeer) -> Unit) {
        discoveryListener = listener
    }

    fun connectTo(remoteHost: String, remotePort: Int) {
        transport.connect(remoteHost, remotePort)
    }

    fun sendJoin(remotePeerId: PeerId, connectionId: String) {
        registry.register(remotePeerId, connectionId)
        val joinMsg = Message(
            MessageType.JOIN, localPeerId.value(),
            "join-${localPeerId.value()}-${remotePeerId.value()}", ByteArray(0)
        )
        transport.send(connectionId, FrameEncoder.encode(MessageEncoder.encode(joinMsg)))
    }

    fun replyJoin(remotePeerId: PeerId) {
        val joinMsg = Message(
            MessageType.JOIN, localPeerId.value(),
            "reply-join-${localPeerId.value()}-${remotePeerId.value()}", ByteArray(0)
        )
        router.send(remotePeerId, joinMsg)
    }

    fun getSessionState(peerIdValue: String): PeerState? {
        return coordinator.sessionManager.getPeerState(peerIdValue)
    }

    fun isDelivered(messageId: String): Boolean {
        return outbox.findByMessageId(messageId).map { it.state() == OutboxState.COMPLETED }.orElse(false)
    }

    fun sendText(destination: PeerId, content: String): ApplicationMessage {
        return messaging.sendText(destination, content)
    }

    fun addMessageListener(listener: ApplicationMessageListener) {
        messaging.addListener(listener)
    }

    override fun close() { stop() }
}
