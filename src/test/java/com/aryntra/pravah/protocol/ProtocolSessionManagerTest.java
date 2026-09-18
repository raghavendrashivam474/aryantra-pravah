package com.aryntra.pravah.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S2.4 — ProtocolSessionManager Chat Semantics Tests")
class ProtocolSessionManagerTest {

    private ProtocolSessionManager sessionManager;
    private TestProtocolListener listener;

    private static class TestProtocolListener implements ProtocolListener {
        final List<String> joinedPeers = new ArrayList<>();
        final List<Message> receivedMessages = new ArrayList<>();
        final List<String> leftPeers = new ArrayList<>();

        @Override
        public void onPeerJoined(String peerId, Message message) {
            joinedPeers.add(peerId);
        }

        @Override
        public void onMessageReceived(String peerId, Message message) {
            receivedMessages.add(message);
        }

        @Override
        public void onPeerLeft(String peerId, Message message) {
            leftPeers.add(peerId);
        }
    }

    @BeforeEach
    void setUp() {
        listener = new TestProtocolListener();
        sessionManager = new ProtocolSessionManager(listener);
    }

    @Test
    @DisplayName("Happy path: JOIN -> MESSAGE -> LEAVE transitions peer through states")
    void shouldExecuteFullSessionLifecycle() {
        Message join = new Message(MessageType.JOIN, "alice", "j-1", new byte[0]);
        Message chat = new Message(MessageType.MESSAGE, "alice", "m-1", "hello".getBytes(StandardCharsets.UTF_8));
        Message leave = new Message(MessageType.LEAVE, "alice", "l-1", new byte[0]);

        assertNull(sessionManager.getPeerState("alice"));
        assertFalse(sessionManager.isPeerJoined("alice"));

        // 1. JOIN
        sessionManager.processMessage(join);
        assertEquals(PeerState.JOINED, sessionManager.getPeerState("alice"));
        assertTrue(sessionManager.isPeerJoined("alice"));
        assertEquals(1, listener.joinedPeers.size());
        assertEquals("alice", listener.joinedPeers.get(0));

        // 2. MESSAGE
        sessionManager.processMessage(chat);
        assertEquals(1, listener.receivedMessages.size());
        assertEquals(chat, listener.receivedMessages.get(0));

        // 3. LEAVE
        sessionManager.processMessage(leave);
        assertEquals(PeerState.LEFT, sessionManager.getPeerState("alice"));
        assertFalse(sessionManager.isPeerJoined("alice"));
        assertEquals(1, listener.leftPeers.size());
        assertEquals("alice", listener.leftPeers.get(0));
    }

    @Test
    @DisplayName("Rejects MESSAGE before JOIN with ProtocolException")
    void shouldRejectMessageBeforeJoin() {
        Message chat = new Message(MessageType.MESSAGE, "bob", "m-1", "hi".getBytes(StandardCharsets.UTF_8));

        ProtocolException ex = assertThrows(ProtocolException.class, () -> sessionManager.processMessage(chat));
        assertTrue(ex.getMessage().contains("cannot send MESSAGE before JOIN"));
        assertTrue(listener.receivedMessages.isEmpty());
    }

    @Test
    @DisplayName("Rejects MESSAGE after LEAVE with ProtocolException")
    void shouldRejectMessageAfterLeave() {
        Message join = new Message(MessageType.JOIN, "bob", "j-1", new byte[0]);
        Message leave = new Message(MessageType.LEAVE, "bob", "l-1", new byte[0]);
        Message chat = new Message(MessageType.MESSAGE, "bob", "m-1", "hi again".getBytes(StandardCharsets.UTF_8));

        sessionManager.processMessage(join);
        sessionManager.processMessage(leave);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> sessionManager.processMessage(chat));
        assertTrue(ex.getMessage().contains("cannot send MESSAGE after LEAVE"));
    }

    @Test
    @DisplayName("Rejects duplicate JOIN with ProtocolException")
    void shouldRejectDuplicateJoin() {
        Message join1 = new Message(MessageType.JOIN, "alice", "j-1", new byte[0]);
        Message join2 = new Message(MessageType.JOIN, "alice", "j-2", new byte[0]);

        sessionManager.processMessage(join1);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> sessionManager.processMessage(join2));
        assertTrue(ex.getMessage().contains("already in JOINED state"));
    }

    @Test
    @DisplayName("Rejects LEAVE without prior JOIN with ProtocolException")
    void shouldRejectLeaveWithoutJoin() {
        Message leave = new Message(MessageType.LEAVE, "charlie", "l-1", new byte[0]);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> sessionManager.processMessage(leave));
        assertTrue(ex.getMessage().contains("cannot LEAVE without prior JOIN"));
    }

    @Test
    @DisplayName("Rejects duplicate LEAVE with ProtocolException")
    void shouldRejectDuplicateLeave() {
        Message join = new Message(MessageType.JOIN, "charlie", "j-1", new byte[0]);
        Message leave1 = new Message(MessageType.LEAVE, "charlie", "l-1", new byte[0]);
        Message leave2 = new Message(MessageType.LEAVE, "charlie", "l-2", new byte[0]);

        sessionManager.processMessage(join);
        sessionManager.processMessage(leave1);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> sessionManager.processMessage(leave2));
        assertTrue(ex.getMessage().contains("already in LEFT state"));
    }

    @Test
    @DisplayName("Allows peer to RE-JOIN after LEAVE")
    void shouldAllowRejoinAfterLeave() {
        Message join1 = new Message(MessageType.JOIN, "dave", "j-1", new byte[0]);
        Message leave = new Message(MessageType.LEAVE, "dave", "l-1", new byte[0]);
        Message join2 = new Message(MessageType.JOIN, "dave", "j-2", new byte[0]);
        Message chat = new Message(MessageType.MESSAGE, "dave", "m-1", "back online".getBytes());

        sessionManager.processMessage(join1);
        sessionManager.processMessage(leave);
        sessionManager.processMessage(join2);
        sessionManager.processMessage(chat);

        assertEquals(PeerState.JOINED, sessionManager.getPeerState("dave"));
        assertEquals(2, listener.joinedPeers.size());
        assertEquals(1, listener.receivedMessages.size());
    }

    @Test
    @DisplayName("Multiple peers operate independently without cross-talk or state contamination")
    void shouldIsolateMultiplePeers() {
        Message joinAlice = new Message(MessageType.JOIN, "alice", "j-1", new byte[0]);
        Message joinBob = new Message(MessageType.JOIN, "bob", "j-2", new byte[0]);
        Message leaveBob = new Message(MessageType.LEAVE, "bob", "l-1", new byte[0]);
        Message chatAlice = new Message(MessageType.MESSAGE, "alice", "m-1", "alice chatting".getBytes());

        sessionManager.processMessage(joinAlice);
        sessionManager.processMessage(joinBob);
        sessionManager.processMessage(leaveBob);

        // Bob is left, Alice is joined
        assertEquals(PeerState.JOINED, sessionManager.getPeerState("alice"));
        assertEquals(PeerState.LEFT, sessionManager.getPeerState("bob"));

        // Alice can still chat freely
        sessionManager.processMessage(chatAlice);
        assertEquals(1, listener.receivedMessages.size());
        assertEquals("alice", listener.receivedMessages.get(0).senderId());
    }
}