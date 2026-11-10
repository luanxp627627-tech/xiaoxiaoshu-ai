package com.quanxiaoha.xiaoxiaoshu.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author: 犬小哈
 * @description: 主应用作为 MCP Client 时的服务端配置
 **/
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    private boolean enabled = true;
    private int connectTimeoutSeconds = 3;
    private int requestTimeoutSeconds = 15;
    private int retryCooldownSeconds = 60;
    private Map<String, Server> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getRetryCooldownSeconds() {
        return retryCooldownSeconds;
    }

    public void setRetryCooldownSeconds(int retryCooldownSeconds) {
        this.retryCooldownSeconds = retryCooldownSeconds;
    }

    public Map<String, Server> getServers() {
        return servers;
    }

    public void setServers(Map<String, Server> servers) {
        this.servers = servers == null ? new LinkedHashMap<>() : servers;
    }

    public static class Server {

        private String url;
        private String label;
        private boolean enabled = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean usable() {
            return enabled && url != null && !url.isBlank();
        }
    }
}
