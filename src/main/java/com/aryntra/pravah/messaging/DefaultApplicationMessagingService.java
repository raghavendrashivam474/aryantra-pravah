package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.protocol.ProtocolListener;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Default implementation of the ApplicationMessagingService.
 * Implements S4.3 message state tracking and application-level delivery receipts
 * without breaking any frozen Phase 3 network protocol constraints.
 */
public class DefaultApplicationMessagingService implements ApplicationMessagingService {

    private static final Logger LOGGER = Logger.getLogger(DefaultApplicationMessagingService.class.getName());

    private static final byte APP_MSG_CHAT = 0x01;
    private static final byte APP_MSG_ACK  = 0x02;

    private final PeerId localPeerId;
    private final PeerRouter peerRouter;
    private final List<ApplicationMessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<MessageLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();

    // Tracks state of outgoing/managed messages
    private final Map<String, MessageState> messageStates = new ConcurrentHashMap<>();

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
        this.peerRouter = Objects.requireNonNull(peerRouter, "peerRouter must not be null");
        Objects.requireNonNull(coordinator, "coordinator must not be null");

        // Bind incoming logical session events
        coordinator.setProtocolListener(new ProtocolListener() {
            @Override
            public void onPeerJoined(String peerIdStr, Message message) {}

            @Override
            public void onMessageReceived(String peerIdStr, Message message) {
                if (message != null && message.type() == MessageType.MESSAGE) {
                    handleInboundProtocolMessage(peerIdStr, message);
                }
            }

            @Override
            public void onPeerLeft(String peerIdStr, Message message) {}
        });
    }

    @Override
    public void send(PeerId destination, ApplicationMessage message) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(message, "message must not be null");

        updateState(message.messageId(), MessageState.CREATED);

        // Frame the application payload: type byte + text content bytes
        byte[] appTextBytes = message.toPayload();
        byte[] framedPayload = new byte[1 + appTextBytes.length];
        framedPayload[0] = APP_MSG_CHAT;
        System.arraycopy(appTextBytes, 0, framedPayload, 1, appTextBytes.length);

        Message protocolMessage = new Message(
                MessageType.MESSAGE,
                localPeerId.value(),
                message.messageId(),
                framedPayload
        );

        try {
            LOGGER.fine(() -> "Dispatching application message " + message.messageId() + " to " + destination.value());
            peerRouter.send(destination, protocolMessage);
            updateState(message.messageId(), MessageState.SENT);
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to route application message " + message.messageId() + ": " + e.getMessage());
            updateState(message.messageId(), MessageState.FAILED);
        }
    }

    @Override
    public ApplicationMessage sendText(PeerId destination, String content) {
        return sendText(destination, content, new ConversationId("direct:system:default"));
    }

    /**
     * S4.2 capability supporting sending directly within a specified conversation container.
     */
    public ApplicationMessage sendText(PeerId destination, String content, ConversationId conversationId) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");

        ApplicationMessage msg = ApplicationMessage.text(localPeerId, content, conversationId);
        send(destination, msg);
        return msg;
    }

    @Override
    public void addListener(ApplicationMessageListener listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
    }

    @Override
    public void removeListener(ApplicationMessageListener listener) {
        if (listener != null) {
            messageListeners.remove(listener);
        }
    }

    public void addLifecycleListener(MessageLifecycleListener listener) {
        if (listener != null) {
            lifecycleListeners.add(listener);
        }
    }

    public void removeLifecycleListener(MessageLifecycleListener listener) {
        if (listener != null) {
            lifecycleListeners.remove(listener);
        }
    }

    public MessageState getMessageState(String messageId) {
        return messageStates.get(messageId);
    }

    private void updateState(String messageId, MessageState state) {
        messageStates.put(messageId, state);
        for (MessageLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onStateChanged(messageId, state);
            } catch (Exception e) {
                LOGGER.warning("Exception in MessageLifecycleListener: " + e.getMessage());
            }
        }
    }

    private void handleInboundProtocolMessage(String senderPeerIdStr, Message protocolMessage) {
        byte[] payload = protocolMessage.payload();
        if (payload == null || payload.length == 0) {
            return;
        }

        byte appType = payload[0];
        if (appType == APP_MSG_CHAT) {
            handleInboundChat(senderPeerIdStr, protocolMessage);
        } else if (appType == APP_MSG_ACK) {
            handleInboundAck(protocolMessage);
        }
    }

    private void handleInboundChat(String senderPeerIdStr, Message protocolMessage) {
        try {
            PeerId sender = PeerId.of(senderPeerIdStr);
            byte[] rawPayload = protocolMessage.payload();
            byte[] textPayload = new byte[rawPayload.length - 1];
            System.arraycopy(rawPayload, 1, textPayload, 0, textPayload.length);

            // Reconstruct linking back to its canonical direct conversation context
            ConversationId conversationId = ConversationManager.deriveDirectConversationId(localPeerId, sender);
            ApplicationMessage appMessage = ApplicationMessage.fromPayload(
                    protocolMessage.messageId(),
                    sender,
                    textPayload,
                    conversationId
            );

            // Notify application listeners
            for (ApplicationMessageListener listener : messageListeners) {
                try {
                    listener.onMessage(appMessage);
                } catch (Exception e) {
                    LOGGER.warning("Exception in ApplicationMessageListener: " + e.getMessage());
                }
            }

            // S4.3 Rule: Instantly send application-level ACK back to the sender
            sendApplicationAck(sender, protocolMessage.messageId());

        } catch (Exception e) {
            LOGGER.warning("Inbound application parsing failed: " + e.getMessage());
        }
    }

    private void handleInboundAck(Message protocolMessage) {
        byte[] rawPayload = protocolMessage.payload();
        byte[] ackedIdBytes = new byte[rawPayload.length - 1];
        System.arraycopy(rawPayload, 1, ackedIdBytes, 0, ackedIdBytes.length);
        String ackedMessageId = new String(ackedIdBytes, StandardCharsets.UTF_8);

        LOGGER.fine(() -> "Received remote delivery receipt for message " + ackedMessageId);
        updateState(ackedMessageId, MessageState.DELIVERED);
    }

    private void sendApplicationAck(PeerId recipient, String messageIdToAck) {
        byte[] idBytes = messageIdToAck.getBytes(StandardCharsets.UTF_8);
        byte[] framedAckPayload = new byte[1 + idBytes.length];
        framedAckPayload[0] = APP_MSG_ACK;
        System.arraycopy(idBytes, 0, framedAckPayload, 1, idBytes.length);

        Message ackMessage = new Message(
                MessageType.MESSAGE,
                localPeerId.value(),
                UUID_Helper(), // Unique envelope ID for receipt routing
                framedAckPayload
        );

        try {
            LOGGER.fine(() -> "Replying with ACK for message " + messageIdToAck);
            peerRouter.send(recipient, ackMessage);
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to dispatch ACK for message " + messageIdToAck + ": " + e.getMessage());
        }
    }

    private String UUID_Helper() {
        return java.util.UUID.randomUUID().toString();
    }
}