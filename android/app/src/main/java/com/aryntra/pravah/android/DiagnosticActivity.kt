package com.aryntra.pravah.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.aryntra.pravah.messaging.ApplicationMessage
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.peer.discovery.DiscoveredPeer
import com.aryntra.pravah.protocol.PeerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var connectedPeerId: PeerId? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        etMessage = findViewById(R.id.etMessage)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)

        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        log("Pravah Diagnostic Node created")
        log("PeerId: ${peerId.value()}")
        updateStatus()

        btnStart.setOnClickListener { startRuntime() }
        btnStop.setOnClickListener { stopRuntime() }
        btnDiscover.setOnClickListener { toggleDiscovery() }
        btnConnect.setOnClickListener { connectToFirstPeer() }
        btnSend.setOnClickListener { sendMessage() }

        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                log("< RECV [${msg.sender().value()}]: ${msg.content()}")
                updateStatus()
            }
        })

        manager.setDiscoveryListener { peer ->
            handler.post {
                log("DISCOVERED: ${peer.peerId().value()} @ ${peer.hostAddress()}:${peer.port()}")
                btnConnect.isEnabled = true
            }
        }
    }

    private fun startRuntime() {
        try {
            manager.start()
            log("Runtime STARTED on port ${manager.boundPort}")
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            btnDiscover.isEnabled = true
            updateStatus()
        } catch (e: Exception) {
            log("ERROR starting: ${e.message}")
        }
    }

    private fun stopRuntime() {
        try {
            manager.stop()
            log("Runtime STOPPED")
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            btnDiscover.isEnabled = false
            btnConnect.isEnabled = false
            btnSend.isEnabled = false
            etMessage.isEnabled = false
            connectedPeerId = null
            updateStatus()
        } catch (e: Exception) {
            log("ERROR stopping: ${e.message}")
        }
    }

    private fun toggleDiscovery() {
        if (manager.isDiscovering) {
            manager.stopDiscovery()
            log("Discovery STOPPED")
            btnDiscover.text = "DISCOVER"
        } else {
            manager.startDiscovery()
            log("Discovery STARTED on UDP ${manager.discoveryPort}")
            btnDiscover.text = "STOP DISC"
        }
    }

    private fun connectToFirstPeer() {
        val peers = manager.discoveredPeers
        if (peers.isEmpty()) {
            log("No peers discovered yet")
            return
        }
        val target = peers[0]
        val targetPeerId = target.peerId()
        log("Connecting to ${targetPeerId.value()} @ ${target.hostAddress()}:${target.port()}...")
        try {
            manager.connectTo(target.hostAddress(), target.port())
            Thread.sleep(200)
            val connId = "${target.hostAddress()}:${target.port()}"
            manager.sendJoin(targetPeerId, connId)
            log("JOIN sent to ${targetPeerId.value()}")

            handler.postDelayed({
                manager.replyJoin(targetPeerId)
                log("JOIN reply sent")
                connectedPeerId = targetPeerId
                btnSend.isEnabled = true
                etMessage.isEnabled = true
                log("Session: checking JOINED state...")

                handler.postDelayed({
                    val state = manager.getSessionState(targetPeerId.value())
                    if (state == PeerState.JOINED) {
                        log("Session JOINED with ${targetPeerId.value()}")
                    } else {
                        log("Session state: $state (may need remote JOIN)")
                    }
                    updateStatus()
                }, 500)
            }, 300)
        } catch (e: Exception) {
            log("CONNECT ERROR: ${e.message}")
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        val peer = connectedPeerId
        if (text.isEmpty() || peer == null) return

        try {
            val msg = manager.sendText(peer, text)
            log("> SENT: $text (id: ${msg.messageId().substring(0, 8)}...)")
            etMessage.setText("")

            handler.postDelayed({
                if (manager.isDelivered(msg.messageId())) {
                    log("ACK received -> DELIVERED")
                } else {
                    log("Delivery pending...")
                }
            }, 1000)
        } catch (e: Exception) {
            log("SEND ERROR: ${e.message}")
        }
    }

    private fun updateStatus() {
        val state = if (manager.isRunning) "RUNNING" else "STOPPED"
        val port = if (manager.isRunning) manager.boundPort.toString() else "-"
        val disc = if (manager.isDiscovering) "ACTIVE" else "OFF"
        val conn = connectedPeerId?.let {
            val s = manager.getSessionState(it.value())
            "${it.value()} [$s]"
        } ?: "NONE"
        tvStatus.text = "Status: $state | Port: $port\nPeerId: ${manager.localPeerId.value()}\nDiscovery: $disc | Session: $conn"
    }

    private fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        manager.close()
    }
}
