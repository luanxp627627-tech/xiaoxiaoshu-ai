package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import java.time.LocalDateTime;

/**
 * @author: 犬小哈
 * @description: 会话
 **/
public record Conversation(String id, String title, String mode, LocalDateTime createdAt,
                           LocalDateTime updatedAt, int messageCount) {
}
