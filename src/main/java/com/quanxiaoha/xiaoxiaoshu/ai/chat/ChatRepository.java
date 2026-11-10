package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author: 犬小哈
 * @description: 会话与消息持久化（MySQL 8）
 **/
@Repository
public class ChatRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Conversation> CONVERSATION_MAPPER = (rs, rowNum) -> new Conversation(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("mode"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getInt("message_count")
    );

    private static final RowMapper<StoredMessage> MESSAGE_MAPPER = (rs, rowNum) -> new StoredMessage(
            rs.getLong("id"),
            rs.getString("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("citations"),
            rs.getString("images"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public ChatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createConversation(String id, String mode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chat_conversation WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO chat_conversation (id, title, mode, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    id, "新对话", mode, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
        }
    }

    public Optional<Conversation> findConversation(String id) {
        List<Conversation> list = jdbcTemplate.query(
                "SELECT c.id, c.title, c.mode, c.created_at, c.updated_at, " +
                        "(SELECT COUNT(1) FROM chat_message m WHERE m.conversation_id = c.id) AS message_count " +
                        "FROM chat_conversation c WHERE c.id = ?",
                CONVERSATION_MAPPER, id);
        return list.stream().findFirst();
    }

    public List<Conversation> listConversations() {
        return jdbcTemplate.query(
                "SELECT c.id, c.title, c.mode, c.created_at, c.updated_at, " +
                        "(SELECT COUNT(1) FROM chat_message m WHERE m.conversation_id = c.id) AS message_count " +
                        "FROM chat_conversation c ORDER BY c.updated_at DESC",
                CONVERSATION_MAPPER);
    }

    public void updateTitle(String id, String title) {
        jdbcTemplate.update("UPDATE chat_conversation SET title = ? WHERE id = ?", title, id);
    }

    public void touch(String id) {
        jdbcTemplate.update("UPDATE chat_conversation SET updated_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void saveMessage(String conversationId, String role, String content, String citations, String images) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (conversation_id, role, content, citations, images, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                conversationId, role, content, citations, images, Timestamp.valueOf(LocalDateTime.now()));
        touch(conversationId);
    }

    public List<StoredMessage> listMessages(String conversationId) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, role, content, citations, images, created_at FROM chat_message " +
                        "WHERE conversation_id = ? ORDER BY id ASC",
                MESSAGE_MAPPER, conversationId);
    }

    public List<StoredMessage> recentMessages(String conversationId, int limit) {
        List<StoredMessage> list = jdbcTemplate.query(
                "SELECT * FROM (SELECT id, conversation_id, role, content, citations, images, created_at " +
                        "FROM chat_message WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) recent ORDER BY id ASC",
                MESSAGE_MAPPER, conversationId, limit);
        return list;
    }

    public void deleteConversation(String id) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id = ?", id);
        jdbcTemplate.update("DELETE FROM chat_conversation WHERE id = ?", id);
    }
}
