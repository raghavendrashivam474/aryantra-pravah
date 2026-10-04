package com.aryntra.pravah.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.aryntra.pravah.core.PravahException
import com.aryntra.pravah.transport.Transport
import com.aryntra.pravah.transport.TransportCapabilities
import com.aryntra.pravah.transport.TransportListener
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Production Android Bluetooth RFCOMM Transport backed by native Android {@link BluetoothSocket}.
 *
 * <p>Implements the Pravah {@link Transport} contract:
 * <ul>
 *   <li><b>SPP Profile:</b> Standard Serial Port Profile UUID {@code 00001101-0000-1000-8000-00805F9B34FB}.</li>
 *   <li><b>Namespace:</b> {@code bt:{MAC}} format (e.g., {@code bt:AA:BB:CC:DD:EE:01}).</li>
 *   <li><b>Concurrency:</b> Non-blocking background worker threads for stream polling and socket acceptance.</li>
 *   <li><b>Idempotence:</b> Safe repeated calls to {@link #start()} and {@link #stop()}.</li>
 * </ul>
 * </p>
 *
 * S8.6 - Phase 8: Real-World Android RFCOMM Integration
 */
class AndroidBluetoothRfcommTransport(
    val localMacAddress: String = "02:00:00:00:00:00",
    val serviceName: String = "PravahRfcomm",
    val serviceUuid: UUID = PRAVAH_SPP_UUID,
    private val adapterProvider: () -> BluetoothAdapter? = {
        try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (t: Throwable) {
            null
        }
    }
) : Transport {

    companion object {
        val PRAVAH_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val LOGGER = Logger.getLogger(AndroidBluetoothRfcommTransport::class.java.name)
    }

    private val running = AtomicBoolean(false)
    private var listener: TransportListener? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    private val activeConnections = ConcurrentHashMap<String, BluetoothStreamLink>()

    override fun getName(): String = "AndroidBluetoothRfcommTransport[$localMacAddress]"

    override fun getCapabilities(): TransportCapabilities =
        TransportCapabilities.builder()
            .reliable(true)
            .connectionOriented(true)
            .unicast(true)
            .supportsMultiplexing(false)
            .build()

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start() {
        if (running.compareAndSet(false, true)) {
            LOGGER.info("Starting Android Bluetooth RFCOMM Transport...")
            val adapter = try { adapterProvider() } catch (t: Throwable) { null }
            if (adapter != null && adapter.isEnabled) {
                try {
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)
                    acceptThread = Thread({ acceptLoop() }, "Pravah-BtAcceptThread").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    LOGGER.log(Level.WARNING, "Failed to start Bluetooth RFCOMM server socket listener: ${e.message}", e)
                }
            } else {
                LOGGER.info("BluetoothAdapter is null or disabled; transport running in passive/client mode")
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Stopping Android Bluetooth RFCOMM Transport...")
            try {
                serverSocket?.close()
            } catch (ignored: IOException) {}
            serverSocket = null
            acceptThread?.interrupt()
            acceptThread = null

            for ((id, link) in activeConnections) {
                link.close()
                notifyConnectionClosed(id)
            }
            activeConnections.clear()
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun send(destinationId: String, payload: ByteArray) {
        requireNotNull(destinationId) { "destinationId must not be null" }
        requireNotNull(payload) { "payload must not be null" }

        if (!running.get()) {
            throw IllegalStateException("Cannot send: AndroidBluetoothRfcommTransport is not running")
        }

        val cleanDest = if (destinationId.startsWith("bt:")) destinationId else "bt:$destinationId"
        val link = activeConnections[cleanDest]
            ?: throw IllegalArgumentException("No active Bluetooth connection to $destinationId")

        try {
            link.write(payload)
        } catch (e: IOException) {
            disconnect(cleanDest)
            throw PravahException("Failed to send Bluetooth payload to $cleanDest: ${e.message}", e)
        }
    }

    override fun setListener(listener: TransportListener?) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(remoteMac: String) {
        if (!running.get()) {
            throw IllegalStateException("Cannot connect: AndroidBluetoothRfcommTransport is not running")
        }

        val cleanMac = remoteMac.removePrefix("bt:").trim().uppercase()
        val connId = "bt:$cleanMac"

        if (activeConnections.containsKey(connId)) {
            return
        }

        val adapter = try { adapterProvider() } catch (t: Throwable) { null }
            ?: throw IllegalStateException("BluetoothAdapter is unavailable")
        LOGGER.info("[BT-FORENSIC] BT-01: Adapter available") // BT-FORENSIC
        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is turned off")
        }
        LOGGER.info("[BT-FORENSIC] BT-02: Adapter enabled") // BT-FORENSIC

        val device: BluetoothDevice = adapter.getRemoteDevice(cleanMac)
        LOGGER.info("[BT-FORENSIC] BT-03: Device resolved addr=${device.address} name=${device.name}") // BT-FORENSIC
        LOGGER.info("[BT-FORENSIC] BT-04: Bond state=${device.bondState} (10=NONE,11=BONDING,12=BONDED)") // BT-FORENSIC
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(serviceUuid)
        LOGGER.info("[BT-FORENSIC] BT-05: Socket created UUID=$serviceUuid") // BT-FORENSIC

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (ignored: Exception) {}
        LOGGER.info("[BT-FORENSIC] BT-06: Discovery cancelled") // BT-FORENSIC
        LOGGER.info("[BT-FORENSIC] BT-07: socket.connect() starting to $cleanMac ...") // BT-FORENSIC
        try { // BT-FORENSIC
            socket.connect()
            LOGGER.info("[BT-FORENSIC] BT-08: socket.connect() SUCCEEDED") // BT-FORENSIC
        } catch (connectEx: IOException) { // BT-FORENSIC
            LOGGER.info("[BT-FORENSIC] BT-08: socket.connect() FAILED class=${connectEx.javaClass.simpleName} msg=${connectEx.message}") // BT-FORENSIC
            try { socket.close() } catch (_: Exception) {} // BT-FORENSIC cleanup
            throw connectEx // BT-FORENSIC re-throw to preserve original behavior
        } // BT-FORENSIC
        attachActiveSocket(connId, socket)
    }

    @Synchronized
    fun disconnect(connectionId: String) {
        val cleanId = if (connectionId.startsWith("bt:")) connectionId else "bt:$connectionId"
        val link = activeConnections.remove(cleanId)
        if (link != null) {
            link.close()
            notifyConnectionClosed(cleanId)
        }
    }

    val connectionCount: Int
        get() = activeConnections.size

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running.get()) {
            val server = serverSocket ?: break
            try {
                val clientSocket = server.accept()
                if (clientSocket != null) {
                    val remoteMac = try {
                        clientSocket.remoteDevice.address
                    } catch (e: Exception) {
                        "UNKNOWN-" + System.nanoTime()
                    }
                    val connId = "bt:$remoteMac"
                    attachActiveSocket(connId, clientSocket)
                }
            } catch (e: IOException) {
                if (running.get()) {
                    LOGGER.log(Level.FINE, "Bluetooth accept loop terminated: ${e.message}")
                }
                break
            }
        }
    }

    private fun attachActiveSocket(connectionId: String, socket: BluetoothSocket) {
        LOGGER.info("[BT-FORENSIC] BT-09: InputStream opened for $connectionId") // BT-FORENSIC
        LOGGER.info("[BT-FORENSIC] BT-10: OutputStream opened for $connectionId") // BT-FORENSIC
        val link = BluetoothStreamLink(
            id = connectionId,
            socket = socket,
            inputStream = socket.inputStream,
            outputStream = socket.outputStream,
            onDataReceived = { data -> notifyDataReceived(connectionId, data) },
            onDisconnected = { disconnect(connectionId) }
        )

        activeConnections[connectionId] = link
        link.startReader()
        LOGGER.info("[BT-FORENSIC] BT-11: Reader started for $connectionId") // BT-FORENSIC
        notifyConnectionOpened(connectionId)
    }

    private fun notifyDataReceived(senderId: String, payload: ByteArray) {
        listener?.onDataReceived(senderId, payload)
    }

    private fun notifyConnectionOpened(connectionId: String) {
        listener?.onConnectionOpened(connectionId)
    }

    private fun notifyConnectionClosed(connectionId: String) {
        listener?.onConnectionClosed(connectionId)
    }

    private class BluetoothStreamLink(
        val id: String,
        private val socket: BluetoothSocket,
        private val inputStream: InputStream,
        private val outputStream: OutputStream,
        private val onDataReceived: (ByteArray) -> Unit,
        private val onDisconnected: () -> Unit
    ) {
        private val closed = AtomicBoolean(false)
        private var readerThread: Thread? = null

        fun startReader() {
            readerThread = Thread({
                val buffer = ByteArray(4096)
                while (!closed.get()) {
                    try {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            val data = buffer.copyOf(bytesRead)
                            onDataReceived(data)
                        } else if (bytesRead < 0) {
                            java.util.logging.Logger.getLogger("BT-FORENSIC").info("[BT-FORENSIC] BT-12: Reader EOF bytesRead=$bytesRead id=$id") // BT-FORENSIC
                            break
                        }
                    } catch (e: IOException) {
                        java.util.logging.Logger.getLogger("BT-FORENSIC").info("[BT-FORENSIC] BT-12: Reader IOException class=${e.javaClass.simpleName} msg=${e.message} id=$id") // BT-FORENSIC
                        break
                    }
                }
                close()
                onDisconnected()
            }, "Pravah-BtLink-$id").apply {
                isDaemon = true
                start()
            }
        }

        @Synchronized
        fun write(data: ByteArray) {
            if (closed.get()) throw IOException("Bluetooth link is closed")
            outputStream.write(data)
            outputStream.flush()
        }

        @Synchronized
        fun close() {
            if (closed.compareAndSet(false, true)) {
                try { socket.close() } catch (ignored: Exception) {}
                try { inputStream.close() } catch (ignored: Exception) {}
                try { outputStream.close() } catch (ignored: Exception) {}
            }
        }
    }
}