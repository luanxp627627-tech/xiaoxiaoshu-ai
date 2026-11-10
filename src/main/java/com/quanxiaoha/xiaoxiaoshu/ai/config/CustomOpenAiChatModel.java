package com.quanxiaoha.xiaoxiaoshu.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author: 犬小哈
 * @description: 自定义 OpenAI 兼容模型，支持非标准端点
 **/
@Component
public class CustomOpenAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CustomOpenAiChatModel.class);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CustomOpenAiChatModel() {
        this.restTemplate = new RestTemplate();
        // 强制使用 UTF-8 编码处理字符串响应
        this.restTemplate.getMessageConverters().stream()
                .filter(converter -> converter instanceof StringHttpMessageConverter)
                .forEach(converter -> ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            String requestBody = buildRequestBody(prompt, false);
            HttpHeaders headers = buildHeaders();
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            String fullUrl = baseUrl.endsWith("/v1/chat/completions") ? baseUrl : baseUrl + "/v1/chat/completions";
            log.info("调用自定义 OpenAI 端点：{}", fullUrl);
            ResponseEntity<String> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            return parseResponse(response.getBody());
        } catch (Exception e) {
            log.error("调用自定义 OpenAI 端点失败", e);
            throw new RuntimeException("对话失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.create(sink -> {
            try {
                String requestBody = buildRequestBody(prompt, true);
                HttpHeaders headers = buildHeaders();
                headers.set("Accept", "text/event-stream");
                HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

                String fullUrl = baseUrl.endsWith("/v1/chat/completions") ? baseUrl : baseUrl + "/v1/chat/completions";
                log.info("流式调用自定义 OpenAI 端点：{}", fullUrl);
                ResponseEntity<String> response = restTemplate.exchange(
                        fullUrl,
                        HttpMethod.POST,
                        entity,
                        String.class
                );

                // 解析 SSE 流
                String body = response.getBody();
                if (body != null) {
                    String[] lines = body.split("\n");
                    for (String line : lines) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            try {
                                ChatResponse chatResponse = parseStreamResponse(data);
                                if (chatResponse != null) {
                                    sink.next(chatResponse);
                                }
                            } catch (Exception e) {
                                log.warn("解析流式响应块失败：{}", data, e);
                            }
                        }
                    }
                }
                sink.complete();
            } catch (Exception e) {
                log.error("流式调用失败", e);
                sink.error(new RuntimeException("对话失败：" + e.getMessage(), e));
            }
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return null;
    }

    private String buildRequestBody(Prompt prompt, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("stream", stream);

            ArrayNode messages = root.putArray("messages");
            for (Message msg : prompt.getInstructions()) {
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", msg.getMessageType().getValue());
                msgNode.put("content", msg.getText());
            }

            // 添加工具定义（如果有）
            ChatOptions options = prompt.getOptions();
            log.info("Prompt options: {}", options);
            if (options instanceof ToolCallingChatOptions toolCallingOptions) {
                List<ToolCallback> toolCallbacks = toolCallingOptions.getToolCallbacks();
                log.info("Tool callbacks: {}", toolCallbacks);
                if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                    ArrayNode tools = root.putArray("tools");
                    for (ToolCallback callback : toolCallbacks) {
                        ToolDefinition definition = callback.getToolDefinition();
                        ObjectNode tool = tools.addObject();
                        tool.put("type", "function");
                        ObjectNode function = tool.putObject("function");
                        function.put("name", definition.name());
                        function.put("description", definition.description());
                        // inputSchema 是 JSON 字符串，需要解析
                        try {
                            JsonNode schema = objectMapper.readTree(definition.inputSchema());
                            function.set("parameters", schema);
                        } catch (Exception e) {
                            log.warn("解析工具 {} 的 inputSchema 失败：{}", definition.name(), e.getMessage());
                            function.putObject("parameters");
                        }
                    }
                    log.info("添加了 {} 个工具定义", toolCallbacks.size());
                }
            }

            String requestBody = objectMapper.writeValueAsString(root);
            log.debug("请求体：{}", requestBody);
            return requestBody;
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private ChatResponse parseResponse(String responseBody) {
        try {
            log.debug("API 原始响应：{}", responseBody);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    String content = message.has("content") && !message.get("content").isNull() 
                            ? message.get("content").asText() : "";
                    
                    // 检查是否有 tool_calls
                    JsonNode toolCalls = message.get("tool_calls");
                    log.debug("tool_calls: {}", toolCalls);
                    if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 0) {
                        // 构建 ToolCall 列表
                        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                        for (JsonNode call : toolCalls) {
                            calls.add(new AssistantMessage.ToolCall(
                                    call.get("id").asText(),
                                    "function",
                                    call.get("function").get("name").asText(),
                                    call.get("function").get("arguments").asText()
                            ));
                        }
                        AssistantMessage assistantMessage = AssistantMessage.builder()
                                .content(content)
                                .toolCalls(calls)
                                .build();
                        log.info("解析到 {} 个工具调用", calls.size());
                        return new ChatResponse(List.of(new Generation(assistantMessage)));
                    }
                    
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        } catch (Exception e) {
            log.error("解析响应失败", e);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("解析响应失败"))));
        }
    }

    private ChatResponse parseStreamResponse(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("content")) {
                    String content = delta.get("content").asText();
                    if (content != null && !content.isEmpty()) {
                        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("解析流式响应块失败", e);
            return null;
        }
    }
}
