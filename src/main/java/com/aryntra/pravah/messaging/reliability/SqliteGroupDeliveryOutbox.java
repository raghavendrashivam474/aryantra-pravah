package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * SQLite-backed persistent implementation of GroupDeliveryOutbox.
 *
 * Uses a separate group_outbox table with composite unique key
 * (message_id, recipient_peer_id) to track per-recipient delivery state.
 */
public class SqliteGroupDeliveryOutbox implements GroupDeliveryOutbox, AutoCloseable {

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteGroupDeliveryOutbox(Path databasePath) {
        Objects.requireNonNull(databasePath);
        try {
            String url = "jdbc:sqlite:" + databasePath.toAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            this.ownsConnection = true;
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init group outbox: " + e.getMessage(), e);
        }
    }

    public SqliteGroupDeliveryOutbox(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
        this.ownsConnection = false;
        initSchema();
    }

    private void initSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS group_outbox (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    recipient_peer_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    created_at_epoch_ms INTEGER NOT NULL,
                    UNIQUE(message_id, recipient_peer_id)
                );
                """;
        String idx1 = "CREATE INDEX IF NOT EXISTS idx_group_outbox_msg ON group_outbox(message_id);";
        String idx2 = "CREATE INDEX IF NOT EXISTS idx_group_outbox_peer_status ON group_outbox(recipient_peer_id, status);";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            stmt.execute(idx1);
            stmt.execute(idx2);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init group outbox schema: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void enqueueGroupMessage(String messageId, ConversationId groupId, Set<PeerId> recipients) {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(recipients);
        String sql = "INSERT OR IGNORE INTO group_outbox (message_id, group_id, recipient_peer_id, status, attempt_count, created_at_epoch_ms) VALUES (?, ?, ?, 'PENDING', 0, ?);";
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (PeerId peer : recipients) {
                ps.setString(1, messageId);
                ps.setString(2, groupId.value());
                ps.setString(3, peer.value());
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to enqueue group message: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<PeerId> findPendingRecipients(String messageId) {
        String sql = "SELECT recipient_peer_id FROM group_outbox WHERE message_id = ? AND status = 'PENDING';";
        List<PeerId> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(PeerId.of(rs.getString("recipient_peer_id")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query pending recipients: " + e.getMessage(), e);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<String> findPendingMessageIdsForPeer(PeerId peer) {
        String sql = "SELECT message_id FROM group_outbox WHERE recipient_peer_id = ? AND status = 'PENDING';";
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, peer.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("message_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query pending messages for peer: " + e.getMessage(), e);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void markRecipientCompleted(String messageId, PeerId recipient) {
        updateStatus(messageId, recipient, "COMPLETED");
    }

    @Override
    public synchronized void markRecipientAbandoned(String messageId, PeerId recipient) {
        updateStatus(messageId, recipient, "ABANDONED");
    }

    @Override
    public synchronized int incrementAttemptCount(String messageId, PeerId recipient) {
        String sql = "UPDATE group_outbox SET attempt_count = attempt_count + 1 WHERE message_id = ? AND recipient_peer_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setString(2, recipient.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to increment attempt count: " + e.getMessage(), e);
        }
        return getAttemptCount(messageId, recipient);
    }

    @Override
    public synchronized int getAttemptCount(String messageId, PeerId recipient) {
        String sql = "SELECT attempt_count FROM group_outbox WHERE message_id = ? AND recipient_peer_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setString(2, recipient.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("attempt_count");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get attempt count: " + e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public synchronized OutboxState getRecipientState(String messageId, PeerId recipient) {
        String sql = "SELECT status FROM group_outbox WHERE message_id = ? AND recipient_peer_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setString(2, recipient.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return OutboxState.valueOf(rs.getString("status"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get recipient state: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public synchronized boolean allRecipientsCompleted(String messageId) {
        String sql = "SELECT COUNT(*) FROM group_outbox WHERE message_id = ? AND status != 'COMPLETED';";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) == 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check completion: " + e.getMessage(), e);
        }
        return false;
    }

    private void updateStatus(String messageId, PeerId recipient, String status) {
        String sql = "UPDATE group_outbox SET status = ? WHERE message_id = ? AND recipient_peer_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, messageId);
            ps.setString(3, recipient.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update status: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (ownsConnection && connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}