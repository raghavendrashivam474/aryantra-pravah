package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ApplicationMessage;
import com.aryntra.pravah.messaging.MessageHistoryStore;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Manages automated delivery retries for queued outbox messages upon peer reconnection.
 *
 * <p>Principles:
 * <ul>
 *   <li><b>Conservative Trigger:</b> Retries are event-driven upon peer CONNECTED/JOIN events, avoiding wasteful network polling.</li>
 *   <li><b>Deterministic Queue Order:</b> Retries pending items strictly in creation sequence (FIFO).</li>
 *   <li><b>Identity Invariance:</b> The retry uses the exact original {@code messageId}. No duplicate logical message IDs are generated.</li>
 *   <li><b>ACK Correlation:</b> Delivery completion is only established when an application-level ACK is received.</li>
 * </ul>
 */
public class DeliveryRetryManager {

    private static final Logger LOGGER = Logger.getLogger(DeliveryRetryManager.class.getName());
    private static final byte APP_MSG_CHAT = 0x01;

    private final PeerId localPeerId;
    private final DeliveryOutbox outbox;
    private final MessageHistoryStore historyStore;
    private final PeerRouter peerRouter;
    private final BiConsumer<String, Boolean> deliveryAttemptCallback;

    public DeliveryRetryManager(PeerId localPeerId,
                                DeliveryOutbox outbox,
                                MessageHistoryStore historyStore,
                                PeerRouter peerRouter,
                                BiConsumer<String, Boolean> deliveryAttemptCallback) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore must not be null");
        this.peerRouter = Objects.requireNonNull(peerRouter, "peerRouter must not be null");
        this.deliveryAttemptCallback = Objects.requireNonNull(deliveryAttemptCallback, "deliveryAttemptCallback must not be null");
    }

    /**
     * Executes retry attempts for all pending messages destined for a newly connected peer.
     *
     * @param destination the peer that has become available/connected
     * @return the number of messages successfully dispatched
     */
    public synchronized int retryPendingForPeer(PeerId destination) {
        Objects.requireNonNull(destination, "destination must not be null");

        List<OutboxEntry> pending = outbox.findPendingForPeer(destination);
        if (pending.isEmpty()) {
            return 0;
        }

        LOGGER.info(() -> "Peer " + destination.value() + " reconnected. Retrying " + pending.size() + " pending message(s).");
        int dispatchedCount = 0;

        for (OutboxEntry entry : pending) {
            String messageId = entry.messageId();
            Optional<ApplicationMessage> appMsgOpt = historyStore.find(messageId);
            if (appMsgOpt.isEmpty()) {
                LOGGER.warning("Pending outbox entry has no corresponding message in history store: " + messageId);
                continue;
            }

            ApplicationMessage appMsg = appMsgOpt.get();

            // Wire payload matching S4 frame: [0x01 (byte)][content bytes (UTF-8)]
            byte[] appTextBytes = appMsg.toPayload();
            byte[] framedPayload = new byte[1 + appTextBytes.length];
            framedPayload[0] = APP_MSG_CHAT;
            System.arraycopy(appTextBytes, 0, framedPayload, 1, appTextBytes.length);

            // Preserve original messageId
            Message protocolMessage = new Message(
                    MessageType.MESSAGE,
                    localPeerId.value(),
                    messageId,
                    framedPayload
            );

            try {
                LOGGER.fine(() -> "Retrying application message " + messageId + " to " + destination.value());
                peerRouter.send(destination, protocolMessage);
                deliveryAttemptCallback.accept(messageId, true);
                dispatchedCount++;
            } catch (Exception e) {
                LOGGER.warning(() -> "Retry delivery attempt failed for " + messageId + ": " + e.getMessage());
                deliveryAttemptCallback.accept(messageId, false);
                // On failure, message remains PENDING in outbox
            }
        }

        return dispatchedCount;
    }
}