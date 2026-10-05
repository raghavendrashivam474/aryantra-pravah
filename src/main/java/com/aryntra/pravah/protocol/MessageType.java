package com.aryntra.pravah.protocol;

/**
 * Supported Pravah protocol message types.
 * S2.1 establishes the type vocabulary; chat semantics belong to S2.4.
 * SX.4 adds authentication handshake types.
 */
public enum MessageType {
    JOIN((byte) 0x01),
    MESSAGE((byte) 0x02),
    LEAVE((byte) 0x03),

    // --- SX.4: Authentication Handshake ---
    /** Verifier issues a cryptographic challenge to the claimant. */
    AUTH_CHALLENGE((byte) 0x04),
    /** Claimant responds with a signed proof of possession. */
    AUTH_PROOF((byte) 0x05);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    /**
     * Resolves a MessageType from its wire code.
     *
     * @param code the single-byte type identifier
     * @return the matching MessageType
     * @throws IllegalArgumentException if the code is unknown
     */
    public static MessageType fromCode(byte code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MessageType code: 0x"
                + String.format("%02X", code));
    }
}
