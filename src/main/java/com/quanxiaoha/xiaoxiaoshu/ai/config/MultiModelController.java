package com.quanxiaoha.xiaoxiaoshu.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author: 犬小哈
 * @description: 第 3 章多模型配置演示接口
 **/
@RestController
@RequestMapping("/api/models")
@EnableConfigurationProperties(MultiModelProperties.class)
public class MultiModelController {

    private final MultiModelProperties properties;

    public MultiModelController(MultiModelProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取所有配置的模型提供商
     */
    @GetMapping
    public Map<String, Object> listProviders() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultProvider", properties.getDefaultProvider());
        
        Map<String, Map<String, Object>> providers = new LinkedHashMap<>();
        
        // 默认提供商（DeepSeek）
        Map<String, Object> defaultInfo = new LinkedHashMap<>();
        defaultInfo.put("label", "DeepSeek V3（默认）");
        defaultInfo.put("type", "deepseek");
        defaultInfo.put("model", "deepseek-chat");
        defaultInfo.put("enabled", true);
        defaultInfo.put("isDefault", true);
        providers.put("deepseek", defaultInfo);

        // 其他提供商
        for (Map.Entry<String, MultiModelProperties.ProviderConfig> entry : properties.getProviders().entrySet()) {
            String name = entry.getKey();
            MultiModelProperties.ProviderConfig config = entry.getValue();
            
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("label", config.getLabel());
            info.put("type", config.getType());
            info.put("model", config.getModel());
            info.put("enabled", config.isEnabled());
            info.put("isDefault", false);
            
            providers.put(name, info);
        }

        response.put("providers", providers);
        return response;
    }

    /**
     * 获取配置示例
     */
    @GetMapping("/example")
    public Map<String, Object> getExampleConfig() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("description", "application.yml 中的多模型配置示例");
        example.put("usage", "通过环境变量启用不同模型提供商：");
        example.put("examples", new String[]{
            "OPENAI_ENABLED=true OPENAI_API_KEY=sk-xxx 启用 OpenAI",
            "OLLAMA_ENABLED=true OLLAMA_BASE_URL=http://localhost:11434 启用 Ollama 本地模型"
        });
        example.put("config", """
            app:
              models:
                default-provider: deepseek
                providers:
                  openai:
                    label: OpenAI GPT-4
                    type: openai
                    api-key: ${OPENAI_API_KEY:}
                    base-url: https://api.openai.com
                    model: gpt-4
                    enabled: ${OPENAI_ENABLED:false}
                  ollama:
                    label: Ollama 本地模型
                    type: ollama
                    api-key: ollama
                    base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
                    model: ${OLLAMA_MODEL:llama3.1}
                    enabled: ${OLLAMA_ENABLED:false}
            """);
        example.put("note", "当前实现演示配置结构，实际切换需要在 ChatService 中根据 provider 参数选择不同的 ChatClient");
        return example;
    }
}
