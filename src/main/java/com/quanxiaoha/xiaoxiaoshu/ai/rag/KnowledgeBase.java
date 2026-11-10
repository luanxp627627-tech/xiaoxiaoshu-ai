package com.quanxiaoha.xiaoxiaoshu.ai.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author: 犬小哈
 * @description: 轻量级 RAG 知识库：按段落切片 + 中文 bigram 相关性检索，
 * 不依赖外部 Embedding 服务，离线也能工作。
 * 第 15 章优化：查询扩展、位置加权、结果重排序
 **/
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final List<Chunk> chunks = new ArrayList<>();

    // 同义词表：用于查询扩展
    private static final Map<String, Set<String>> SYNONYMS = Map.of(
            "发布", Set.of("发表", "上传"),
            "笔记", Set.of("文章", "帖子"),
            "点赞", Set.of("赞", "喜欢"),
            "收藏", Set.of("保存", "标记"),
            "评论", Set.of("留言", "回复"),
            "关注", Set.of("订阅", "追踪"),
            "搜索", Set.of("查找", "检索"),
            "账号", Set.of("账户", "用户")
    );

    private record Chunk(String source, String heading, String text, Set<String> tokens) {
    }

    @PostConstruct
    public void load() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:knowledge/*.md");

        for (Resource resource : resources) {
            String filename = resource.getFilename() == null ? "knowledge.md" : resource.getFilename();
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            parseDocument(filename, content);
        }
        log.info("## 知识库加载完成，共 {} 个片段，{} 个文档", chunks.size(), resources.length);
    }

    private void parseDocument(String source, String content) {
        String[] blocks = content.split("\\n\\s*\\n");
        String heading = source.replace(".md", "");
        for (String block : blocks) {
            String text = block.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("#")) {
                heading = text.replaceFirst("^#+\\s*", "").strip();
            }
            String plain = text.replaceAll("^#+\\s*", "");
            if (plain.length() < 8) {
                continue;
            }
            chunks.add(new Chunk(source, heading, plain, tokenize(heading + " " + plain)));
        }
    }

    public List<Citation> search(String query, int topK) {
        // 第 15 章优化：查询扩展
        Set<String> expandedQuery = expandQuery(query);
        Set<String> queryTokens = tokenize(String.join(" ", expandedQuery));
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        // 短查询允许命中 1 个词，长句至少命中 2 个有效词，过滤"如何/我的"这类噪声
        int minOverlap = queryTokens.size() <= 2 ? 1 : 2;

        List<ScoredChunk> scored = new ArrayList<>();
        for (Chunk chunk : chunks) {
            double overlap = 0;
            for (String token : queryTokens) {
                if (chunk.tokens().contains(token)) {
                    overlap += 1;
                }
            }
            if (overlap < minOverlap) {
                continue;
            }
            double coverage = overlap / queryTokens.size();
            // 标题命中加权，避免只靠正文里的偶然字符
            Set<String> headingTokens = tokenize(chunk.heading());
            int headingHits = 0;
            for (String token : queryTokens) {
                if (headingTokens.contains(token)) {
                    headingHits++;
                }
            }
            double score = coverage + 0.12 * headingHits;
            if (score > 0.3) {
                String snippet = chunk.text().length() > 220
                        ? chunk.text().substring(0, 220) + "..."
                        : chunk.text();
                scored.add(new ScoredChunk(new Citation(chunk.source(), chunk.heading(), snippet,
                        Math.round(score * 1000) / 1000.0), score));
            }
        }

        // 第 15 章优化：结果重排序
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 同一小节只保留最高分，避免引用重复
        Map<String, Citation> distinct = new LinkedHashMap<>();
        for (ScoredChunk sc : scored) {
            distinct.putIfAbsent(sc.citation.source() + "|" + sc.citation.heading(), sc.citation);
            if (distinct.size() >= topK) {
                break;
            }
        }
        return new ArrayList<>(distinct.values());
    }

    /**
     * 第 15 章：查询扩展 - 添加同义词以提高召回率
     */
    private Set<String> expandQuery(String query) {
        Set<String> expanded = new HashSet<>();
        expanded.add(query);
        for (Map.Entry<String, Set<String>> entry : SYNONYMS.entrySet()) {
            if (query.contains(entry.getKey())) {
                for (String synonym : entry.getValue()) {
                    expanded.add(query.replace(entry.getKey(), synonym));
                }
            }
        }
        return expanded;
    }

    private record ScoredChunk(Citation citation, double score) {}

    public String context(String query, int topK) {
        List<Citation> citations = search(query, topK);
        if (citations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            sb.append("[资料").append(i + 1).append("] ").append(c.heading()).append('\n')
                    .append(c.snippet()).append("\n\n");
        }
        return sb.toString().strip();
    }

    public int chunkCount() {
        return chunks.size();
    }

    private static final Set<String> STOP_BIGRAMS = Set.of(
            "如何", "怎么", "什么", "为什", "我的", "可以", "请问", "帮我",
            "一下", "是否", "哪些", "这个", "那个", "告诉", "以及", "还有",
            "就是", "有什", "么区", "么用", "怎样", "能不", "不能", "需要"
    );

    private Set<String> tokenize(String text) {
        if (text == null) {
            return Set.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();

        // 英文/数字单词
        for (String word : normalized.split("[^a-z0-9]+")) {
            if (word.length() >= 2) {
                tokens.add(word);
            }
        }

        // 中文 bigram，能覆盖"发布笔记""智能客服"这类短语
        StringBuilder chinese = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chinese.append(c);
            } else {
                addBigrams(chinese, tokens);
                chinese.setLength(0);
            }
        }
        addBigrams(chinese, tokens);
        return tokens;
    }

    private void addBigrams(StringBuilder chinese, Set<String> tokens) {
        String s = chinese.toString();
        for (int i = 0; i + 1 < s.length(); i++) {
            String bigram = s.substring(i, i + 2);
            if (!STOP_BIGRAMS.contains(bigram)) {
                tokens.add(bigram);
            }
        }
        if (s.length() == 1) {
            tokens.add(s);
        }
    }
}
