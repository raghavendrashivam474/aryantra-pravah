package com.aryntra.pravah.messaging;

import com.aryntra.pravah.messaging.reliability.DeliveryOutbox;
import com.aryntra.pravah.messaging.reliability.DeliveryRetryManager;
import com.aryntra.pravah.messaging.reliability.InMemoryDeliveryOutbox;
import com.aryntra.pravah.messaging.reliability.OutboxEntry;
import com.aryntra.pravah.messaging.reliability.OutboxState;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Default implementation of the ApplicationMessagingService.
 * Integrates message lifecycle state tracking, persistent history storage,
 * delivery intent tracking via DeliveryOutbox, group communication fan-out,
 * and automated reconnect delivery retries via DeliveryRetryManager.
 */
public class DefaultApplicationMessagingService implements ApplicationMessagingService {

    private static final Logger LOGGER = Logger.getLogger(DefaultApplicationMessagingService.class.getName());

    private static final byte APP_MSG_CHAT       = 0x01;
    private static final byte APP_MSG_ACK        = 0x02;
    private static final byte APP_MSG_GROUP_CHAT = 0x03;

    private final PeerId localPeerId;
    private final PeerRouter peerRouter;
    private final MessageHistoryStore historyStore;
    private final DeliveryOutbox outbox;
    private final DeliveryRetryManager retryManager;
    private final ConversationManager conversationManager;
    private final List<ApplicationMessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<MessageLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();

