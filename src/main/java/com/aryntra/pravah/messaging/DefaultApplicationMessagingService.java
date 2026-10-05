package com.aryntra.pravah.messaging;

import com.aryntra.pravah.messaging.reliability.*;
import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.protocol.ProtocolListener;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Default implementation of the ApplicationMessagingService.
 * Integrates message lifecycle state tracking, persistent history storage,
 * single-peer and per-recipient group outbox delivery queues,
 * automated reconnect retries, application-level sequence ordering,
 * bounded deduplication, and delivery observability.
 */
public class DefaultApplicationMessagingService implements ApplicationMessagingService {

    private static final Logger LOGGER = Logger.getLogger(DefaultApplicationMessagingService.class.getName());

    private static final byte APP_MSG_CHAT           = 0x01;
    private static final byte APP_MSG_ACK            = 0x02;
    private static final byte APP_MSG_GROUP_CHAT     = 0x03;
    private static final byte APP_MSG_SEQUENCED_CHAT = 0x04;

    private static final int MAX_DEDUP_CACHE_SIZE = 1024;

    private final PeerId localPeerId;
    private final PeerRouter peerRouter;
    private final MessageHistoryStore historyStore;
    private final DeliveryOutbox outbox;
    private final GroupDeliveryOutbox groupOutbox;
    private final DeliveryRetryManager retryManager;
    private final ConversationManager conversationManager;
    private final SequenceGenerator sequenceGenerator;

    private final List<ApplicationMessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<MessageLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
    private final Map<String, MessageState> messageStates = new ConcurrentHashMap<>();

