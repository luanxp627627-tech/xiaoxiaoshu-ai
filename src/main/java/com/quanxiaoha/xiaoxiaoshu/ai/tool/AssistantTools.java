package com.quanxiaoha.xiaoxiaoshu.ai.tool;

import com.quanxiaoha.xiaoxiaoshu.ai.rag.Citation;
import com.quanxiaoha.xiaoxiaoshu.ai.rag.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author: 犬小哈
 * @description: 提供给大模型调用的工具（Tool Calling）
 **/
@Component
public class AssistantTools {

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);

    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<li class=\"b_algo\".*?<h2>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?(?:<p[^>]*>(.*?)</p>)?",
            Pattern.DOTALL);

    private final KnowledgeBase knowledgeBase;
    private final HttpClient httpClient;

    @Value("${app.web-search.enabled:true}")
    private boolean webSearchEnabled;

    @Value("${app.web-search.timeout-seconds:8}")
    private long webSearchTimeoutSeconds;

    public AssistantTools(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(name = "getCurrentTime", description = "获取当前日期、时间和星期，回答时间相关问题时使用")
    public String getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        String[] weeks = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + weeks[now.getDayOfWeek().getValue() - 1];
    }

    @Tool(name = "calculate", description = "计算数学表达式，支持加减乘除、括号和小数，例如 (12.5+3)*2")
    public String calculate(@ToolParam(description = "数学表达式") String expression) {
        try {
            double value = new Calculator(expression).parse();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return "表达式计算结果无效";
            }
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        } catch (Exception e) {
            return "无法计算该表达式：" + e.getMessage();
        }
    }

    @Tool(name = "searchKnowledge", description = "检索小小书产品知识库，可查询发布笔记、点赞收藏、评论、关注、搜索、账号与隐私等使用说明")
    public String searchKnowledge(@ToolParam(description = "要检索的问题或关键词") String query) {
        List<Citation> citations = knowledgeBase.search(query, 3);
        if (citations.isEmpty()) {
            return "知识库中没有找到相关内容。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            sb.append("【").append(c.heading()).append("】").append(c.snippet()).append('\n');
        }
        return sb.toString().strip();
    }

    @Tool(name = "conversationStats", description = "查询当前会话的基本信息，包括会话 ID、使用模式和已加载的历史消息数量")
    public String conversationStats(ToolContext toolContext) {
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        String conversationId = String.valueOf(context.getOrDefault("conversationId", "未知"));
        String mode = "service".equals(context.get("mode")) ? "智能客服模式" : "通用助手模式";
        Object messageCount = context.getOrDefault("messageCount", 0);
        return "会话 ID：" + conversationId + "\n对话模式：" + mode + "\n上下文消息数：" + messageCount;
    }

    @Tool(name = "webSearch", description = "联网搜索最新信息。仅在用户明确要求联网查询、或问题涉及实时新闻时使用")
    public String webSearch(@ToolParam(description = "搜索关键词") String query) {
        if (!webSearchEnabled) {
            return "联网搜索当前已关闭。";
        }
        try {
            String url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(webSearchTimeoutSeconds))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return "联网搜索失败，HTTP 状态码：" + response.statusCode();
            }

            Matcher matcher = RESULT_PATTERN.matcher(response.body());
            StringBuilder sb = new StringBuilder();
            int count = 0;
            while (matcher.find() && count < 5) {
                String title = stripTags(matcher.group(2));
                String snippet = matcher.group(3) == null ? "" : stripTags(matcher.group(3));
                if (title.isBlank()) {
                    continue;
                }
                sb.append(count + 1).append(". ").append(title);
                if (!snippet.isBlank()) {
                    sb.append("：").append(snippet);
                }
                sb.append('\n');
                count++;
            }
            return count == 0 ? "没有搜索到相关结果。" : sb.toString().strip();
        } catch (Exception e) {
            log.warn("## 联网搜索失败", e);
            return "联网搜索暂时不可用：" + e.getMessage();
        }
    }

    private String stripTags(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * 递归下降计算器，避免使用脚本引擎执行任意表达式
     */
    private static class Calculator {
        private final String expression;
        private int pos = -1;
        private int ch;

        Calculator(String expression) {
            this.expression = expression == null ? "" : expression.replaceAll("\\s+", "");
        }

        double parse() {
            nextChar();
            double value = parseExpression();
            if (pos < expression.length()) {
                throw new IllegalArgumentException("无法识别的字符：" + expression.charAt(pos));
            }
            return value;
        }

        private void nextChar() {
            ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') {
                nextChar();
            }
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                if (eat('+')) {
                    value += parseTerm();
                } else if (eat('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                if (eat('*')) {
                    value *= parseFactor();
                } else if (eat('/')) {
                    value /= parseFactor();
                } else if (eat('%')) {
                    value %= parseFactor();
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }
            int startPos = pos;
            if (eat('(')) {
                double value = parseExpression();
                eat(')');
                return value;
            }
            while ((ch >= '0' && ch <= '9') || ch == '.') {
                nextChar();
            }
            if (startPos == pos) {
                throw new IllegalArgumentException("缺少数字");
            }
            return Double.parseDouble(expression.substring(startPos, pos));
        }
    }
}
