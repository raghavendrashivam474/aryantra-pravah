package com.aryntra.pravah.android

import com.aryntra.pravah.messaging.ApplicationMessage
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.messaging.DefaultApplicationMessagingService
import com.aryntra.pravah.messaging.InMemoryMessageHistoryStore
import com.aryntra.pravah.messaging.MessageHistoryStore
import com.aryntra.pravah.messaging.reliability.DeliveryOutbox
import com.aryntra.pravah.messaging.reliability.InMemoryDeliveryOutbox
import com.aryntra.pravah.peer.PeerConnectionCoordinator
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.peer.PeerRegistry
import com.aryntra.pravah.peer.PeerRouter
import com.aryntra.pravah.peer.presence.PeerPresenceBridge
import com.aryntra.pravah.peer.presence.PeerPresenceManager
import com.aryntra.pravah.protocol.FrameEncoder
import com.aryntra.pravah.protocol.Message
import com.aryntra.pravah.protocol.MessageEncoder
import com.aryntra.pravah.protocol.MessageType
import com.aryntra.pravah.transport.tcp.TcpTransport
import java.util.logging.Logger

/**
 * S7.4 - Android Messaging Integration Manager.
 *
 * Composes existing Pravah components to provide end-to-end messaging
 * on the Android runtime. No new protocol, routing, or reliability
 * architecture is introduced. All components are reused from the
 * existing core stack.
 *
 * Component wiring:
 *   TcpTransport
 *     -> PeerConnectionCoordinator (framing + protocol sessions)
 *       -> DefaultApplicationMessagingService (app messages + ACK + retry)
 *         -> PeerRouter (routing via PeerRegistry)
 *           -> TcpTransport (physical delivery)
 *
 * Invariant: PeerId remains the sole application-level addressing primitive.
 * No socket, IP, or connection ID is exposed to callers.
 */
class PravahAndroidMessagingManager(
    val localPeerId: PeerId,
    val host: String = "127.0.0.1",
    val port: Int = 0
) : AutoCloseable {

    private val logger = Logger.getLogger(PravahAndroidMessagingManager::class.java.name)

    // Transport layer (existing TcpTransport - no Android-specific transport)
    val transport = TcpTransport(host, port)

    // Peer identity and presence (existing components)
    val registry = PeerRegistry()
    val presenceManager = PeerPresenceManager(2000L)
    val presenceBridge = PeerPresenceBridge(registry, presenceManager)

    // Protocol coordination (existing - handles framing, session state, JOIN/MESSAGE/LEAVE)
    val coordinator = PeerConnectionCoordinator(transport, registry, presenceBridge)

    // Routing (existing - resolves PeerId to connectionId to transport)
    val router = PeerRouter(registry, transport)

    // Application messaging with in-memory stores (Android-safe, no SQLite-JDBC)
    val historyStore: MessageHistoryStore = InMemoryMessageHistoryStore()
    val outbox: DeliveryOutbox = InMemoryDeliveryOutbox()
    val messaging = DefaultApplicationMessagingService(
        localPeerId, router, coordinator, historyStore, outbox
    )

    val boundPort: Int get() = transport.boundPort
    val isRunning: Boolean get() = transport.isRunning

    /**
     * Starts the transport and presence manager.
     */
    fun start() {
        logger.info("Starting Android Messaging Manager for peer " + localPeerId.value())
        transport.start()
        presenceManager.start()
    }

    /**
     * Stops all managed components.
     */
    fun stop() {
        logger.info("Stopping Android Messaging Manager for peer " + localPeerId.value())
        presenceManager.stop()
        transport.stop()
    }

    /**
     * Establishes a TCP connection to a remote peer.
     */
    fun connectTo(remoteHost: String, remotePort: Int) {
        transport.connect(remoteHost, remotePort)
    }

    /**
     * Performs the outbound JOIN handshake with a remote peer.
     * Registers the remote peer in the local registry and sends a JOIN message.
     *
     * @param remotePeerId the logical identity of the remote peer
     * @param connectionId the transport connection ID (typically "host:port")
     */
    fun sendJoin(remotePeerId: PeerId, connectionId: String) {
        registry.register(remotePeerId, connectionId)
        val joinMsg = Message(
            MessageType.JOIN,
            localPeerId.value(),
            "join-" + localPeerId.value() + "-" + remotePeerId.value(),
            ByteArray(0)
        )
        val framed = FrameEncoder.encode(MessageEncoder.encode(joinMsg))
        transport.send(connectionId, framed)
    }

    /**
     * Sends a reply JOIN back to a peer that initiated the handshake.
     * Uses the PeerRouter which resolves via registry after inbound JOIN.
     */
    fun replyJoin(remotePeerId: PeerId) {
        val joinMsg = Message(
            MessageType.JOIN,
            localPeerId.value(),
            "reply-join-" + localPeerId.value() + "-" + remotePeerId.value(),
            ByteArray(0)
        )
        router.send(remotePeerId, joinMsg)
    }

    /**
     * Sends an application text message to a remote peer.
     * Delegates entirely to the existing DefaultApplicationMessagingService.
     */
    fun sendText(destination: PeerId, content: String): ApplicationMessage {
        return messaging.sendText(destination, content)
    }

    /**
     * Registers a listener for incoming application messages.
     */
    fun addMessageListener(listener: ApplicationMessageListener) {
        messaging.addListener(listener)
    }

    override fun close() {
        stop()
    }
}
