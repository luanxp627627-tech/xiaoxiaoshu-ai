package com.quanxiaoha.xiaoxiaoshu.ai.prompt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author: 犬小哈
 * @description: 第 5 章 Prompt Engineering 演示接口
 **/
@RestController
@RequestMapping("/api/prompt")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 5.1 基础模板演示
     */
    @GetMapping("/template")
    public Map<String, Object> demoTemplate() {
        String template = "你好，{name}！今天是{date}，天气{weather}。";
        Map<String, Object> variables = Map.of(
                "name", "犬小哈",
                "date", "2026-11-02",
                "weather", "晴朗"
        );
        String result = promptTemplateService.renderTemplate(template, variables);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "5.1 基础模板 - 变量替换");
        response.put("template", template);
        response.put("variables", variables);
        response.put("result", result);
        return response;
    }

    /**
     * 5.2 Few-shot 示例演示
     */
    @GetMapping("/fewshot")
    public Map<String, Object> demoFewShot() {
        String task = "将中文数字转换为阿拉伯数字";
        String[] examples = {
                "一", "1",
                "二十五", "25",
                "一百零八", "108"
        };
        String userInput = "三百六十五";
        String result = promptTemplateService.buildFewShotPrompt(task, examples, userInput);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "5.2 Few-shot 示例 - 通过示例引导输出格式");
        response.put("task", task);
        response.put("examples", examples);
        response.put("userInput", userInput);
        response.put("prompt", result);
        return response;
    }

    /**
     * 5.3 Chain-of-Thought 演示
     */
    @GetMapping("/cot")
    public Map<String, Object> demoCot(@RequestParam(defaultValue = "一个水池有甲乙两个进水管，甲管单独注满需要6小时，乙管单独注满需要8小时，两管同时开需要多久注满？") String question) {
        String result = promptTemplateService.buildCotPrompt(question);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "5.3 Chain-of-Thought - 引导逐步推理");
        response.put("question", question);
        response.put("prompt", result);
        return response;
    }

    /**
     * 5.4 角色设定演示
     */
    @GetMapping("/role")
    public Map<String, Object> demoRole() {
        String role = "资深 Java 架构师";
        String task = "评估技术方案的可行性";
        String context = "项目需要在 MySQL 和 Redis 之间保持数据一致性，考虑使用双写方案或 Canal 监听 binlog";
        String result = promptTemplateService.buildRolePrompt(role, task, context);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "5.4 角色设定 - 给模型明确角色");
        response.put("role", role);
        response.put("task", task);
        response.put("context", context);
        response.put("prompt", result);
        return response;
    }

    /**
     * 5.5 输出格式约束演示
     */
    @GetMapping("/format")
    public Map<String, Object> demoFormat() {
        String instruction = "分析以下代码的时间复杂度";
        String format = """
                {
                  "algorithm": "算法名称",
                  "timeComplexity": "O(?)",
                  "spaceComplexity": "O(?)",
                  "explanation": "简要说明"
                }""";
        String result = promptTemplateService.buildFormatPrompt(instruction, format);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "5.5 输出格式约束 - 要求按指定格式输出");
        response.put("instruction", instruction);
        response.put("format", format);
        response.put("prompt", result);
        return response;
    }

    /**
     * 客服模式系统提示词
     */
    @GetMapping("/customer-service")
    public Map<String, Object> getCustomerServicePrompt() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "客服模式系统提示词（优化版）");
        response.put("prompt", promptTemplateService.getCustomerServiceSystemPrompt());
        return response;
    }

    /**
     * 通用助手模式系统提示词
     */
    @GetMapping("/general-assistant")
    public Map<String, Object> getGeneralAssistantPrompt() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "通用助手模式系统提示词（优化版）");
        response.put("prompt", promptTemplateService.getGeneralAssistantSystemPrompt());
        return response;
    }

    /**
     * 深度思考模式提示词
     */
    @GetMapping("/deep-thinking")
    public Map<String, Object> getDeepThinkingPrompt(@RequestParam(defaultValue = "如何设计一个高并发的秒杀系统？") String question) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "深度思考模式提示词");
        response.put("question", question);
        response.put("prompt", promptTemplateService.getDeepThinkingPrompt(question));
        return response;
    }

    /**
     * 图片分析提示词
     */
    @GetMapping("/image-analysis")
    public Map<String, Object> getImageAnalysisPrompt() {
        String imageDescription = "尺寸：1920x1080，色调：暖色调·整体明亮，格式：JPEG，大小：2.3MB";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chapter", "图片分析提示词");
        response.put("imageDescription", imageDescription);
        response.put("prompt", promptTemplateService.getImageAnalysisPrompt(imageDescription));
        return response;
    }

    /**
     * 所有演示接口汇总
     */
    @GetMapping("/all")
    public Map<String, Object> allDemos() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("5.1_基础模板", demoTemplate());
        all.put("5.2_Few-shot", demoFewShot());
        all.put("5.3_Chain-of-Thought", demoCot("一个水池有甲乙两个进水管，甲管单独注满需要6小时，乙管单独注满需要8小时，两管同时开需要多久注满？"));
        all.put("5.4_角色设定", demoRole());
        all.put("5.5_输出格式", demoFormat());
        all.put("客服系统提示词", getCustomerServicePrompt());
        all.put("通用助手提示词", getGeneralAssistantPrompt());
        all.put("深度思考提示词", getDeepThinkingPrompt("如何设计一个高并发的秒杀系统？"));
        all.put("图片分析提示词", getImageAnalysisPrompt());
        return all;
    }
}
