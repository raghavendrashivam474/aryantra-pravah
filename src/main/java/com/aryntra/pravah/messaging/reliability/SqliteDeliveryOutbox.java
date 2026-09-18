package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable SQLite-backed implementation of DeliveryOutbox.
 *
 * Guarantees:
 * <ul>
 *   <li>Delivery intents survive application process restarts</li>
 *   <li>Deterministic ordering using SQLite autoincrement primary key `seq`</li>
 *   <li>Strict uniqueness on `message_id`</li>
 *   <li>Destination identity persisted as logical PeerId (no socket or connection metadata)</li>
 *   <li>Thread-safe access through synchronized database operations</li>
 * </ul>
 */
public class SqliteDeliveryOutbox implements DeliveryOutbox, AutoCloseable {

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteDeliveryOutbox(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath must not be null");
        try {
            String url = "jdbc:sqlite:" + databasePath.toAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            this.ownsConnection = true;
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite connection: " + e.getMessage(), e);
        }
    }

    public SqliteDeliveryOutbox(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.ownsConnection = false;
        initSchema();
    }

    private void initSchema() {
        String createTableSql = """
                CREATE TABLE IF NOT EXISTS outbox (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT NOT NULL UNIQUE,
                    destination_peer_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL
                );
                """;

        String createStatusSeqIndex = """
                CREATE INDEX IF NOT EXISTS idx_outbox_status_seq
                ON outbox(status, seq);
                """;

        String createPeerIndex = """
                CREATE INDEX IF NOT EXISTS idx_outbox_peer_status_seq
                ON outbox(destination_peer_id, status, seq);
                """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSql);
            stmt.execute(createStatusSeqIndex);
            stmt.execute(createPeerIndex);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize outbox schema: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void enqueue(OutboxEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");

        String sql = """
                INSERT INTO outbox (message_id, destination_peer_id, conversation_id, status, created_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?);
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entry.messageId());
            ps.setString(2, entry.destination().value());
            ps.setString(3, entry.conversationId().value());
            ps.setString(4, entry.state().name());
            ps.setLong(5, entry.createdAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage() != null && (e.getMessage().contains("UNIQUE") || e.getMessage().contains("constraint"))) {
                throw new IllegalArgumentException("Outbox entry already exists for messageId: " + entry.messageId(), e);
            }
            throw new RuntimeException("Failed to enqueue outbox entry: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<OutboxEntry> findPending() {
        String sql = """
                SELECT message_id, destination_peer_id, conversation_id, status, created_at_epoch_ms
                FROM outbox
                WHERE status = 'PENDING'
                ORDER BY seq ASC;
                """;

        List<OutboxEntry> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapResultSet(rs));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query pending outbox entries: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<OutboxEntry> findPendingForPeer(PeerId destination) {
        Objects.requireNonNull(destination, "destination must not be null");

        String sql = """
                SELECT message_id, destination_peer_id, conversation_id, status, created_at_epoch_ms
                FROM outbox
                WHERE status = 'PENDING' AND destination_peer_id = ?
                ORDER BY seq ASC;
                """;

        List<OutboxEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, destination.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapResultSet(rs));
                }
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query pending entries for peer: " + destination.value(), e);
        }
    }

    @Override
    public synchronized Optional<OutboxEntry> findByMessageId(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        String sql = """
                SELECT message_id, destination_peer_id, conversation_id, status, created_at_epoch_ms
                FROM outbox
                WHERE message_id = ?;
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query outbox by messageId: " + messageId, e);
        }
    }

    @Override
    public synchronized void markCompleted(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        String sql = """
                UPDATE outbox
                SET status = 'COMPLETED'
                WHERE message_id = ?;
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark outbox completed: " + messageId, e);
        }
    }

    @Override
    public synchronized void remove(String messageId) {
        if (messageId == null) {
            return;
        }

        String sql = "DELETE FROM outbox WHERE message_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove outbox entry: " + messageId, e);
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (ownsConnection && connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private OutboxEntry mapResultSet(ResultSet rs) throws SQLException {
        String msgId = rs.getString("message_id");
        PeerId dest = PeerId.of(rs.getString("destination_peer_id"));
        ConversationId convId = new ConversationId(rs.getString("conversation_id"));
        OutboxState state = OutboxState.valueOf(rs.getString("status"));
        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at_epoch_ms"));
        return new OutboxEntry(msgId, dest, convId, state, createdAt);
    }
}