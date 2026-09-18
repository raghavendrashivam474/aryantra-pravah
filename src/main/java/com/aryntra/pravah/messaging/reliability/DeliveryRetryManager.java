package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ApplicationMessage;
import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.messaging.MessageHistoryStore;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Manages automated delivery retries for both direct and group outbox messages
 * upon peer reconnection, enforcing bounded retry policies and observability.
 */
public class DeliveryRetryManager {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private static final Logger LOGGER = Logger.getLogger(DeliveryRetryManager.class.getName());
    private static final byte APP_MSG_CHAT       = 0x01;
    private static final byte APP_MSG_GROUP_CHAT = 0x03;

    private final PeerId localPeerId;
    private final DeliveryOutbox outbox;
    private final GroupDeliveryOutbox groupOutbox;
    private final MessageHistoryStore historyStore;
    private final PeerRouter peerRouter;
    private final BiConsumer<String, Boolean> deliveryAttemptCallback;
    private final int maxAttempts;
    private final List<RetryAttemptListener> listeners = new CopyOnWriteArrayList<>();

    public DeliveryRetryManager(PeerId localPeerId,
                                DeliveryOutbox outbox,
                                MessageHistoryStore historyStore,
                                PeerRouter peerRouter,
                                BiConsumer<String, Boolean> deliveryAttemptCallback) {
        this(localPeerId, outbox, new InMemoryGroupDeliveryOutbox(), historyStore, peerRouter, deliveryAttemptCallback, DEFAULT_MAX_ATTEMPTS);
    }

    public DeliveryRetryManager(PeerId localPeerId,
                                DeliveryOutbox outbox,
                                MessageHistoryStore historyStore,
                                PeerRouter peerRouter,
                                BiConsumer<String, Boolean> deliveryAttemptCallback,
                                int maxAttempts) {
        this(localPeerId, outbox, new InMemoryGroupDeliveryOutbox(), historyStore, peerRouter, deliveryAttemptCallback, maxAttempts);
    }

    public DeliveryRetryManager(PeerId localPeerId,
                                DeliveryOutbox outbox,
                                GroupDeliveryOutbox groupOutbox,
                                MessageHistoryStore historyStore,
                                PeerRouter peerRouter,
                                BiConsumer<String, Boolean> deliveryAttemptCallback,
                                int maxAttempts) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.groupOutbox = Objects.requireNonNull(groupOutbox, "groupOutbox must not be null");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore must not be null");
        this.peerRouter = Objects.requireNonNull(peerRouter, "peerRouter must not be null");
        this.deliveryAttemptCallback = Objects.requireNonNull(deliveryAttemptCallback, "deliveryAttemptCallback must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public GroupDeliveryOutbox getGroupOutbox() {
        return groupOutbox;
    }

