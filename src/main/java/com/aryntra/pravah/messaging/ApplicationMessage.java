package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain model representing an application-level message.
 * Decoupled from transport, frames, and low-level protocol envelopes.
 *
 * <p>Includes optional application-level {@code sequenceNumber} for
 * deterministic ordering across transport migration boundaries.</p>
 */
public final class ApplicationMessage {

    private final String messageId;
    private final PeerId sender;
    private final String content;
    private final Instant timestamp;
    private final ConversationId conversationId;
    private final long sequenceNumber;

    public ApplicationMessage(String messageId,
                              PeerId sender,
                              String content,
                              Instant timestamp,
                              ConversationId conversationId,
                              long sequenceNumber) {
        this.messageId = Objects.requireNonNull(messageId, "messageId must not be null");
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.sequenceNumber = sequenceNumber;
    }

    public ApplicationMessage(String messageId,
                              PeerId sender,
                              String content,
                              Instant timestamp,
                              ConversationId conversationId) {
        this(messageId, sender, content, timestamp, conversationId, 0L);
    }

    public ApplicationMessage(String messageId,
                              PeerId sender,
                              String content,
                              Instant timestamp) {
        this(messageId, sender, content, timestamp, new ConversationId("direct:system:default"), 0L);
    }

    public static ApplicationMessage text(PeerId sender, String content, ConversationId conversationId) {
        return new ApplicationMessage(UUID.randomUUID().toString(), sender, content, Instant.now(), conversationId, 0L);
    }

    public static ApplicationMessage fromPayload(String messageId,
                                                 PeerId sender,
                                                 byte[] payload,
                                                 ConversationId conversationId) {
        Objects.requireNonNull(payload, "payload must not be null");
        String text = new String(payload, StandardCharsets.UTF_8);
        return new ApplicationMessage(messageId, sender, text, Instant.now(), conversationId, 0L);
    }

    public static ApplicationMessage fromPayload(String messageId,
                                                 PeerId sender,
                                                 byte[] payload,
                                                 ConversationId conversationId,
                                                 long sequenceNumber) {
        Objects.requireNonNull(payload, "payload must not be null");
        String text = new String(payload, StandardCharsets.UTF_8);
        return new ApplicationMessage(messageId, sender, text, Instant.now(), conversationId, sequenceNumber);
    }

    public static ApplicationMessage text(PeerId sender, String content) {
        return text(sender, content, new ConversationId("direct:system:default"));
    }

    public static ApplicationMessage text(String messageId, PeerId sender, String content) {
        return new ApplicationMessage(messageId, sender, content, Instant.now(), new ConversationId("direct:system:default"), 0L);
    }

    public static ApplicationMessage fromPayload(String messageId, PeerId sender, byte[] payload) {
        return fromPayload(messageId, sender, payload, new ConversationId("direct:system:default"), 0L);
    }

    public String messageId() {
        return messageId;
    }

    public PeerId sender() {
        return sender;
    }

    public String content() {
        return content;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public ApplicationMessage withSequence(long sequenceNumber) {
        return new ApplicationMessage(messageId, sender, content, timestamp, conversationId, sequenceNumber);
    }

    public byte[] toPayload() {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApplicationMessage that)) return false;
        return Objects.equals(messageId, that.messageId) &&
                Objects.equals(sender, that.sender) &&
                Objects.equals(content, that.content) &&
                Objects.equals(conversationId, that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, sender, content, conversationId);
    }

    @Override
    public String toString() {
        return "ApplicationMessage{" +
                "messageId='" + messageId + '\'' +
                ", sender=" + sender +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                ", conversationId=" + conversationId +
                ", sequenceNumber=" + sequenceNumber +
                '}';
    }
}