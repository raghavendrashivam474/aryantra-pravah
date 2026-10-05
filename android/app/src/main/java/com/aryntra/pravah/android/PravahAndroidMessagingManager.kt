package com.aryntra.pravah.android

import com.aryntra.pravah.android.bluetooth.AndroidBluetoothRfcommTransport
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.connectivity.PathSelectionPolicy
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry
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
import com.aryntra.pravah.protocol.ProtocolListener
import com.aryntra.pravah.protocol.ProtocolSessionManager
import com.aryntra.pravah.security.identity.CryptographicIdentity
import com.aryntra.pravah.security.identity.IdentityGenerator
import com.aryntra.pravah.security.identity.IdentityKeyPair
import com.aryntra.pravah.security.trust.PeerTrustManager
import com.aryntra.pravah.security.trust.TrustState
import com.aryntra.pravah.transport.CompositeTransport
import com.aryntra.pravah.transport.Transport
import com.aryntra.pravah.transport.tcp.TcpTransport
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

class PravahAndroidMessagingManager(
    val localPeerId: PeerId,
    val host: String = "0.0.0.0",
    val port: Int = 0,
    val discoveryPort: Int = 49152,
    val customTransport: Transport? = null,
    val localMacAddress: String = "02:00:00:00:00:00"
) : AutoCloseable {

    private val logger = Logger.getLogger(PravahAndroidMessagingManager::class.java.name)

    // Security & Cryptographic Identity (SX.4)
    val identityGenerator = IdentityGenerator()
    val localKeyPair: IdentityKeyPair = identityGenerator.generate()
    val localIdentity: CryptographicIdentity = CryptographicIdentity.fromKeyPair(localPeerId, localKeyPair)
    val trustManager: PeerTrustManager = PeerTrustManager()

    val tcpTransport: TcpTransport = TcpTransport(host, port)
    val bluetoothTransport: AndroidBluetoothRfcommTransport = AndroidBluetoothRfcommTransport(localMacAddress)
    val compositeTransport: Transport = customTransport ?: CompositeTransport(tcpTransport, bluetoothTransport)

    val registry = PeerRegistry()
    val presenceManager = PeerPresenceManager(2000L)
    val connectivityRegistry = PeerConnectivityRegistry()
    val presenceBridge = PeerPresenceBridge(registry, presenceManager, connectivityRegistry)
    val sessionManager = ProtocolSessionManager()

    val pathPolicy: PathSelectionPolicy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth", "bt")

    val coordinator = object : PeerConnectionCoordinator(
        compositeTransport,
        registry,
        presenceBridge,
        sessionManager,
        trustManager,
        localKeyPair,
        localIdentity
    ) {
        private val protocolListeners = java.util.concurrent.CopyOnWriteArrayList<ProtocolListener>()

        fun addProtocolListener(listener: ProtocolListener) {
            if (!protocolListeners.contains(listener)) {
                protocolListeners.add(listener)
                rebuildSuperListener()
            }
        }

        fun removeProtocolListener(listener: ProtocolListener) {
            if (protocolListeners.remove(listener)) {
                rebuildSuperListener()
            }
        }

        private fun rebuildSuperListener() {
            super.setProtocolListener(object : ProtocolListener {
                override fun onPeerJoined(peerIdStr: String, message: Message) {
                    val remotePeer = PeerId.of(peerIdStr)
                    cleanOrphanedBtNode(remotePeer)

                    // Trust peer on verified JOIN
                    trustPeer(remotePeer)

                    protocolListeners.forEach { it.onPeerJoined(peerIdStr, message) }

                    if (message != null && message.messageId().startsWith("join-")) {
                        try {
                            logger.info("Auto-replying reciprocal JOIN to $peerIdStr")
                            replyJoin(remotePeer)
                        } catch (e: Exception) {
                            logger.fine("Reciprocal JOIN auto-reply notice: ${e.message}")
                        }
                    }
                }

                override fun onMessageReceived(peerIdStr: String, message: Message) {
                    protocolListeners.forEach { it.onMessageReceived(peerIdStr, message) }
                }

                override fun onPeerLeft(peerIdStr: String, message: Message) {
                    protocolListeners.forEach { it.onPeerLeft(peerIdStr, message) }
                }
            })
        }

        override fun setProtocolListener(listener: ProtocolListener?) {
            if (listener != null) {
                addProtocolListener(listener)
            }
        }
    }

    val router = PeerRouter(registry, compositeTransport, connectivityRegistry, pathPolicy, trustManager)

    val historyStore: MessageHistoryStore = InMemoryMessageHistoryStore()
    val outbox: DeliveryOutbox = InMemoryDeliveryOutbox()
    val messaging = DefaultApplicationMessagingService(
        localPeerId, router, coordinator, historyStore, outbox
    )

    private var discoveryEngine: LanPeerDiscovery? = null
    private val _discoveredPeers = CopyOnWriteArrayList<DiscoveredPeer>()
    val discoveredPeers: List<DiscoveredPeer> get() = Collections.unmodifiableList(_discoveredPeers)
    private var discoveryListener: ((DiscoveredPeer) -> Unit)? = null

    val boundPort: Int get() = tcpTransport.boundPort
    val isRunning: Boolean get() = compositeTransport.isRunning

    fun start() {
        compositeTransport.start()
        presenceManager.start()
    }

    fun stop() {
        stopDiscovery()
        presenceManager.stop()
        compositeTransport.stop()
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

    fun connectToTcp(remoteHost: String, remotePort: Int, remotePeerId: PeerId? = null): String {
        if (remotePeerId != null) {
            trustPeer(remotePeerId)
        }
        tcpTransport.connect(remoteHost, remotePort)
        return "$remoteHost:$remotePort"
    }

    fun connectTo(remoteHost: String, remotePort: Int) {
        connectToTcp(remoteHost, remotePort)
    }

    fun connectToBluetooth(remoteMac: String, remotePeerId: PeerId? = null): String {
        bluetoothTransport.connect(remoteMac)
        val cleanMac = remoteMac.removePrefix("bt:").trim().uppercase()
        val connId = "bt:$cleanMac"
        if (remotePeerId != null) {
            trustPeer(remotePeerId)
            try {
                sendJoin(remotePeerId, connId)
            } catch (e: Exception) {
                logger.fine("Bluetooth sendJoin notice: ${e.message}")
            }
        }
        return connId
    }

    fun sendJoin(remotePeerId: PeerId, connectionId: String) {
        val cleanConn = cleanConnId(connectionId)
        registry.register(remotePeerId, cleanConn)
        trustPeer(remotePeerId)
        val joinMsg = Message(
            MessageType.JOIN, localPeerId.value(),
            "join-${localPeerId.value()}-${remotePeerId.value()}", ByteArray(0)
        )
        compositeTransport.send(cleanConn, FrameEncoder.encode(MessageEncoder.encode(joinMsg)))
    }

    fun replyJoin(remotePeerId: PeerId) {
        trustPeer(remotePeerId)
        val joinMsg = Message(
            MessageType.JOIN, localPeerId.value(),
            "reply-join-${localPeerId.value()}-${remotePeerId.value()}", ByteArray(0)
        )
        try {
            router.send(remotePeerId, joinMsg)
        } catch (e: Exception) {
            logger.fine("Reply JOIN error: ${e.message}")
        }
    }

    fun trustPeer(peerId: PeerId) {
        if (trustManager.getTrustState(peerId) != TrustState.TRUSTED) {
            trustManager.transitionState(peerId, TrustState.TRUSTED)
        }
    }

    fun getSessionState(peerIdValue: String): PeerState? {
        return sessionManager.getPeerState(peerIdValue)
    }

    fun getTrustState(peerId: PeerId): TrustState {
        return trustManager.getTrustState(peerId)
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

    private fun cleanConnId(connId: String): String {
        return if (connId.startsWith("/")) connId.substring(1) else connId
    }

    fun dropTransport(peerId: PeerId, transportScheme: String): Boolean {
        var dropped = false
        connectivityRegistry.lookup(peerId).ifPresent { conn ->
            for (path in conn.activePaths()) {
                if (path.transportName().equals(transportScheme, ignoreCase = true) ||
                    path.endpointAddress().transportScheme().equals(transportScheme, ignoreCase = true)) {
                    val connId = path.connectionId()
                    if (connId != null) {
                        try {
                            if (transportScheme.equals("tcp", ignoreCase = true)) {
                                tcpTransport.disconnect(connId)
                            } else if (transportScheme.equals("bluetooth", ignoreCase = true) ||
                                       transportScheme.equals("bt", ignoreCase = true)) {
                                bluetoothTransport.disconnect(connId)
                            }
                            dropped = true
                        } catch (e: Exception) {
                            logger.warning("Error disconnecting transport $transportScheme: ${e.message}")
                        }
                    }
                    conn.addPath(path.deactivate())
                }
            }
        }
        return dropped
    }

    private fun cleanOrphanedBtNode(authenticatedPeer: PeerId) {
        val orphanedPeers = connectivityRegistry.allConnectivities()
            .map { it.peerId() }
            .filter { it.value().startsWith("remote-bt-") && it != authenticatedPeer }
        for (orphan in orphanedPeers) {
            connectivityRegistry.lookup(orphan).ifPresent { conn ->
                for (path in conn.allPaths()) {
                    if (path.isActive && path.connectionId() != null) {
                        presenceBridge.handlePeerConnected(authenticatedPeer, path.connectionId())
                    }
                }
                connectivityRegistry.removePeer(orphan)
                logger.info("Cleaned orphaned synthetic BT peer: ${orphan.value()} -> migrated to ${authenticatedPeer.value()}")
            }
        }
    }

    override fun close() { stop() }
}