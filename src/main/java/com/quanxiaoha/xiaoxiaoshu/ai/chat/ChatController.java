package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author: 犬小哈
 * @description: 流式聊天接口
 **/
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        if (request == null
                || ((request.message() == null || request.message().isBlank()) && request.imageList().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }
        SseEmitter emitter = new SseEmitter(0L);
        chatService.stream(request, emitter);
        return emitter;
    }
}
