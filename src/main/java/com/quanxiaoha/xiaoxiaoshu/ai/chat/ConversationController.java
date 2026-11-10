package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author: 犬小哈
 * @description: 会话与历史消息接口
 **/
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ChatRepository chatRepository;
    private final ChatService chatService;

    public ConversationController(ChatRepository chatRepository, ChatService chatService) {
        this.chatRepository = chatRepository;
        this.chatService = chatService;
    }

    @GetMapping
    public List<Conversation> list() {
        return chatRepository.listConversations();
    }

    @PostMapping
    public Conversation create(@RequestBody(required = false) Map<String, String> body) {
        String mode = body == null ? "assistant" : body.getOrDefault("mode", "assistant");
        String id = UUID.randomUUID().toString().replace("-", "");
        chatRepository.createConversation(id, "service".equals(mode) ? "service" : "assistant");
        return chatRepository.findConversation(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "会话创建失败"));
    }

    @GetMapping("/{id}/messages")
    public List<StoredMessage> messages(@PathVariable String id) {
        return chatRepository.listMessages(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        chatRepository.deleteConversation(id);
        chatService.clearMemory(id);
        return Map.of("success", true);
    }
}
