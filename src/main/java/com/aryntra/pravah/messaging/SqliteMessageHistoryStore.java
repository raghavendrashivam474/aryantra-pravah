package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable SQLite implementation of MessageHistoryStore.
 *
 * Guarantees:
 * - Deterministic ordering via SQLite autoincrement primary key `seq`
 * - Strict uniqueness on `message_id`
 * - Persistence of PeerId sender identity (no transport metadata)
 * - Durability across process and connection restarts
 */
public class SqliteMessageHistoryStore implements MessageHistoryStore, AutoCloseable {

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteMessageHistoryStore(Path databasePath) {
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

    public SqliteMessageHistoryStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.ownsConnection = false;
        initSchema();
    }

    private void initSchema() {
        String createTableSql = """
                CREATE TABLE IF NOT EXISTS messages (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT NOT NULL UNIQUE,
                    conversation_id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp_epoch_ms INTEGER NOT NULL,
                    state TEXT NOT NULL
                );
                """;

        String createIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_messages_conv_seq
                ON messages(conversation_id, seq);
                """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSql);
            stmt.execute(createIndexSql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create message table schema: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void save(ApplicationMessage message, MessageState state) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(state, "state must not be null");

        String sql = """
                INSERT INTO messages (message_id, conversation_id, sender_id, content, timestamp_epoch_ms, state)
                VALUES (?, ?, ?, ?, ?, ?);
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, message.messageId());
            ps.setString(2, message.conversationId().value());
            ps.setString(3, message.sender().value());
            ps.setString(4, message.content());
            ps.setLong(5, message.timestamp().toEpochMilli());
            ps.setString(6, state.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage() != null && (e.getMessage().contains("UNIQUE") || e.getMessage().contains("constraint"))) {
                throw new IllegalArgumentException("Message already exists: " + message.messageId(), e);
            }
            throw new RuntimeException("Failed to save message: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<ApplicationMessage> find(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        String sql = """
                SELECT message_id, conversation_id, sender_id, content, timestamp_epoch_ms
                FROM messages
                WHERE message_id = ?;
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapMessage(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find message: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<ApplicationMessage> getConversationHistory(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");

        String sql = """
                SELECT message_id, conversation_id, sender_id, content, timestamp_epoch_ms
                FROM messages
                WHERE conversation_id = ?
                ORDER BY seq ASC;
                """;

        List<ApplicationMessage> history = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.add(mapMessage(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get conversation history: " + e.getMessage(), e);
        }
        return history;
    }

    @Override
    public synchronized void updateState(String messageId, MessageState state) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(state, "state must not be null");

        String sql = """
                UPDATE messages
                SET state = ?
                WHERE message_id = ?;
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, messageId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IllegalArgumentException("Message not found: " + messageId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update message state: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<MessageState> findState(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        String sql = "SELECT state FROM messages WHERE message_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(MessageState.valueOf(rs.getString("state")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find message state: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void delete(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        String sql = "DELETE FROM messages WHERE message_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete message: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        if (ownsConnection) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close SQLite connection: " + e.getMessage(), e);
            }
        }
    }

    private ApplicationMessage mapMessage(ResultSet rs) throws SQLException {
        String messageId = rs.getString("message_id");
        String convIdStr = rs.getString("conversation_id");
        String senderStr = rs.getString("sender_id");
        String content = rs.getString("content");
        long timestampMs = rs.getLong("timestamp_epoch_ms");

        return new ApplicationMessage(
                messageId,
                PeerId.of(senderStr),
                content,
                Instant.ofEpochMilli(timestampMs),
                new ConversationId(convIdStr)
        );
    }
}