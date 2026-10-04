package com.aryntra.pravah.android

import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.connectivity.PathState
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.transport.Transport
import com.aryntra.pravah.transport.TransportListener
import com.aryntra.pravah.transport.tcp.TcpTransport
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Android Networking coordinator managing physical transport connections
 * and mapping active sockets directly into Pravah Connectivity Paths.
 *
 * Invariant: Never leaks java.net.Socket or android.net classes to application code.
 */
class PravahAndroidNetworkManager(
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val connectivityRegistry: PeerConnectivityRegistry? = null,
    val customTransport: Transport? = null
) {
    private val logger = Logger.getLogger(PravahAndroidNetworkManager::class.java.name)

    // The underlying Pravah transport contract implementation
    val transport: Transport = customTransport ?: TcpTransport(host, port)

    // Connection tracking
    private val activeConnections = ConcurrentHashMap.newKeySet<String>()
    private val connectionListeners = Collections.synchronizedList(mutableListOf<TransportListener>())

    init {
        // Wire the transport listener to track connection lifecycle and update connectivity
        transport.setListener(object : TransportListener {
            override fun onDataReceived(senderId: String, payload: ByteArray) {
                notifyDataReceived(senderId, payload)
            }

            override fun onConnectionOpened(connectionId: String) {
                logger.info("PravahAndroidNetwork: Connection opened -> $connectionId")
                activeConnections.add(connectionId)
                notifyConnectionOpened(connectionId)
            }

            override fun onConnectionClosed(connectionId: String) {
                logger.info("PravahAndroidNetwork: Connection closed -> $connectionId")
                activeConnections.remove(connectionId)
                
                // If connectivity registry is present, deactivate any paths associated with this connection
                connectivityRegistry?.lookupByConnectionId(connectionId)?.ifPresent { peerConn ->
                    for (path in peerConn.activePaths()) {
                        if (path.connectionId() == connectionId) {
                            peerConn.addPath(path.deactivate())
                            logger.fine("Deactivated path ${path.pathId()} for peer ${path.peerId().value()}")
                        }
                    }
                }
                
                notifyConnectionClosed(connectionId)
            }
        })
    }

    /**
     * Starts the underlying network transport.
     */
    @Synchronized
    fun start() {
        if (!transport.isRunning) {
            logger.info("Starting Android network transport...")
            transport.start()
        }
    }

    /**
     * Stops the transport and cleans up all active network resources.
     */
    @Synchronized
    fun stop() {
        if (transport.isRunning) {
            logger.info("Stopping Android network transport...")
            transport.stop()
            activeConnections.clear()
        }
    }

    /**
     * Initiates an outbound TCP connection to a remote endpoint.
     */
    fun connect(remoteHost: String, remotePort: Int) {
        if (!transport.isRunning) {
            throw IllegalStateException("Cannot connect: transport is not running")
        }
        if (transport is TcpTransport) {
            transport.connect(remoteHost, remotePort)
        } else {
            throw UnsupportedOperationException("Underlying transport does not support explicit connect")
        }
    }

    /**
     * Connects to a peer using an existing CANDIDATE ConnectivityPath and promotes it to ACTIVE upon success.
     */
    fun connectPath(peerId: PeerId, candidatePath: ConnectivityPath) {
        require(candidatePath.isCandidate) { "Path must be in CANDIDATE state" }
        val endpoint = candidatePath.endpointAddress()
        
        connect(endpoint.host(), endpoint.port())
        
        // Find newly opened connection matching this endpoint
        val connectionId = activeConnections.firstOrNull { it.contains("${endpoint.port()}") } 
            ?: activeConnections.firstOrNull()
            
        if (connectionId != null && connectivityRegistry != null) {
            val activePath = candidatePath.activate(connectionId)
            connectivityRegistry.registerPath(peerId, activePath)
            logger.info("Promoted path ${activePath.pathId()} to ACTIVE with connection $connectionId")
        }
    }

    /**
     * Sends raw framed data over an established connection.
     */
    fun send(connectionId: String, payload: ByteArray) {
        transport.send(connectionId, payload)
    }

    val isRunning: Boolean
        get() = transport.isRunning

    val boundPort: Int
        get() = if (transport is TcpTransport) transport.boundPort else port

    val connectionCount: Int
        get() = activeConnections.size

    fun addListener(listener: TransportListener) {
        connectionListeners.add(listener)
    }

    fun removeListener(listener: TransportListener) {
        connectionListeners.remove(listener)
    }

    private fun notifyDataReceived(senderId: String, payload: ByteArray) {
        synchronized(connectionListeners) {
            for (l in connectionListeners) {
                try {
                    l.onDataReceived(senderId, payload)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Error in data listener", e)
                }
            }
        }
    }

    private fun notifyConnectionOpened(connectionId: String) {
        synchronized(connectionListeners) {
            for (l in connectionListeners) {
                try {
                    l.onConnectionOpened(connectionId)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Error in connection opened listener", e)
                }
            }
        }
    }

    private fun notifyConnectionClosed(connectionId: String) {
        synchronized(connectionListeners) {
            for (l in connectionListeners) {
                try {
                    l.onConnectionClosed(connectionId)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Error in connection closed listener", e)
                }
            }
        }
    }
}
