package com.quanxiaoha.xiaoxiaoshu.mcp.config;

import com.quanxiaoha.xiaoxiaoshu.mcp.tool.QqTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author: 犬小哈
 * @description: 把 @Tool 方法注册为 MCP Server 对外提供的工具
 **/
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider qqToolCallbackProvider(QqTools qqTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(qqTools)
                .build();
    }
}