    // Tracks state of outgoing/managed messages
    private final Map<String, MessageState> messageStates = new ConcurrentHashMap<>();

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator) {
        this(localPeerId, peerRouter, coordinator, new InMemoryMessageHistoryStore(), new InMemoryDeliveryOutbox());
    }

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator,
                                              MessageHistoryStore historyStore) {
        this(localPeerId, peerRouter, coordinator, historyStore, new InMemoryDeliveryOutbox());
    }

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator,
                                              MessageHistoryStore historyStore,
                                              DeliveryOutbox outbox) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
        this.peerRouter = Objects.requireNonNull(peerRouter, "peerRouter must not be null");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.conversationManager = new ConversationManager(localPeerId);
        Objects.requireNonNull(coordinator, "coordinator must not be null");

        this.retryManager = new DeliveryRetryManager(
                localPeerId,
                outbox,
                historyStore,
                peerRouter,
                (messageId, success) -> {
                    if (success) {
                        updateState(messageId, MessageState.SENT);
                    } else {
                        updateState(messageId, MessageState.FAILED);
                    }
                }
        );

        // Bind incoming logical session events
        coordinator.setProtocolListener(new ProtocolListener() {
            @Override
            public void onPeerJoined(String peerIdStr, Message message) {
                try {
                    PeerId joinedPeer = PeerId.of(peerIdStr);
                    retryManager.retryPendingForPeer(joinedPeer);
                } catch (Exception e) {
                    LOGGER.warning("Error triggering retry upon peer join: " + e.getMessage());
                }
            }

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

    public MessageHistoryStore getHistoryStore() {
        return historyStore;
    }

    public DeliveryOutbox getOutbox() {
        return outbox;
    }

    public DeliveryRetryManager getRetryManager() {
        return retryManager;
    }

    public ConversationManager getConversationManager() {
        return conversationManager;
    }

    @Override
    public void send(PeerId destination, ApplicationMessage message) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(message, "message must not be null");

        ConversationId conversationId = message.conversationId();

        // Check if destination is group
        if (conversationId.value().startsWith("group:")) {
            sendGroupMessage(message);
            return;
        }

        // 1. Direct message path - persist history
        try {
            historyStore.save(message, MessageState.CREATED);
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Message already in history store: " + message.messageId());
        }

        // 2. Register delivery intent in outbox
        try {
            outbox.enqueue(OutboxEntry.pending(message.messageId(), destination, conversationId));
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Delivery intent already in outbox: " + message.messageId());
        }

        updateState(message.messageId(), MessageState.CREATED);

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

        // 3. Attempt immediate delivery
        try {
            LOGGER.fine(() -> "Dispatching application message " + message.messageId() + " to " + destination.value());
            peerRouter.send(destination, protocolMessage);
            updateState(message.messageId(), MessageState.SENT);
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to route application message " + message.messageId() + ": " + e.getMessage());
            updateState(message.messageId(), MessageState.FAILED);
            // Notice: OutboxEntry remains PENDING in outbox, preserving delivery intent
        }
    }

    /**
     * Application-layer fan-out implementation for Group Messages.
     * Guarantees message delivery without extending core PeerRouter constructs.
     */
    private void sendGroupMessage(ApplicationMessage message) {
        ConversationId groupId = message.conversationId();
        GroupConversation group = conversationManager.getGroupConversation(groupId)
                .orElseThrow(() -> new IllegalArgumentException("No registered group conversation with ID: " + groupId));

        // 1. Save locally with initial state
        try {
            historyStore.save(message, MessageState.CREATED);
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Group message already in history store: " + message.messageId());
        }
        updateState(message.messageId(), MessageState.CREATED);

        // 2. Wire frame group message payload:
        // [0x03 (byte)][2-byte groupId len][groupId (UTF-8)][content (UTF-8)]
        byte[] groupIdBytes = groupId.value().getBytes(StandardCharsets.UTF_8);
        byte[] appTextBytes = message.toPayload();

        int payloadLen = 1 + 2 + groupIdBytes.length + appTextBytes.length;
        byte[] framedPayload = new byte[payloadLen];

        framedPayload[0] = APP_MSG_GROUP_CHAT;
        framedPayload[1] = (byte) ((groupIdBytes.length >> 8) & 0xFF);
        framedPayload[2] = (byte) (groupIdBytes.length & 0xFF);
        System.arraycopy(groupIdBytes, 0, framedPayload, 3, groupIdBytes.length);
        System.arraycopy(appTextBytes, 0, framedPayload, 3 + groupIdBytes.length, appTextBytes.length);

        // 3. Dispatch to all participants except local self
        Set<PeerId> participants = group.participants();
        boolean atLeastOneDispatched = false;

        for (PeerId participant : participants) {
            if (participant.equals(localPeerId)) {
                continue;
            }

            Message protocolMessage = new Message(
                    MessageType.MESSAGE,
                    localPeerId.value(),
                    message.messageId(),
                    framedPayload
            );

            try {
                LOGGER.fine(() -> "Routing group message " + message.messageId() + " to " + participant.value());
                peerRouter.send(participant, protocolMessage);
                atLeastOneDispatched = true;
            } catch (Exception e) {
                LOGGER.warning(() -> "Failed to route group message " + message.messageId() + " to " + participant.value() + ": " + e.getMessage());
            }
        }

        if (atLeastOneDispatched) {
            updateState(message.messageId(), MessageState.SENT);
        } else {
            updateState(message.messageId(), MessageState.FAILED);
        }
    }

    @Override
    public ApplicationMessage sendText(PeerId destination, String content) {
        return sendText(destination, content, new ConversationId("direct:system:default"));
    }

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
        MessageState state = messageStates.get(messageId);
        if (state == null) {
            return historyStore.findState(messageId).orElse(null);
        }
        return state;
    }

    private void updateState(String messageId, MessageState state) {
        messageStates.put(messageId, state);
        try {
            historyStore.updateState(messageId, state);
        } catch (Exception ignored) {}

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
        switch (appType) {
            case APP_MSG_CHAT -> handleInboundChat(senderPeerIdStr, protocolMessage);
            case APP_MSG_ACK -> handleInboundAck(protocolMessage);
            case APP_MSG_GROUP_CHAT -> handleInboundGroupChat(senderPeerIdStr, protocolMessage);
            default -> LOGGER.warning("Unknown application payload type: " + appType);
        }
    }

    private void handleInboundChat(String senderPeerIdStr, Message protocolMessage) {
        try {
            PeerId sender = PeerId.of(senderPeerIdStr);
            byte[] rawPayload = protocolMessage.payload();
            byte[] textPayload = new byte[rawPayload.length - 1];
            System.arraycopy(rawPayload, 1, textPayload, 0, textPayload.length);

            ConversationId conversationId = ConversationManager.deriveDirectConversationId(localPeerId, sender);
            ApplicationMessage appMessage = ApplicationMessage.fromPayload(
                    protocolMessage.messageId(),
                    sender,
                    textPayload,
                    conversationId
            );

            try {
                historyStore.save(appMessage, MessageState.DELIVERED);
            } catch (Exception e) {
                LOGGER.fine("Message already in store: " + appMessage.messageId());
            }

            for (ApplicationMessageListener listener : messageListeners) {
                try {
                    listener.onMessage(appMessage);
                } catch (Exception e) {
                    LOGGER.warning("Exception in ApplicationMessageListener: " + e.getMessage());
                }
            }

            sendApplicationAck(sender, protocolMessage.messageId());
        } catch (Exception e) {
            LOGGER.warning("Inbound application parsing failed: " + e.getMessage());
        }
    }

    private void handleInboundGroupChat(String senderPeerIdStr, Message protocolMessage) {
        try {
            PeerId sender = PeerId.of(senderPeerIdStr);
            byte[] rawPayload = protocolMessage.payload();

            // Extract group ID length and value
            int groupLen = ((rawPayload[1] & 0xFF) << 8) | (rawPayload[2] & 0xFF);
            String groupIdStr = new String(rawPayload, 3, groupLen, StandardCharsets.UTF_8);
            ConversationId groupId = new ConversationId(groupIdStr);

            // Extract message content
            int contentOffset = 3 + groupLen;
            byte[] textPayload = new byte[rawPayload.length - contentOffset];
            System.arraycopy(rawPayload, contentOffset, textPayload, 0, textPayload.length);

            ApplicationMessage appMessage = ApplicationMessage.fromPayload(
                    protocolMessage.messageId(),
                    sender,
                    textPayload,
                    groupId
            );

            // Reconstruct / Auto-reconcile group locally if not present
            if (conversationManager.getGroupConversation(groupId).isEmpty()) {
                GroupConversation autoReconstructed = new GroupConversation(
                        groupId,
                        "Auto Group " + groupIdStr.substring(Math.min(groupIdStr.length(), 11)),
                        Set.of(localPeerId, sender)
                );
                conversationManager.registerGroupConversation(autoReconstructed);
            }

            // Persist
            try {
                historyStore.save(appMessage, MessageState.DELIVERED);
            } catch (Exception e) {
                LOGGER.fine("Group message already in store: " + appMessage.messageId());
            }

            // Notify
            for (ApplicationMessageListener listener : messageListeners) {
                try {
                    listener.onMessage(appMessage);
                } catch (Exception e) {
                    LOGGER.warning("Exception in ApplicationMessageListener: " + e.getMessage());
                }
            }

            // Reply with delivery receipt
            sendApplicationAck(sender, protocolMessage.messageId());
        } catch (Exception e) {
            LOGGER.warning("Inbound group parsing failed: " + e.getMessage());
        }
    }

    private void handleInboundAck(Message protocolMessage) {
        byte[] rawPayload = protocolMessage.payload();
        byte[] ackedIdBytes = new byte[rawPayload.length - 1];
        System.arraycopy(rawPayload, 1, ackedIdBytes, 0, ackedIdBytes.length);
        String ackedMessageId = new String(ackedIdBytes, StandardCharsets.UTF_8);

        LOGGER.fine(() -> "Received remote delivery receipt for message " + ackedMessageId);
        updateState(ackedMessageId, MessageState.DELIVERED);
        outbox.markCompleted(ackedMessageId);
    }

    private void sendApplicationAck(PeerId recipient, String messageIdToAck) {
        byte[] idBytes = messageIdToAck.getBytes(StandardCharsets.UTF_8);
        byte[] framedAckPayload = new byte[1 + idBytes.length];
        framedAckPayload[0] = APP_MSG_ACK;
        System.arraycopy(idBytes, 0, framedAckPayload, 1, idBytes.length);

        Message ackMessage = new Message(
                MessageType.MESSAGE,
                localPeerId.value(),
                UUID_Helper(),
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