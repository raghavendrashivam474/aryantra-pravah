package com.aryntra.pravah.protocol;

import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S2.3 — Message Framing TCP Integration Tests")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class FramingTcpIntegrationTest {

    private TcpTransport serverTransport;
    private TcpTransport clientTransport;

    @AfterEach
    void tearDown() {
        if (serverTransport != null) {
            serverTransport.stop();
        }
        if (clientTransport != null) {
            clientTransport.stop();
        }
    }

    @Test
    @DisplayName("End-to-end: Peer A sends framed Message over TCP to Peer B")
    void shouldSendAndReceiveFramedMessageOverTcp() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Message> receivedMessage = new AtomicReference<>();
        ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();

        serverTransport.setListener((senderId, chunk) -> {
            FrameDecoder decoder = decoders.computeIfAbsent(senderId, k -> new FrameDecoder());
            List<byte[]> frames = decoder.feed(chunk);
            for (byte[] frame : frames) {
                Message msg = MessageParser.parse(frame);
                receivedMessage.set(msg);
                latch.countDown();
            }
        });

        clientTransport = new TcpTransport(0);
        clientTransport.start();
        clientTransport.connect("127.0.0.1", serverPort);

        Message original = new Message(
                MessageType.MESSAGE,
                "alice",
                "msg-001",
                "Hello via framed TCP!".getBytes(StandardCharsets.UTF_8)
        );

        byte[] encoded = MessageEncoder.encode(original);
        byte[] framed = FrameEncoder.encode(encoded);

        clientTransport.send("127.0.0.1:" + serverPort, framed);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for message");
        Message received = receivedMessage.get();
        assertNotNull(received);
        assertEquals(original, received);
        assertEquals("Hello via framed TCP!", new String(received.payload(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Fragmented TCP delivery: Message split across multiple TCP writes reconstructs perfectly")
    void shouldReconstructFragmentedMessageOverTcp() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Message> receivedMessage = new AtomicReference<>();
        ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();

        serverTransport.setListener((senderId, chunk) -> {
            FrameDecoder decoder = decoders.computeIfAbsent(senderId, k -> new FrameDecoder());
            List<byte[]> frames = decoder.feed(chunk);
            for (byte[] frame : frames) {
                receivedMessage.set(MessageParser.parse(frame));
                latch.countDown();
            }
        });

        // Use raw socket to precisely control fragmented writes
        try (Socket rawSocket = new Socket("127.0.0.1", serverPort)) {
            OutputStream out = rawSocket.getOutputStream();

            Message original = new Message(
                    MessageType.JOIN,
                    "bob-fragmented",
                    "join-001",
                    "presence-info".getBytes(StandardCharsets.UTF_8)
            );

            byte[] framed = FrameEncoder.encode(MessageEncoder.encode(original));
            int mid = framed.length / 2;

            // Write chunk 1
            out.write(framed, 0, mid);
            out.flush();
            Thread.sleep(50); // Ensure separate TCP read on server

            // Write chunk 2
            out.write(framed, mid, framed.length - mid);
            out.flush();

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for reconstructed message");
            assertEquals(original, receivedMessage.get());
        }
    }

    @Test
    @DisplayName("Coalesced TCP delivery: Multiple framed messages in single write are all extracted")
    void shouldExtractCoalescedMessagesOverTcp() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        List<Message> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);
        ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();

        serverTransport.setListener((senderId, chunk) -> {
            FrameDecoder decoder = decoders.computeIfAbsent(senderId, k -> new FrameDecoder());
            List<byte[]> frames = decoder.feed(chunk);
            for (byte[] frame : frames) {
                receivedMessages.add(MessageParser.parse(frame));
                latch.countDown();
            }
        });

        Message m1 = new Message(MessageType.JOIN, "carol", "id-1", new byte[0]);
        Message m2 = new Message(MessageType.MESSAGE, "carol", "id-2", "first".getBytes(StandardCharsets.UTF_8));
        Message m3 = new Message(MessageType.MESSAGE, "carol", "id-3", "second".getBytes(StandardCharsets.UTF_8));

        byte[] f1 = FrameEncoder.encode(MessageEncoder.encode(m1));
        byte[] f2 = FrameEncoder.encode(MessageEncoder.encode(m2));
        byte[] f3 = FrameEncoder.encode(MessageEncoder.encode(m3));

        ByteBuffer coalesced = ByteBuffer.allocate(f1.length + f2.length + f3.length);
        coalesced.put(f1).put(f2).put(f3);

        try (Socket rawSocket = new Socket("127.0.0.1", serverPort)) {
            OutputStream out = rawSocket.getOutputStream();
            out.write(coalesced.array());
            out.flush();

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for coalesced messages");
            assertEquals(3, receivedMessages.size());
            assertEquals(m1, receivedMessages.get(0));
            assertEquals(m2, receivedMessages.get(1));
            assertEquals(m3, receivedMessages.get(2));
        }
    }

    @Test
    @DisplayName("Multiple peers: Fragmented traffic from Peer A does not affect Peer B")
    void shouldIsolateFramingStreamsBetweenMultiplePeers() throws Exception {
        serverTransport = new TcpTransport(0);
        serverTransport.start();
        int serverPort = serverTransport.getBoundPort();

        List<Message> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latchB = new CountDownLatch(1);
        ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();

        serverTransport.setListener((senderId, chunk) -> {
            FrameDecoder decoder = decoders.computeIfAbsent(senderId, k -> new FrameDecoder());
            List<byte[]> frames = decoder.feed(chunk);
            for (byte[] frame : frames) {
                Message msg = MessageParser.parse(frame);
                receivedMessages.add(msg);
                if ("peerB".equals(msg.senderId())) {
                    latchB.countDown();
                }
            }
        });

        // Socket A sends partial message and stalls
        try (Socket sockA = new Socket("127.0.0.1", serverPort);
             Socket sockB = new Socket("127.0.0.1", serverPort)) {

            Message msgA = new Message(MessageType.MESSAGE, "peerA", "a-1", "partial".getBytes());
            byte[] frameA = FrameEncoder.encode(MessageEncoder.encode(msgA));
            sockA.getOutputStream().write(frameA, 0, 5); // Incomplete frame
            sockA.getOutputStream().flush();

            // Socket B sends complete message
            Message msgB = new Message(MessageType.MESSAGE, "peerB", "b-1", "ready".getBytes());
            byte[] frameB = FrameEncoder.encode(MessageEncoder.encode(msgB));
            sockB.getOutputStream().write(frameB);
            sockB.getOutputStream().flush();

            // Peer B should be delivered immediately despite Peer A being stalled
            assertTrue(latchB.await(5, TimeUnit.SECONDS), "Peer B message should be delivered");
            assertEquals(1, receivedMessages.size());
            assertEquals(msgB, receivedMessages.get(0));
        }
    }
}