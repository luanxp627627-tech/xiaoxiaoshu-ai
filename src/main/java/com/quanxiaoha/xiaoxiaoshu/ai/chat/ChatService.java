package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import tools.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaoxiaoshu.ai.prompt.PromptTemplateService;
import com.quanxiaoha.xiaoxiaoshu.ai.mcp.McpToolRegistry;
import com.quanxiaoha.xiaoxiaoshu.ai.rag.Citation;
import com.quanxiaoha.xiaoxiaoshu.ai.rag.KnowledgeBase;
import com.quanxiaoha.xiaoxiaoshu.ai.storage.ImageRef;
import com.quanxiaoha.xiaoxiaoshu.ai.tool.AssistantTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import com.quanxiaoha.xiaoxiaoshu.ai.config.CustomOpenAiChatModel;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author: 犬小哈
 * @description: 聊天核心服务：SSE 流式输出 + 多轮记忆 + RAG + 工具调用
 **/
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String DEFAULT_TITLE = "新对话";

    private final ChatClient deepSeekClient;
    private final ChatClient openAiClient;
    private final ChatClient customOpenAiClient;
    private final ChatMemory chatMemory;
    private final ChatRepository chatRepository;
    private final KnowledgeBase knowledgeBase;
    private final AssistantTools assistantTools;
    private final McpToolRegistry mcpToolRegistry;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${app.ai.model:deepseek}")
    private String defaultModel;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ChatService(ChatMemory chatMemory, ChatRepository chatRepository,
                       KnowledgeBase knowledgeBase, AssistantTools assistantTools,
                       McpToolRegistry mcpToolRegistry, PromptTemplateService promptTemplateService,
                       ObjectMapper objectMapper,
                       @Autowired(required = false) DeepSeekChatModel deepSeekChatModel,
                       @Autowired(required = false) OpenAiChatModel openAiChatModel,
                       @Autowired(required = false) CustomOpenAiChatModel customOpenAiChatModel) {
        this.chatMemory = chatMemory;
        this.chatRepository = chatRepository;
        this.knowledgeBase = knowledgeBase;
        this.assistantTools = assistantTools;
        this.mcpToolRegistry = mcpToolRegistry;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
        
        // 创建 DeepSeek 客户端（如果可用）
        this.deepSeekClient = deepSeekChatModel != null ? ChatClient.create(deepSeekChatModel) : null;
        // 创建 OpenAI 客户端（如果可用）
        this.openAiClient = openAiChatModel != null ? ChatClient.create(openAiChatModel) : null;
        // 创建自定义 OpenAI 客户端（如果可用）
        this.customOpenAiClient = customOpenAiChatModel != null ? ChatClient.create(customOpenAiChatModel) : null;
    }

    public boolean isMockMode() {
        // 如果两个 API Key 都没配置，进入模拟模式
        boolean hasDeepSeek = deepseekApiKey != null && !deepseekApiKey.isBlank() && !deepseekApiKey.startsWith("no-key");
        boolean hasOpenAi = openaiApiKey != null && !openaiApiKey.isBlank();
        return !hasDeepSeek && !hasOpenAi;
    }

    public String model() {
        return defaultModel;
    }

    /**
     * 根据模型名称获取对应的 ChatClient
     */
    private ChatClient getChatClient(String model) {
        if ("openai".equalsIgnoreCase(model)) {
            // 优先使用自定义 OpenAI 客户端（支持非标准端点）
            if (customOpenAiClient != null) {
                return customOpenAiClient;
            }
            if (openAiClient != null) {
                return openAiClient;
            }
        }
        // 默认使用 DeepSeek
        if (deepSeekClient != null) {
            return deepSeekClient;
        }
        // 如果都没有，返回 null（会进入 mock 模式）
        return null;
    }

    public void stream(ChatRequest request, SseEmitter emitter) {
        executor.execute(() -> doStream(request, emitter));
    }

    private void doStream(ChatRequest request, SseEmitter emitter) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : request.conversationId();
        String mode = normalizeMode(request.mode());
        boolean reasoning = request.reasoningEnabled();
        String userMessage = request.message() == null ? "" : request.message().strip();
        List<ImageRef> images = request.imageList();

        StringBuilder answer = new StringBuilder();
        List<Citation> citations = List.of();

        try {
            chatRepository.createConversation(conversationId, mode);
            Conversation conversation = chatRepository.findConversation(conversationId).orElseThrow();

            String title = conversation.title();
            if (DEFAULT_TITLE.equals(title)) {
                String titleSource = userMessage.isEmpty() ? (images.isEmpty() ? "" : "图片对话") : userMessage;
                if (!titleSource.isEmpty()) {
                    title = makeTitle(titleSource);
                    chatRepository.updateTitle(conversationId, title);
                }
            }

            List<Message> history = loadHistory(conversationId);
            String imagesJson = images.isEmpty() ? null : objectMapper.writeValueAsString(images);
            chatRepository.saveMessage(conversationId, "user", userMessage, null, imagesJson);
            chatMemory.add(conversationId,
                    new UserMessage(userMessage.isEmpty() && !images.isEmpty() ? "[图片消息]" : userMessage));

            List<Citation> hits = knowledgeBase.search(userMessage, 3);
            citations = hits;
            String systemPrompt = buildSystemPrompt(mode, hits);

            sendMeta(emitter, conversationId, title, mode, citations, reasoning);

            if (isMockMode()) {
                if (reasoning) {
                    streamReasoningMock(emitter, userMessage, images);
                }
                String reply = mockReply(userMessage, mode, hits, images);
                streamMock(emitter, reply, answer);
            } else {
                streamModel(emitter, systemPrompt, history, modelUserText(userMessage, images),
                        answer, reasoning, conversationId, mode);
            }

            String content = answer.toString();
            chatRepository.saveMessage(conversationId, "assistant", content,
                    citations.isEmpty() ? null : objectMapper.writeValueAsString(citations), null);
            chatMemory.add(conversationId, new AssistantMessage(content));

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("conversationId", conversationId);
            done.put("title", title);
            done.put("length", content.length());
            emitter.send(SseEmitter.event().name("done").data(done, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception e) {
            log.error("## 对话处理失败, conversationId={}", conversationId, e);
            try {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("message", friendlyError(e));
                error.put("conversationId", conversationId);
                emitter.send(SseEmitter.event().name("error").data(error, MediaType.APPLICATION_JSON));
            } catch (IOException ignored) {
                // 客户端可能已断开
            }
            emitter.completeWithError(e);
        }
    }

    private void streamModel(SseEmitter emitter, String systemPrompt, List<Message> history,
                             String userMessage, StringBuilder answer, boolean reasoning,
                             String conversationId, String mode) throws IOException {
        // 根据配置的模型选择对应的 ChatClient
        ChatClient client = getChatClient(defaultModel);
        if (client == null) {
            throw new IOException("未配置可用的 AI 模型（DeepSeek 或 OpenAI）");
        }
        
        ChatClient.ChatClientRequestSpec spec = client.prompt().system(systemPrompt);
        if (!history.isEmpty()) {
            spec.messages(history);
        }
        spec.user(userMessage);
        // ToolContext：让工具拿到当前会话的上下文信息
        spec.toolContext(Map.of(
                "conversationId", conversationId,
                "mode", mode,
                "messageCount", history.size() + 1));
        // MCP 工具按需懒加载，MCP Server 未启动时自动跳过
        ToolCallback[] mcpCallbacks = mcpToolRegistry.getToolCallbacks();
        if (mcpCallbacks.length > 0) {
            spec.toolCallbacks(mcpCallbacks);
        }
        if (reasoning) {
            spec.options(DeepSeekChatOptions.builder().model(reasoningModel()));
        }

        StringBuilder thinking = new StringBuilder();
        for (ChatResponse response : spec.stream().chatResponse().toIterable()) {
            if (response == null || response.getResult() == null) {
                continue;
            }
            AssistantMessage output = response.getResult().getOutput();
            String reasoningChunk = reasoningContent(output);
            if (reasoningChunk != null && !reasoningChunk.isEmpty()) {
                // DeepSeek 流式返回的思维链可能是增量，也可能是累计文本，这里统一转成增量
                String delta = reasoningChunk.startsWith(thinking.toString())
                        ? reasoningChunk.substring(thinking.length())
                        : reasoningChunk;
                if (!delta.isEmpty()) {
                    thinking.append(delta);
                    sendEvent(emitter, "reasoning", Map.of("content", delta));
                }
            }
            String content = output.getText();
            if (content == null || content.isEmpty()) {
                continue;
            }
            answer.append(content);
            sendDelta(emitter, content);
        }
    }

    private String reasoningContent(AssistantMessage output) {
        if (output instanceof DeepSeekAssistantMessage deepSeekMessage) {
            return deepSeekMessage.getReasoningContent();
        }
        Object value = output.getMetadata().get("reasoningContent");
        if (value == null) {
            value = output.getMetadata().get("reasoning_content");
        }
        return value == null ? null : value.toString();
    }

    private String reasoningModel() {
        return "deepseek-reasoner";
    }

    private void streamMock(SseEmitter emitter, String reply, StringBuilder answer) throws IOException {
        List<String> chunks = splitForTyping(reply);
        for (String chunk : chunks) {
            answer.append(chunk);
            sendDelta(emitter, chunk);
            try {
                Thread.sleep(28);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void streamReasoningMock(SseEmitter emitter, String userMessage, List<ImageRef> images) throws IOException {
        String question = userMessage.isEmpty()
                ? (images.isEmpty() ? "（空消息）" : "用户发来了一张图片")
                : (userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage);
        String thinking = """
                用户的问题是「%s」。
                先判断意图：这是在问小小书的使用方式，还是普通聊天，或者需要工具计算、查时间。
                然后从知识库里找相关资料，组织成简洁的中文回答，避免编造不存在的功能。
                """.formatted(question);
        if (!images.isEmpty()) {
            thinking += "\n这次还带了图片，先看图片的文件名、尺寸和色调，给出贴合画面的建议。";
        }
        for (String chunk : splitForTyping(thinking.strip())) {
            sendEvent(emitter, "reasoning", Map.of("content", chunk));
            try {
                Thread.sleep(18);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private List<String> splitForTyping(String text) {
        List<String> chunks = new ArrayList<>();
        int index = 0;
        int step = 2;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + step);
            // 尽量在标点处断开，观感更自然
            chunks.add(text.substring(index, end));
            index = end;
            step = (step % 5) + 2;
        }
        return chunks;
    }

    private void sendMeta(SseEmitter emitter, String conversationId, String title, String mode,
                          List<Citation> citations, boolean reasoning) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("conversationId", conversationId);
        meta.put("title", title);
        meta.put("mode", mode);
        meta.put("mockMode", isMockMode());
        meta.put("reasoning", reasoning);
        meta.put("model", reasoning ? reasoningModel() : model());
        meta.put("citations", citations);
        emitter.send(SseEmitter.event().name("meta").data(meta, MediaType.APPLICATION_JSON));
    }

    private void sendDelta(SseEmitter emitter, String chunk) throws IOException {
        sendEvent(emitter, "delta", Map.of("content", chunk));
    }

    private void sendEvent(SseEmitter emitter, String name, Map<String, Object> data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }

    private List<Message> loadHistory(String conversationId) {
        List<Message> memoryMessages = chatMemory.get(conversationId);
        if (memoryMessages != null && !memoryMessages.isEmpty()) {
            return memoryMessages;
        }
        List<StoredMessage> stored = chatRepository.recentMessages(conversationId, 20);
        if (stored.isEmpty()) {
            return List.of();
        }
        List<Message> messages = stored.stream()
                .map(m -> "user".equals(m.role())
                        ? (Message) new UserMessage(m.content())
                        : (Message) new AssistantMessage(m.content()))
                .toList();
        chatMemory.add(conversationId, messages);
        return messages;
    }

    private String buildSystemPrompt(String mode, List<Citation> citations) {
        String base = "service".equals(mode)
                ? promptTemplateService.getCustomerServiceSystemPrompt()
                : promptTemplateService.getGeneralAssistantSystemPrompt();

        // 添加当前时间到 system prompt（临时方案）
        String currentTime = assistantTools.getCurrentTime();
        base = base + "\n\n当前时间：" + currentTime;

        if (citations.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append("\n\n【知识库检索结果】\n");
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            sb.append('[').append(i + 1).append("] ").append(c.heading()).append("：")
                    .append(c.snippet()).append('\n');
        }
        sb.append("请优先依据以上资料回答，并在合适的位置自然标注引用编号。");
        return sb.toString();
    }

    private String modelUserText(String userMessage, List<ImageRef> images) {
        if (images.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder(userMessage.isEmpty() ? "请看看我上传的图片。" : userMessage);
        sb.append("\n\n【用户上传的图片】\n");
        for (ImageRef image : images) {
            sb.append("- ").append(image.name());
            if (image.width() != null && image.height() != null) {
                sb.append("，").append(image.width()).append('×').append(image.height()).append(" 像素");
            }
            if (image.tone() != null) {
                sb.append("，").append(image.tone());
            }
            sb.append("，地址：").append(image.url()).append('\n');
        }
        sb.append("当前对话模型不支持读取图片像素，请基于文件名、尺寸、色调等信息回应，并坦诚说明这一限制。");
        return sb.toString();
    }

    private String mockReply(String message, String mode, List<Citation> citations, List<ImageRef> images) {
        StringBuilder sb = new StringBuilder();

        if (!images.isEmpty()) {
            sb.append(imageMockReply(images));
            if (message.isEmpty()) {
                return sb.toString().strip();
            }
            sb.append("\n\n");
        } else if (message.isEmpty()) {
            return "你好，我是小小书 AI 助手，有什么可以帮你的吗？";
        }

        boolean asksTime = message.contains("几点") || message.contains("时间") || message.contains("日期")
                || message.contains("今天") || message.contains("星期");
        boolean asksMath = message.matches(".*[0-9]\\s*[+\\-*/%].*") || message.contains("计算");
        boolean asksQq = message.toUpperCase().contains("QQ");

        if (asksTime) {
            sb.append("现在是 ").append(assistantTools.getCurrentTime()).append("。\n\n");
        }
        if (asksMath) {
            String expression = extractExpression(message);
            if (!expression.isBlank()) {
                sb.append("计算结果是 **").append(assistantTools.calculate(expression)).append("**。\n\n");
            }
        }
        if (asksQq) {
            sb.append(qqMockReply(message));
            return sb.toString().strip();
        }

        if (!citations.isEmpty()) {
            sb.append("根据小小书知识库，我找到了这些相关信息：\n\n");
            for (int i = 0; i < citations.size(); i++) {
                Citation c = citations.get(i);
                sb.append("**").append(i + 1).append(". ").append(c.heading()).append("**\n")
                        .append(c.snippet()).append("\n\n");
            }
            sb.append("如果你需要更具体的操作步骤，可以告诉我你卡在哪一步。");
        } else if (asksTime || asksMath) {
            sb.append("还有什么需要我帮忙的吗？");
        } else if ("service".equals(mode)) {
            sb.append("""
                    我是小小书的智能客服小书，可以帮你解答这些常见问题：

                    - 如何发布图文 / 视频笔记
                    - 点赞、收藏和评论怎么用
                    - 关注、粉丝与个人主页
                    - 搜索笔记和用户
                    - 账号、隐私与内容安全

                    你可以直接描述遇到的问题，我会尽量给出具体步骤。
                    """);
        } else {
            sb.append("""
                    我是 **小小书 AI 助手**，可以陪你聊天，也能帮你处理这些事：

                    - 查询时间、做数学计算
                    - 检索小小书产品知识库（发布、点赞、评论、关注、搜索等）
                    - 通过 MCP Server 查询 QQ 资料（试试「帮我查一下 QQ 10001」）
                    - 联网搜索实时信息（配置真实模型后可自动调用工具）

                    你可以问我「怎么发布笔记」「帮我算 (12+8)*3」「现在几点」或者「查一下 QQ 10001」。

                    > 当前运行在本地演示模式：配置环境变量 `DEEPSEEK_API_KEY` 后重启，就能接入 DeepSeek 的真实流式对话与工具调用。
                    """);
        }
        return sb.toString().strip();
    }

    private String qqMockReply(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?<!\\d)(\\d{5,12})(?!\\d)")
                .matcher(message);
        if (!matcher.find()) {
            return "请告诉我要查询的 QQ 号（5 到 12 位数字），例如「帮我查一下 QQ 12345678」。";
        }
        String qq = matcher.group(1);
        java.util.Optional<String> info = mcpToolRegistry.call("qq", "getQQInfo", Map.of("qq", qq));
        if (info.isEmpty()) {
            return "暂时连不上 QQ 资料 MCP Server（http://127.0.0.1:7081/mcp），"
                    + "请先在 mcp-server 目录启动服务后重试。";
        }
        StringBuilder sb = new StringBuilder("通过 MCP Server 查询到 QQ ").append(qq).append(" 的资料：\n\n");
        sb.append(info.get()).append("\n\n");
        mcpToolRegistry.call("qq", "getQQAvatar", Map.of("qq", qq))
                .ifPresent(avatar -> sb.append("头像地址：").append(avatar).append("\n\n"));
        sb.append("> 以上数据由独立部署的 MCP Server 提供，主应用通过 MCP Client 远程调用工具，"
                + "而不是把工具代码写在本应用里。");
        return sb.toString().strip();
    }

    private String imageMockReply(List<ImageRef> images) {
        StringBuilder sb = new StringBuilder();
        sb.append("我收到了你上传的 ").append(images.size()).append(" 张图片：\n\n");
        for (int i = 0; i < images.size(); i++) {
            ImageRef image = images.get(i);
            sb.append("**").append(i + 1).append(". ").append(image.name()).append("**");
            if (image.width() != null && image.height() != null) {
                sb.append(" — ").append(image.width()).append('×').append(image.height())
                        .append(" 像素 · ").append(Math.max(1, image.size() / 1024)).append(" KB");
            }
            sb.append('\n');
        }

        ImageRef first = images.getFirst();
        String tone = first.tone() == null ? "" : first.tone();
        sb.append("\n**画面概览**：");
        if (tone.contains("偏暗")) {
            sb.append("整体亮度偏低，暗部细节可能不够清晰。如果是笔记封面，建议适当提高曝光，或者给标题文字加一层浅色描边。");
        } else if (tone.contains("明亮")) {
            sb.append("整体亮度较高，画面清爽。发图文笔记时可以配一个低饱和的标题贴纸，避免文字被亮背景吃掉。");
        } else {
            sb.append("亮度和对比度都比较均衡，适合直接作为笔记配图。");
        }
        if (tone.contains("暖色调")) {
            sb.append("色调偏暖，适合生活、美食、穿搭类内容。");
        } else if (tone.contains("冷色调")) {
            sb.append("色调偏冷，适合科技、学习、风景类内容。");
        }

        sb.append("\n\n**发布建议**：\n");
        sb.append("- 首图尽量突出主体，标题控制在 12 字以内\n");
        sb.append("- 正文补充拍摄场景和关键词，方便搜索到\n");
        sb.append("- 需要的话我可以帮你写一段配套文案\n\n");
        sb.append("> 当前是本地演示模式，视觉模型尚未接入，以上判断基于图片尺寸与平均色调。"
                + "配置支持视觉输入的模型后，我就能真正\"看懂\"图片内容了。");
        return sb.toString();
    }

    private String extractExpression(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?(?:\\s*[+\\-*/%()]\\s*[0-9]+(?:\\.[0-9]+)?)+)")
                .matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String makeTitle(String message) {
        String title = message.replaceAll("\\s+", " ").strip();
        return title.length() > 24 ? title.substring(0, 24) + "..." : title;
    }

    private String normalizeMode(String mode) {
        return "service".equalsIgnoreCase(mode) ? "service" : "assistant";
    }

    private String friendlyError(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("401") || message.toLowerCase().contains("unauthorized") || message.contains("api key")) {
            return "DeepSeek API Key 无效或未配置，请检查 DEEPSEEK_API_KEY 后重启服务。";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "模型响应超时，请稍后重试。";
        }
        return "对话失败：" + (message.length() > 160 ? message.substring(0, 160) : message);
    }

    public void clearMemory(String conversationId) {
        chatMemory.clear(conversationId);
    }
}
