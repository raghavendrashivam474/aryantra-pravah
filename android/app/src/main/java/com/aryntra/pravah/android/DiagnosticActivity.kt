package com.aryntra.pravah.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var connectedPeerId: PeerId? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvMultiPathTopology: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnConnectTcp: Button
    private lateinit var btnConnectBt: Button
    private lateinit var btnSimulateDrop: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        requestPermissionsIfRequired()

        tvStatus = findViewById(R.id.tvStatus)
        tvMultiPathTopology = findViewById(R.id.tvMultiPathTopology)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        etMessage = findViewById(R.id.etMessage)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnConnectTcp = findViewById(R.id.btnConnectTcp)
        btnConnectBt = findViewById(R.id.btnConnectBt)
        btnSimulateDrop = findViewById(R.id.btnSimulateDrop)
        btnSend = findViewById(R.id.btnSend)

        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        log("Pravah Hybrid Diagnostic Node initialized")
        log("PeerId: ${peerId.value()}")
        updateDashboard()

        btnStart.setOnClickListener { startRuntime() }
        btnStop.setOnClickListener { stopRuntime() }
        btnDiscover.setOnClickListener { toggleDiscovery() }
        btnConnectTcp.setOnClickListener { connectTcp() }
        btnConnectBt.setOnClickListener { showBluetoothDeviceChooser() }
        btnSimulateDrop.setOnClickListener { simulateTcpDrop() }
        btnSend.setOnClickListener { sendMessage() }

        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                val sender = msg.sender()
                bindSession(sender)
                log("< RECV [${sender.value()}]: ${msg.content()}")
                updateDashboard()
            }
        })

        manager.setDiscoveryListener { peer ->
            handler.post {
                bindSession(peer.peerId())
                log("DISCOVERED: ${peer.peerId().value()} @ ${peer.hostAddress()}:${peer.port()}")
                btnConnectTcp.isEnabled = true
                btnConnectBt.isEnabled = true
                updateDashboard()
            }
        }
    }

    private fun requestPermissionsIfRequired() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
    }

    private fun bindSession(peer: PeerId) {
        connectedPeerId = peer
        btnSend.isEnabled = true
        etMessage.isEnabled = true
        btnSimulateDrop.isEnabled = true
        btnConnectTcp.isEnabled = true
        btnConnectBt.isEnabled = true
    }

    private fun startRuntime() {
        backgroundExecutor.execute {
            try {
                manager.start()
                handler.post {
                    log("Runtime STARTED on TCP port ${manager.boundPort}")
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true
                    btnDiscover.isEnabled = true
                    btnConnectBt.isEnabled = true
                    updateDashboard()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("ERROR starting: $err") }
            }
        }
    }

    private fun stopRuntime() {
        backgroundExecutor.execute {
            try {
                manager.stop()
                handler.post {
                    log("Runtime STOPPED")
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    btnDiscover.isEnabled = false
                    btnConnectTcp.isEnabled = false
                    btnConnectBt.isEnabled = false
                    btnSimulateDrop.isEnabled = false
                    btnSend.isEnabled = false
                    etMessage.isEnabled = false
                    connectedPeerId = null
                    updateDashboard()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("ERROR stopping: $err") }
            }
        }
    }

    private fun toggleDiscovery() {
        backgroundExecutor.execute {
            if (manager.isDiscovering) {
                manager.stopDiscovery()
                handler.post {
                    log("Discovery STOPPED")
                    btnDiscover.text = "DISCOVER"
                    updateDashboard()
                }
            } else {
                manager.startDiscovery()
                handler.post {
                    log("Discovery STARTED on UDP ${manager.discoveryPort}")
                    btnDiscover.text = "STOP DISC"
                    updateDashboard()
                }
            }
        }
    }

    private fun connectTcp() {
        val peers = manager.discoveredPeers
        if (peers.isEmpty()) {
            log("No peers discovered yet via UDP. Please press DISCOVER first.")
            return
        }

        val target = peers.last()
        val host = target.hostAddress()
        val port = target.port()
        val targetPeerId = target.peerId()

        log("Connecting TCP to ${targetPeerId.value()} @ $host:$port...")
        backgroundExecutor.execute {
            try {
                val connId = manager.connectToTcp(host, port, targetPeerId)
                Thread.sleep(150)
                manager.sendJoin(targetPeerId, connId)
                handler.post {
                    log("TCP JOIN sent to ${targetPeerId.value()}")
                    bindSession(targetPeerId)
                    updateDashboard()
                }
                Thread.sleep(200)
                manager.replyJoin(targetPeerId)
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("TCP CONNECT ERROR: $err") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceChooser() {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (t: Throwable) { null }
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is turned OFF or unavailable on this device")
            return
        }

        val bondedDevices: Set<BluetoothDevice> = try { adapter.bondedDevices ?: emptySet() } catch (e: Exception) { emptySet() }
        val deviceList = bondedDevices.toList()

        if (deviceList.isEmpty()) {
            log("No paired Bluetooth devices found. Please pair both Android devices in Bluetooth Settings first.")
            return
        }

        val names = deviceList.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Paired Bluetooth Peer")
            .setItems(names) { _, which ->
                val selectedDevice = deviceList[which]
                connectBtDevice(selectedDevice.address)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectBtDevice(macAddress: String) {
        val peers = manager.discoveredPeers
        val targetPeerId = connectedPeerId ?: if (peers.isNotEmpty()) peers.last().peerId() else PeerId.of("remote-bt-node")

        log("Connecting Bluetooth RFCOMM to $macAddress for ${targetPeerId.value()}...")
        backgroundExecutor.execute {
            try {
                val connId = manager.connectToBluetooth(macAddress, targetPeerId)
                handler.post {
                    log("Bluetooth path established: $connId")
                    bindSession(targetPeerId)
                    updateDashboard()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("BT CONNECT NOTICE: $err") }
            }
        }
    }

    private fun simulateTcpDrop() {
        val peer = connectedPeerId ?: return
        backgroundExecutor.execute {
            try {
                manager.connectivityRegistry.lookup(peer).ifPresent { conn ->
                    for (path in conn.activePaths()) {
                        if (path.transportName().equals("tcp", ignoreCase = true)) {
                            conn.addPath(path.deactivate())
                            handler.post {
                                log("SIMULATED TCP FAILURE: Path ${path.pathId().value()} DEACTIVATED")
                                updateDashboard()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post { log("DROP ERROR: ${e.message}") }
            }
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        val peer = connectedPeerId
        if (text.isEmpty() || peer == null) return

        backgroundExecutor.execute {
            try {
                val activeConn = try { manager.router.resolveConnectionId(peer) } catch (e: Exception) { "unresolved" }
                val msg = manager.sendText(peer, text)
                handler.post {
                    log("> SENT via [$activeConn]: $text")
                    etMessage.setText("")
                    updateDashboard()
                }
                var delivered = false
                for (i in 1..15) {
                    Thread.sleep(200)
                    if (manager.isDelivered(msg.messageId())) {
                        delivered = true
                        break
                    }
                }
                handler.post {
                    if (delivered) {
                        log("ACK received -> DELIVERED")
                    } else {
                        log("Dispatched to router -> Pending ACK")
                    }
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("SEND ERROR: $err") }
            }
        }
    }

    private fun updateDashboard() {
        val state = if (manager.isRunning) "RUNNING" else "STOPPED"
        val port = if (manager.isRunning) manager.boundPort.toString() else "-"
        val disc = if (manager.isDiscovering) "ACTIVE" else "OFF"
        val peerStr = connectedPeerId?.value() ?: "NONE"
        val sessionStr = connectedPeerId?.let { manager.getSessionState(it.value()) ?: PeerState.JOINED } ?: "-"

        tvStatus.text = "Status: $state | TCP Port: $port\nPeerId: ${manager.localPeerId.value()}\nDiscovery: $disc | Remote: $peerStr [$sessionStr]"

        val topoSb = StringBuilder()
        val allConnectivities = manager.connectivityRegistry.allConnectivities()
            .filter { it.peerId().value() != "remote-bt-node" || manager.connectivityRegistry.allConnectivities().size == 1 }

        if (allConnectivities.isNotEmpty()) {
            for (conn in allConnectivities) {
                topoSb.append("Peer: ").append(conn.peerId().value()).append("\n")

                // Group and deduplicate paths by scheme (one Bluetooth, one TCP)
                val tcpPath = conn.allPaths().firstOrNull { it.transportName().equals("tcp", ignoreCase = true) }
                val btPath = conn.allPaths().firstOrNull { it.transportName().contains("bt", ignoreCase = true) || it.transportName().contains("bluetooth", ignoreCase = true) }

                if (btPath != null) {
                    val statusSymbol = if (btPath.isActive) "● ACTIVE" else "○ INACTIVE"
                    val cleanConn = btPath.optionalConnectionId().map { if (it.startsWith("/")) it.substring(1) else it }.orElse("no-conn")
                    topoSb.append(" ├── [BLUETOOTH] ")
                        .append(statusSymbol).append(" (").append(cleanConn).append(")\n")
                }

                if (tcpPath != null) {
                    val statusSymbol = if (tcpPath.isActive) "● ACTIVE" else "○ INACTIVE"
                    val rawConn = tcpPath.optionalConnectionId().orElse("no-conn")
                    val cleanConn = if (rawConn.startsWith("bt:")) "no-conn" else (if (rawConn.startsWith("/")) rawConn.substring(1) else rawConn)
                    topoSb.append(" ├── [TCP] ")
                        .append(statusSymbol).append(" (").append(cleanConn).append(")\n")
                }

                val selected = try { manager.router.resolveConnectionId(conn.peerId()) } catch (e: Exception) { "none" }
                val cleanSelected = if (selected.startsWith("/")) selected.substring(1) else selected
                topoSb.append(" └── [DISPATCH ROUTE]: ").append(cleanSelected).append("\n\n")
            }
        } else {
            topoSb.append("No active peer paths.")
        }
        tvMultiPathTopology.text = topoSb.toString().trim()
    }

    private fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        manager.close()
    }
}