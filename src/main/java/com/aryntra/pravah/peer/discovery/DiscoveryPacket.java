package com.aryntra.pravah.peer.discovery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format for LAN peer discovery packets.
 *
 * Layout:
 *   [4 bytes] MAGIC   — "PRAH" (ASCII)
 *   [1 byte]  VERSION — 0x01
 *   [2 bytes] ID_LEN  — unsigned short, length of peer ID in UTF-8 bytes
 *   [N bytes] PEER_ID — UTF-8 encoded logical peer identity
 *   [2 bytes] TCP_PORT — unsigned short, the TCP port this peer listens on
 *
 * Minimum packet size: 4 + 1 + 2 + 1 + 2 = 10 bytes
 */
public final class DiscoveryPacket {

    public static final byte[] MAGIC = "PRAH".getBytes(StandardCharsets.US_ASCII);
    public static final byte VERSION = 0x01;

    /** MAGIC(4) + VERSION(1) + ID_LEN(2) */
    public static final int HEADER_SIZE = 7;

    /** HEADER(7) + at least 1 byte peer ID + PORT(2) */
    public static final int MIN_PACKET_SIZE = 10;

    private final String peerId;
    private final int tcpPort;

    public DiscoveryPacket(String peerId, int tcpPort) {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be null or blank");
        }
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("tcpPort must be in range 1-65535, got: " + tcpPort);
        }
        this.peerId = peerId;
        this.tcpPort = tcpPort;
    }

    public String peerId() {
        return peerId;
    }

    public int tcpPort() {
        return tcpPort;
    }

    /**
     * Serialize this discovery packet to bytes for UDP transmission.
     */
    public byte[] serialize() {
        byte[] idBytes = peerId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > 65535) {
            throw new IllegalArgumentException("peerId too long for wire format");
        }

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + idBytes.length + 2);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        buf.putShort((short) tcpPort);
        return buf.array();
    }

    /**
     * Deserialize a discovery packet from raw UDP bytes.
     */
    public static DiscoveryPacket deserialize(byte[] data) {
        if (data == null || data.length < MIN_PACKET_SIZE) {
            throw new IllegalArgumentException(
                "Packet too short: need at least " + MIN_PACKET_SIZE + " bytes");
        }

        // Validate magic bytes
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new IllegalArgumentException("Invalid magic marker");
            }
        }

        // Validate version
        if (data[4] != VERSION) {
            throw new IllegalArgumentException(
                "Unsupported discovery version: " + (data[4] & 0xFF));
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.position(5); // skip MAGIC(4) + VERSION(1)

        int idLen = Short.toUnsignedInt(buf.getShort());

        if (idLen == 0) {
            throw new IllegalArgumentException("Peer ID length cannot be zero");
        }

        if (buf.remaining() < idLen + 2) {
            throw new IllegalArgumentException(
                "Truncated packet: expected " + (idLen + 2) +
                " more bytes, have " + buf.remaining());
        }

        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String peerId = new String(idBytes, StandardCharsets.UTF_8);

        int tcpPort = Short.toUnsignedInt(buf.getShort());

        return new DiscoveryPacket(peerId, tcpPort);
    }

    @Override
    public String toString() {
        return "DiscoveryPacket{peerId='" + peerId + "', tcpPort=" + tcpPort + "}";
    }
}