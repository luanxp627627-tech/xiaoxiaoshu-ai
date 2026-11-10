package com.quanxiaoha.xiaoxiaoshu.ai.config;

import com.quanxiaoha.xiaoxiaoshu.ai.tool.AssistantTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @author: 犬小哈
 * @description: Spring AI 相关 Bean
 **/
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class AiConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(30)
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(CustomOpenAiChatModel customOpenAiChatModel, AssistantTools assistantTools) {
        return ChatClient.builder(customOpenAiChatModel)
                // Advisor：打印请求大模型的出入参日志
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultTools(assistantTools)
                .build();
    }
}
