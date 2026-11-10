package com.quanxiaoha.xiaoxiaoshu.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author: 犬小哈
 * @description: 第 3 章多模型配置：支持多个 LLM 提供商
 **/
@ConfigurationProperties(prefix = "app.models")
public class MultiModelProperties {

    private static final Logger log = LoggerFactory.getLogger(MultiModelProperties.class);

    private String defaultProvider = "deepseek";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public static class ProviderConfig {
        private String label;
        private String type; // deepseek, openai, ollama
        private String apiKey;
        private String baseUrl;
        private String model;
        private boolean enabled = true;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
