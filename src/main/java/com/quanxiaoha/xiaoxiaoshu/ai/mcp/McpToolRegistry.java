package com.quanxiaoha.xiaoxiaoshu.ai.mcp;

import com.quanxiaoha.xiaoxiaoshu.ai.config.McpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author: 犬小哈
 * @description: 延迟连接的 MCP Client 注册表：连不上 MCP Server 时不影响应用启动
 **/
@Component
public class McpToolRegistry implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, Failure> failures = new LinkedHashMap<>();

    public McpToolRegistry(McpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (!properties.isEnabled()) {
            return new ToolCallback[0];
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            if (!entry.getValue().usable()) {
                continue;
            }
            Connection connection = connection(entry.getKey(), entry.getValue());
            if (connection != null) {
                callbacks.addAll(connection.callbacks());
            }
        }
        return callbacks.toArray(ToolCallback[]::new);
    }

    /**
     * 供演示模式下直接调用某个 MCP 工具，不经过大模型
     */
    public Optional<String> call(String serverName, String toolName, Map<String, Object> arguments) {
        McpProperties.Server server = properties.getServers().get(serverName);
        if (!properties.isEnabled() || server == null || !server.usable()) {
            return Optional.empty();
        }
        Connection connection = connection(serverName, server);
        if (connection == null) {
            return Optional.empty();
        }
        try {
            McpSchema.CallToolResult result = connection.client()
                    .callTool(new McpSchema.CallToolRequest(toolName, arguments));
            String text = extractText(result);
            if (Boolean.TRUE.equals(result.isError())) {
                return Optional.of("MCP 工具执行失败：" + text);
            }
            return Optional.of(text);
        } catch (Exception e) {
            log.warn("## 调用 MCP 工具失败, server={}, tool={}", serverName, toolName, e);
            markFailure(serverName, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!properties.isEnabled()) {
            return list;
        }
        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            String name = entry.getKey();
            McpProperties.Server server = entry.getValue();
            Connection connection = server.usable() ? connection(name, server) : null;
            Failure failure = failures.get(name);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("label", server.getLabel() == null ? name : server.getLabel());
            item.put("url", server.getUrl() == null ? "" : server.getUrl());
            item.put("enabled", server.usable());
            item.put("connected", connection != null);
            item.put("tools", connection == null ? List.of() : connection.toolNames());
            item.put("error", failure == null ? null : failure.message());
            list.add(item);
        }
        return list;
    }

    public List<String> toolNames() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> item : status()) {
            @SuppressWarnings("unchecked")
            List<String> tools = (List<String>) item.get("tools");
            names.addAll(tools);
        }
        return names;
    }

    private synchronized Connection connection(String name, McpProperties.Server server) {
        Connection existing = connections.get(name);
        if (existing != null && existing.client().isInitialized()) {
            return existing;
        }
        if (existing != null) {
            closeQuietly(existing);
            connections.remove(name);
        }
        Failure failure = failures.get(name);
        if (failure != null && !failure.expired(properties.getRetryCooldownSeconds())) {
            return null;
        }
        try {
            McpSyncClient client = buildClient(server.getUrl());
            client.initialize();
            List<ToolCallback> callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(client)).stream()
                    .map(callback -> (ToolCallback) new UnwrappedToolCallback(callback, objectMapper))
                    .toList();
            Connection connection = new Connection(client, callbacks);
            connections.put(name, connection);
            failures.remove(name);
            log.info("## MCP Server 已连接: {} -> {}，工具 {}", name, server.getUrl(), connection.toolNames());
            return connection;
        } catch (Exception e) {
            log.warn("## 连接 MCP Server 失败: {} -> {}: {}", name, server.getUrl(), e.getMessage());
            markFailure(name, e.getMessage());
            return null;
        }
    }

    private McpSyncClient buildClient(String url) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(url)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .build();
        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("xiaoxiaoshu-ai", "1.0.0"))
                .initializationTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .requestTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .build();
    }

    private String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            String text;
            if (content instanceof McpSchema.TextContent textContent) {
                text = textContent.text();
            } else {
                text = String.valueOf(content);
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(unwrapJsonString(text));
        }
        return sb.toString().strip();
    }

    /**
     * Spring AI 的 MCP Server 会把工具返回的字符串再序列化一次，这里还原成可读文本
     */
    private String unwrapJsonString(String text) {
        String trimmed = text.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                return objectMapper.readValue(trimmed, String.class);
            } catch (Exception ignored) {
                // 不是合法 JSON 字符串时按原样返回
            }
        }
        return trimmed;
    }

    private void markFailure(String name, String message) {
        failures.put(name, new Failure(Instant.now(), message));
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.client().close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }

    @PreDestroy
    public void close() {
        connections.values().forEach(this::closeQuietly);
        connections.clear();
    }

    private record Connection(McpSyncClient client, List<ToolCallback> callbacks) {
        List<String> toolNames() {
            return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
        }
    }

    private record Failure(Instant at, String message) {
        boolean expired(int cooldownSeconds) {
            return Instant.now().isAfter(at.plusSeconds(Math.max(5, cooldownSeconds)));
        }
    }

    /**
     * 将 MCP 返回的 JSON 字符串还原为纯文本，避免模型看到多余引号
     */
    private static final class UnwrappedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ObjectMapper objectMapper;

        private UnwrappedToolCallback(ToolCallback delegate, ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return unwrap(delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return unwrap(delegate.call(toolInput, toolContext));
        }

        private String unwrap(String text) {
            if (text == null) {
                return "";
            }
            String trimmed = text.strip();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                try {
                    return objectMapper.readValue(trimmed, String.class);
                } catch (Exception ignored) {
                    return trimmed;
                }
            }
            return trimmed;
        }
    }
}
