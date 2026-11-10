package com.quanxiaoha.xiaoxiaoshu.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author: 犬小哈
 * @description: 自定义 MCP Server 启动类，通过 Streamable HTTP 暴露工具
 **/
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
