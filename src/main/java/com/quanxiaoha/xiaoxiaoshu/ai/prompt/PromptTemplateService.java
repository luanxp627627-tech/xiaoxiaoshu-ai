package com.quanxiaoha.xiaoxiaoshu.ai.prompt;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * @author: 犬小哈
 * @description: 第 5 章 Prompt Engineering - 提示词模板服务
 **/
@Service
public class PromptTemplateService {

    /**
     * 5.1 基础模板：变量替换
     */
    public String renderTemplate(String template, Map<String, Object> variables) {
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    /**
     * 5.2 Few-shot 示例：通过示例引导模型输出格式
     */
    public String buildFewShotPrompt(String task, String[] examples, String userInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(task).append("\n\n");
        sb.append("示例：\n");
        for (int i = 0; i < examples.length; i += 2) {
            sb.append("输入：").append(examples[i]).append("\n");
            sb.append("输出：").append(examples[i + 1]).append("\n\n");
        }
        sb.append("现在请处理：\n");
        sb.append("输入：").append(userInput).append("\n");
        sb.append("输出：");
        return sb.toString();
    }

    /**
     * 5.3 Chain-of-Thought：引导模型逐步推理
     */
    public String buildCotPrompt(String question) {
        return "请一步一步思考这个问题，先分析已知条件，再推导结论。\n\n" +
               "问题：" + question + "\n\n" +
               "思考过程：";
    }

    /**
     * 5.4 角色设定：给模型一个明确的角色
     */
    public String buildRolePrompt(String role, String task, String context) {
        return String.format(
            "你是一位%s。你的任务是%s。\n\n背景信息：%s\n\n请根据以上信息回答用户问题。",
            role, task, context
        );
    }

    /**
     * 5.5 输出格式约束：要求模型按指定格式输出
     */
    public String buildFormatPrompt(String instruction, String format) {
        return instruction + "\n\n" +
               "请严格按照以下格式输出：\n" +
               format + "\n\n" +
               "不要输出任何其他内容。";
    }

    /**
     * 客服模式系统提示词（优化版）
     */
    public String getCustomerServiceSystemPrompt() {
        return """
            你是小小书的智能客服助手，专门帮助用户解决平台使用问题。
            
            你的职责：
            1. 耐心解答用户关于发布笔记、账号管理、隐私设置等问题
            2. 提供清晰、准确的操作指引
            3. 遇到无法解决的问题，引导用户联系人工客服
            
            回答原则：
            - 使用友好、专业的语气
            - 优先使用知识库中的官方文档
            - 如果不确定答案，诚实告知并建议联系人工客服
            - 回答要简洁明了，避免冗长
            
            如果用户问题涉及知识库内容，请参考以下资料：
            {context}
            """;
    }

    /**
     * 通用助手模式系统提示词（优化版）
     */
    public String getGeneralAssistantSystemPrompt() {
        return """
            你是小小书的通用 AI 助手，可以回答各种问题、提供帮助和建议。
            
            你的能力：
            1. 回答知识性问题
            2. 提供建议和解决方案
            3. 进行数学计算
            4. 查询时间和搜索信息
            5. 分析图片内容（如果提供）
            
            回答原则：
            - 保持客观、准确
            - 如果不确定，诚实告知
            - 提供有用的背景信息
            - 回答要结构清晰、易于理解
            
            如果问题涉及平台相关知识，请参考：
            {context}
            """;
    }

    /**
     * 深度思考模式提示词（用于复杂问题）
     */
    public String getDeepThinkingPrompt(String question) {
        return """
            请对以下问题进行深度分析：
            
            问题：%s
            
            请按以下结构回答：
            1. 问题本质：这个问题的核心是什么？
            2. 关键因素：影响答案的主要因素有哪些？
            3. 多角度分析：从不同角度看这个问题
            4. 综合结论：给出最终答案和建议
            
            要求：
            - 思考要深入、全面
            - 逻辑要清晰、严密
            - 结论要有依据、可操作
            """.formatted(question);
    }

    /**
     * 图片分析提示词
     */
    public String getImageAnalysisPrompt(String imageDescription) {
        return """
            用户发送了一张图片，图片的基本信息如下：
            %s
            
            请根据这些信息：
            1. 描述图片可能的内容
            2. 分析图片的用途或场景
            3. 提供相关的建议或信息
            
            注意：这是基于元数据的推测，不是实际看到图片。
            """.formatted(imageDescription);
    }
}
