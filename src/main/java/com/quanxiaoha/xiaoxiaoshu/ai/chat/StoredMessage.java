package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import java.time.LocalDateTime;

/**
 * @author: 犬小哈
 * @description: 已持久化的聊天消息
 **/
public record StoredMessage(Long id, String conversationId, String role, String content,
                            String citations, String images, LocalDateTime createdAt) {
}
