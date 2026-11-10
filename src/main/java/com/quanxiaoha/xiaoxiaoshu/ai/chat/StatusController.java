package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import com.quanxiaoha.xiaoxiaoshu.ai.mcp.McpToolRegistry;
import com.quanxiaoha.xiaoxiaoshu.ai.rag.KnowledgeBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author: 犬小哈
 * @description: 运行时状态，前端据此展示演示模式提示
 **/
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final ChatService chatService;
    private final KnowledgeBase knowledgeBase;
    private final McpToolRegistry mcpToolRegistry;

    @Value("${app.web-search.enabled:true}")
    private boolean webSearchEnabled;

    public StatusController(ChatService chatService, KnowledgeBase knowledgeBase,
                            McpToolRegistry mcpToolRegistry) {
        this.chatService = chatService;
        this.knowledgeBase = knowledgeBase;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mockMode", chatService.isMockMode());
        status.put("model", chatService.model());
        status.put("provider", "DeepSeek");
        status.put("knowledgeChunks", knowledgeBase.chunkCount());

        List<String> tools = new ArrayList<>(List.of(
                "getCurrentTime", "calculate", "searchKnowledge", "conversationStats", "webSearch"));
        tools.addAll(mcpToolRegistry.toolNames());
        status.put("tools", tools);

        status.put("webSearchEnabled", webSearchEnabled);
        status.put("database", "MySQL 8");
        status.put("storage", "MinIO");
        status.put("imageUploadEnabled", true);
        status.put("maxImageBytes", 10485760);
        status.put("mcpServers", mcpToolRegistry.status());
        return status;
    }
}