    // Bounded LRU Set for receiver-side deduplication
    private final Set<String> processedMessageIds = Collections.synchronizedSet(
            Collections.newSetFromMap(
                    new LinkedHashMap<String, Boolean>(MAX_DEDUP_CACHE_SIZE, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_DEDUP_CACHE_SIZE;
                        }
                    }
            )
    );

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator) {
        this(localPeerId, peerRouter, coordinator, new InMemoryMessageHistoryStore(), new InMemoryDeliveryOutbox(), new InMemoryGroupDeliveryOutbox(), new SequenceGenerator());
    }

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator,
                                              MessageHistoryStore historyStore) {
        this(localPeerId, peerRouter, coordinator, historyStore, new InMemoryDeliveryOutbox(), new InMemoryGroupDeliveryOutbox(), new SequenceGenerator());
    }

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator,
                                              MessageHistoryStore historyStore,
                                              DeliveryOutbox outbox) {
        this(localPeerId, peerRouter, coordinator, historyStore, outbox, new InMemoryGroupDeliveryOutbox(), new SequenceGenerator());
    }

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator,
                                              MessageHistoryStore historyStore,
                                              DeliveryOutbox outbox,
                                              GroupDeliveryOutbox groupOutbox) {
        this(localPeerId, peerRouter, coordinator, historyStore, outbox, groupOutbox, new SequenceGenerator());
    }

    public DefaultApplicationMessagingService(PeerId localPeerId,
                                              PeerRouter peerRouter,
                                              PeerConnectionCoordinator coordinator,
                                              MessageHistoryStore historyStore,
                                              DeliveryOutbox outbox,
                                              GroupDeliveryOutbox groupOutbox,
                                              SequenceGenerator sequenceGenerator) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
        this.peerRouter = Objects.requireNonNull(peerRouter, "peerRouter must not be null");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.groupOutbox = Objects.requireNonNull(groupOutbox, "groupOutbox must not be null");
        this.sequenceGenerator = Objects.requireNonNull(sequenceGenerator, "sequenceGenerator must not be null");
        this.conversationManager = new ConversationManager(localPeerId);

        Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.retryManager = new DeliveryRetryManager(
                localPeerId,
                outbox,
                groupOutbox,
                historyStore,
                peerRouter,
                (messageId, success) -> {
                    if (success) {
                        updateState(messageId, MessageState.SENT);
                    } else {
                        updateState(messageId, MessageState.FAILED);
                    }
                },
                DeliveryRetryManager.DEFAULT_MAX_ATTEMPTS
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

    public GroupDeliveryOutbox getGroupOutbox() {
        return groupOutbox;
    }

    public DeliveryRetryManager getRetryManager() {
        return retryManager;
    }

    public ConversationManager getConversationManager() {
        return conversationManager;
    }

    public SequenceGenerator getSequenceGenerator() {
        return sequenceGenerator;
    }

    public void addRetryAttemptListener(RetryAttemptListener listener) {
        retryManager.addRetryAttemptListener(listener);
    }

    public void removeRetryAttemptListener(RetryAttemptListener listener) {
        retryManager.removeRetryAttemptListener(listener);
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

        // Assign sequence number if not already present
        long sequenceNumber = message.sequenceNumber() > 0
                ? message.sequenceNumber()
                : sequenceGenerator.nextSequence(destination);
        ApplicationMessage sequencedMessage = message.sequenceNumber() > 0
                ? message
                : message.withSequence(sequenceNumber);

        // 1. Direct message path - persist history
        try {
            historyStore.save(sequencedMessage, MessageState.CREATED);
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Message already in history store: " + sequencedMessage.messageId());
        }

        // 2. Register delivery intent in outbox
        try {
            outbox.enqueue(OutboxEntry.pending(sequencedMessage.messageId(), destination, conversationId));
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Delivery intent already in outbox: " + sequencedMessage.messageId());
        }

        updateState(sequencedMessage.messageId(), MessageState.CREATED);

        byte[] appTextBytes = sequencedMessage.toPayload();
        // Frame: [APP_MSG_SEQUENCED_CHAT:1][Seq:8][Text:N]
        byte[] framedPayload = new byte[1 + 8 + appTextBytes.length];
        framedPayload[0] = APP_MSG_SEQUENCED_CHAT;
        ByteBuffer.wrap(framedPayload, 1, 8).putLong(sequenceNumber);
        System.arraycopy(appTextBytes, 0, framedPayload, 9, appTextBytes.length);

        Message protocolMessage = new Message(
                MessageType.MESSAGE,
                localPeerId.value(),
                sequencedMessage.messageId(),
                framedPayload
        );

        // 3. Attempt delivery via multi-path router
        try {
            LOGGER.fine(() -> "Dispatching application message " + sequencedMessage.messageId()
                    + " (seq=" + sequenceNumber + ") to " + destination.value());
            boolean dispatched = peerRouter.send(destination, protocolMessage);
            if (dispatched) {
                updateState(sequencedMessage.messageId(), MessageState.SENT);
            } else {
                updateState(sequencedMessage.messageId(), MessageState.BUFFERED);
            }
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to route application message " + sequencedMessage.messageId() + ": " + e.getMessage());
            updateState(sequencedMessage.messageId(), MessageState.FAILED);
        }
    }

    /**
     * Application-layer fan-out implementation for Group Messages with per-recipient delivery tracking.
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

        // 2. Prepare target recipients (all participants except self)
        Set<PeerId> participants = group.participants();
        Set<PeerId> targets = new HashSet<>();
        for (PeerId p : participants) {
            if (!p.equals(localPeerId)) {
                targets.add(p);
            }
        }

        // 3. Enqueue per-recipient delivery intents
        try {
            groupOutbox.enqueueGroupMessage(message.messageId(), groupId, targets);
        } catch (Exception e) {
            LOGGER.fine("Group outbox intents already enqueued: " + message.messageId());
        }

        // 4. Wire frame group payload
        byte[] groupIdBytes = groupId.value().getBytes(StandardCharsets.UTF_8);
        byte[] appTextBytes = message.toPayload();
        int payloadLen = 1 + 2 + groupIdBytes.length + appTextBytes.length;
        byte[] framedPayload = new byte[payloadLen];
        framedPayload[0] = APP_MSG_GROUP_CHAT;
        framedPayload[1] = (byte) ((groupIdBytes.length >> 8) & 0xFF);
        framedPayload[2] = (byte) (groupIdBytes.length & 0xFF);
        System.arraycopy(groupIdBytes, 0, framedPayload, 3, groupIdBytes.length);
        System.arraycopy(appTextBytes, 0, framedPayload, 3 + groupIdBytes.length, appTextBytes.length);

        // 5. Dispatch immediate attempt to all targets
        boolean atLeastOneDispatched = false;
        for (PeerId participant : targets) {
            Message protocolMessage = new Message(
                    MessageType.MESSAGE,
                    localPeerId.value(),
                    message.messageId(),
                    framedPayload
            );

            try {
                LOGGER.fine(() -> "Routing group message " + message.messageId() + " to " + participant.value());
                boolean dispatched = peerRouter.send(participant, protocolMessage);
                if (dispatched) {
                    atLeastOneDispatched = true;
                }
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
        long seq = sequenceGenerator.nextSequence(destination);
        ApplicationMessage msg = new ApplicationMessage(
                UUID.randomUUID().toString(),
                localPeerId,
                content,
                java.time.Instant.now(),
                conversationId,
                seq
        );
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
            case APP_MSG_CHAT -> handleInboundChat(senderPeerIdStr, protocolMessage, false);
            case APP_MSG_SEQUENCED_CHAT -> handleInboundChat(senderPeerIdStr, protocolMessage, true);
            case APP_MSG_ACK -> handleInboundAck(senderPeerIdStr, protocolMessage);
            case APP_MSG_GROUP_CHAT -> handleInboundGroupChat(senderPeerIdStr, protocolMessage);
            default -> LOGGER.warning("Unknown application payload type: " + appType);
        }
    }

    private void handleInboundChat(String senderPeerIdStr, Message protocolMessage, boolean sequenced) {
        try {
            PeerId sender = PeerId.of(senderPeerIdStr);
            byte[] rawPayload = protocolMessage.payload();
            String messageId = protocolMessage.messageId();

            long sequenceNumber = 0L;
            byte[] textPayload;

            if (sequenced && rawPayload.length >= 9) {
                sequenceNumber = ByteBuffer.wrap(rawPayload, 1, 8).getLong();
                textPayload = new byte[rawPayload.length - 9];
                System.arraycopy(rawPayload, 9, textPayload, 0, textPayload.length);
            } else {
                textPayload = new byte[rawPayload.length - 1];
                System.arraycopy(rawPayload, 1, textPayload, 0, textPayload.length);
            }

            // Deduplication Check
            if (processedMessageIds.contains(messageId)) {
                LOGGER.fine(() -> "Duplicate message " + messageId + " received; acknowledging without re-dispatching.");
                sendApplicationAck(sender, messageId);
                return;
            }
            processedMessageIds.add(messageId);

            ConversationId conversationId = ConversationManager.deriveDirectConversationId(localPeerId, sender);
            ApplicationMessage appMessage = ApplicationMessage.fromPayload(
                    messageId,
                    sender,
                    textPayload,
                    conversationId,
                    sequenceNumber
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

            sendApplicationAck(sender, messageId);

        } catch (Exception e) {
            LOGGER.warning("Inbound application parsing failed: " + e.getMessage());
        }
    }

    private void handleInboundGroupChat(String senderPeerIdStr, Message protocolMessage) {
        try {
            PeerId sender = PeerId.of(senderPeerIdStr);
            byte[] rawPayload = protocolMessage.payload();
            String messageId = protocolMessage.messageId();

            int groupLen = ((rawPayload[1] & 0xFF) << 8) | (rawPayload[2] & 0xFF);
            String groupIdStr = new String(rawPayload, 3, groupLen, StandardCharsets.UTF_8);
            ConversationId groupId = new ConversationId(groupIdStr);

            int contentOffset = 3 + groupLen;
            byte[] textPayload = new byte[rawPayload.length - contentOffset];
            System.arraycopy(rawPayload, contentOffset, textPayload, 0, textPayload.length);

            // Deduplication Check
            if (processedMessageIds.contains(messageId)) {
                LOGGER.fine(() -> "Duplicate group message " + messageId + " received; acknowledging.");
                sendApplicationAck(sender, messageId);
                return;
            }
            processedMessageIds.add(messageId);

            ApplicationMessage appMessage = ApplicationMessage.fromPayload(
                    messageId,
                    sender,
                    textPayload,
                    groupId
            );

            if (conversationManager.getGroupConversation(groupId).isEmpty()) {
                GroupConversation autoReconstructed = new GroupConversation(
                        groupId,
                        "Auto Group " + groupIdStr.substring(Math.min(groupIdStr.length(), 11)),
                        Set.of(localPeerId, sender)
                );
                conversationManager.registerGroupConversation(autoReconstructed);
            }

            try {
                historyStore.save(appMessage, MessageState.DELIVERED);
            } catch (Exception e) {
                LOGGER.fine("Group message already in store: " + appMessage.messageId());
            }

            for (ApplicationMessageListener listener : messageListeners) {
                try {
                    listener.onMessage(appMessage);
                } catch (Exception e) {
                    LOGGER.warning("Exception in ApplicationMessageListener: " + e.getMessage());
                }
            }

            sendApplicationAck(sender, messageId);

        } catch (Exception e) {
            LOGGER.warning("Inbound group parsing failed: " + e.getMessage());
        }
    }

    private void handleInboundAck(String senderPeerIdStr, Message protocolMessage) {
        byte[] rawPayload = protocolMessage.payload();
        byte[] ackedIdBytes = new byte[rawPayload.length - 1];
        System.arraycopy(rawPayload, 1, ackedIdBytes, 0, ackedIdBytes.length);
        String ackedMessageId = new String(ackedIdBytes, StandardCharsets.UTF_8);

        LOGGER.fine(() -> "Received remote delivery receipt for message " + ackedMessageId + " from " + senderPeerIdStr);

        // 1. Direct message outbox completion
        Optional<OutboxEntry> directEntry = outbox.findByMessageId(ackedMessageId);
        if (directEntry.isPresent()) {
            outbox.markCompleted(ackedMessageId);
            updateState(ackedMessageId, MessageState.DELIVERED);
            return;
        }

        // 2. Group message outbox completion (per recipient)
        try {
            PeerId sender = PeerId.of(senderPeerIdStr);
            groupOutbox.markRecipientCompleted(ackedMessageId, sender);
            if (groupOutbox.allRecipientsCompleted(ackedMessageId)) {
                updateState(ackedMessageId, MessageState.DELIVERED);
            }
        } catch (Exception e) {
            LOGGER.warning("Error processing group ACK: " + e.getMessage());
        }
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