    public void addRetryAttemptListener(RetryAttemptListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeRetryAttemptListener(RetryAttemptListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Executes bounded retry attempts for all pending direct and group messages destined for a reconnected peer.
     *
     * @param destination the peer that has become available/connected
     * @return the total number of direct and group messages successfully dispatched
     */
    public synchronized int retryPendingForPeer(PeerId destination) {
        Objects.requireNonNull(destination, "destination must not be null");

        int dispatchedCount = 0;

        // 1. Direct messages retry sweep
        List<OutboxEntry> pendingDirect = outbox.findPendingForPeer(destination);
        if (!pendingDirect.isEmpty()) {
            LOGGER.info(() -> "Peer " + destination.value() + " reconnected. Retrying " + pendingDirect.size() + " direct message(s).");
            for (OutboxEntry entry : pendingDirect) {
                String messageId = entry.messageId();
                int nextAttempt = entry.attemptCount() + 1;
                outbox.updateAttemptCount(messageId, nextAttempt);

                Optional<ApplicationMessage> appMsgOpt = historyStore.find(messageId);
                if (appMsgOpt.isEmpty()) {
                    LOGGER.warning("Pending direct outbox entry has no corresponding message in history: " + messageId);
                    continue;
                }

                ApplicationMessage appMsg = appMsgOpt.get();
                byte[] appTextBytes = appMsg.toPayload();
                byte[] framedPayload = new byte[1 + appTextBytes.length];
                framedPayload[0] = APP_MSG_CHAT;
                System.arraycopy(appTextBytes, 0, framedPayload, 1, appTextBytes.length);

                Message protocolMessage = new Message(
                        MessageType.MESSAGE,
                        localPeerId.value(),
                        messageId,
                        framedPayload
                );

                try {
                    LOGGER.fine(() -> "Retrying direct message " + messageId + " to " + destination.value()
                            + " (attempt " + nextAttempt + "/" + maxAttempts + ")");
                    peerRouter.send(destination, protocolMessage);
                    deliveryAttemptCallback.accept(messageId, true);
                    dispatchedCount++;

                    notifyListeners(new RetryAttemptEvent(
                            messageId, destination, nextAttempt, System.currentTimeMillis(), RetryAttemptEvent.Outcome.DISPATCHED));

                } catch (Exception e) {
                    LOGGER.warning(() -> "Retry direct attempt " + nextAttempt + " failed for " + messageId + ": " + e.getMessage());
                    deliveryAttemptCallback.accept(messageId, false);

                    if (nextAttempt >= maxAttempts) {
                        outbox.updateState(messageId, OutboxState.ABANDONED);
                        notifyListeners(new RetryAttemptEvent(
                                messageId, destination, nextAttempt, System.currentTimeMillis(), RetryAttemptEvent.Outcome.ABANDONED));
                    } else {
                        notifyListeners(new RetryAttemptEvent(
                                messageId, destination, nextAttempt, System.currentTimeMillis(), RetryAttemptEvent.Outcome.FAILED));
                    }
                }
            }
        }

        // 2. Group messages per-recipient retry sweep
        List<String> pendingGroupMsgIds = groupOutbox.findPendingMessageIdsForPeer(destination);
        if (!pendingGroupMsgIds.isEmpty()) {
            LOGGER.info(() -> "Peer " + destination.value() + " reconnected. Retrying " + pendingGroupMsgIds.size() + " group message(s).");
            for (String messageId : pendingGroupMsgIds) {
                Optional<ApplicationMessage> appMsgOpt = historyStore.find(messageId);
                if (appMsgOpt.isEmpty()) {
                    LOGGER.warning("Pending group outbox entry has no corresponding message in history: " + messageId);
                    continue;
                }

                ApplicationMessage appMsg = appMsgOpt.get();
                byte[] framedPayload = frameGroupPayload(appMsg.conversationId(), appMsg);
                int nextAttempt = groupOutbox.incrementAttemptCount(messageId, destination);

                Message protocolMessage = new Message(
                        MessageType.MESSAGE,
                        localPeerId.value(),
                        messageId,
                        framedPayload
                );

                try {
                    LOGGER.fine(() -> "Retrying group message " + messageId + " to " + destination.value()
                            + " (attempt " + nextAttempt + "/" + maxAttempts + ")");
                    peerRouter.send(destination, protocolMessage);
                    deliveryAttemptCallback.accept(messageId, true);
                    dispatchedCount++;

                    notifyListeners(new RetryAttemptEvent(
                            messageId, destination, nextAttempt, System.currentTimeMillis(), RetryAttemptEvent.Outcome.DISPATCHED));

                } catch (Exception e) {
                    LOGGER.warning(() -> "Retry group attempt " + nextAttempt + " failed for " + messageId + " to " + destination.value() + ": " + e.getMessage());
                    deliveryAttemptCallback.accept(messageId, false);

                    if (nextAttempt >= maxAttempts) {
                        groupOutbox.markRecipientAbandoned(messageId, destination);
                        notifyListeners(new RetryAttemptEvent(
                                messageId, destination, nextAttempt, System.currentTimeMillis(), RetryAttemptEvent.Outcome.ABANDONED));
                    } else {
                        notifyListeners(new RetryAttemptEvent(
                                messageId, destination, nextAttempt, System.currentTimeMillis(), RetryAttemptEvent.Outcome.FAILED));
                    }
                }
            }
        }

        return dispatchedCount;
    }

    private byte[] frameGroupPayload(ConversationId groupId, ApplicationMessage message) {
        byte[] groupIdBytes = groupId.value().getBytes(StandardCharsets.UTF_8);
        byte[] appTextBytes = message.toPayload();

        int payloadLen = 1 + 2 + groupIdBytes.length + appTextBytes.length;
        byte[] framedPayload = new byte[payloadLen];

        framedPayload[0] = APP_MSG_GROUP_CHAT;
        framedPayload[1] = (byte) ((groupIdBytes.length >> 8) & 0xFF);
        framedPayload[2] = (byte) (groupIdBytes.length & 0xFF);
        System.arraycopy(groupIdBytes, 0, framedPayload, 3, groupIdBytes.length);
        System.arraycopy(appTextBytes, 0, framedPayload, 3 + groupIdBytes.length, appTextBytes.length);
        return framedPayload;
    }

    private void notifyListeners(RetryAttemptEvent event) {
        for (RetryAttemptListener listener : listeners) {
            try {
                listener.onRetryAttempt(event);
            } catch (Exception e) {
                LOGGER.warning("Exception in RetryAttemptListener: " + e.getMessage());
            }
        }
    }
}