package com.aryntra.pravah.android.state

import com.aryntra.pravah.android.PravahAndroidMessagingManager
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.connectivity.PathState
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.security.trust.TrustState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("A.D3 DiagnosticModelMapper & UI Contract Tests")
class DiagnosticModelMapperTest {

    @Test
    @DisplayName("Human display name formatting preserves identity distinction")
    fun testHumanDisplayNameFormatting() {
        val androidPeer = "android-abc123456789"
        val nodePeer = "node-xyz987654321"
        val btPeer = "remote-bt-112233445566"
        val standardPeer = "alice"

        assertEquals("Android (abc123)", DiagnosticModelMapper.formatHumanDisplayName(androidPeer))
        assertEquals("Node (xyz987)", DiagnosticModelMapper.formatHumanDisplayName(nodePeer))
        assertEquals("Bluetooth Device (112233)", DiagnosticModelMapper.formatHumanDisplayName(btPeer))
        assertEquals("alice", DiagnosticModelMapper.formatHumanDisplayName(standardPeer))
    }

    @Test
    @DisplayName("PeerContextState maps trust, presence, and sorted paths correctly")
    fun testPeerContextStateMapping() {
        val localId = PeerId.of("android-local111")
        val remoteId = PeerId.of("android-remote222")

        val manager = PravahAndroidMessagingManager(localId)
        
        // Register active TCP path and inactive BT path
        val tcpPath = ConnectivityPath(
            PathId.of("p-tcp"),
            remoteId,
            "tcp",
            EndpointAddress.tcp("192.168.1.50", 9000),
            PathState.ACTIVE,
            "conn-tcp-1"
        )
        val btPath = ConnectivityPath(
            PathId.of("p-bt"),
            remoteId,
            "bluetooth",
            EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:FF", 0),
            PathState.INACTIVE,
            null
        )

        manager.connectivityRegistry.registerPath(remoteId, tcpPath)
        manager.connectivityRegistry.registerPath(remoteId, btPath)

        // Set remote peer trust state to TRUSTED
        manager.trustManager.transitionState(remoteId, TrustState.TRUSTED)

        // Map state
        val state = DiagnosticModelMapper.map(manager, remoteId)

        assertEquals(1, state.peerContexts.size)
        val peerCtx = state.peerContexts[0]

        // Technical ID immutable
        assertEquals("android-remote222", peerCtx.technicalPeerId)
        assertEquals("Android (remote)", peerCtx.humanDisplayName)
        assertEquals("ONLINE", peerCtx.presenceState)
        assertEquals("TRUSTED", peerCtx.trustState)
        assertTrue(peerCtx.isTrusted)
        assertTrue(peerCtx.isSelected)

        // Paths sorting: ACTIVE TCP first, INACTIVE BT second
        assertEquals(2, peerCtx.paths.size)
        assertEquals("TCP", peerCtx.paths[0].transportType)
        assertTrue(peerCtx.paths[0].isActive)
        assertEquals("BLUETOOTH", peerCtx.paths[1].transportType)
        assertFalse(peerCtx.paths[1].isActive)

        // Snapshot counts
        assertEquals(1, state.snapshot.peersCount)
        assertEquals(1, state.snapshot.activePathsCount)
        assertEquals(1, state.snapshot.trustedPeersCount)
    }

    @Test
    @DisplayName("MessageJourneyState captures progressive disclosure levels")
    fun testMessageJourneyProgressiveDisclosure() {
        val msgId = "msg-101"
        val destId = "android-bob"

        val steps = listOf(
            MessageJourneyStep("Accepted", "Message queued locally", "10:00:00.100", true),
            MessageJourneyStep("Dispatched", "Sent via TCP transport", "10:00:00.120", true),
            MessageJourneyStep("Delivered", "ACK received from peer", "10:00:00.180", true)
        )

        val forensic = listOf(
            "10:00:00.100 QUEUE msgId=msg-101 seq=1",
            "10:00:00.120 DISPATCH path=tcp conn=conn-1",
            "10:00:00.180 ACK msgId=msg-101"
        )

        val journey = MessageJourneyState(
            messageId = msgId,
            sequenceNumber = 1,
            destinationPeerId = destId,
            humanStatus = "✓ Delivered",
            networkAwareStatus = "✓ Delivered via TCP",
            technicalSummary = "TCP active; ACK received in 80ms",
            steps = steps,
            forensicLog = forensic
        )

        val uiMsg = UiMessageItem(
            messageId = msgId,
            senderPeerId = "android-alice",
            senderDisplayName = "Android (alice)",
            content = "Hello Pravaah",
            timestamp = "10:00:00",
            isOutgoing = true,
            status = MessageDeliveryStatus.DELIVERED,
            journey = journey
        )

        // Level 1: Human
        assertEquals("✓ Delivered", uiMsg.journey.humanStatus)
        // Level 2: Network-aware
        assertEquals("✓ Delivered via TCP", uiMsg.journey.networkAwareStatus)
        // Level 3: Technical
        assertEquals("TCP active; ACK received in 80ms", uiMsg.journey.technicalSummary)
        // Level 4: Forensic
        assertEquals(3, uiMsg.journey.forensicLog.size)
        assertTrue(uiMsg.journey.forensicLog[1].contains("DISPATCH"))
    }
}