package com.quanxiaoha.xiaoxiaoshu.ai.rag;

/**
 * @author: 犬小哈
 * @description: RAG 检索命中的知识片段
 **/
public record Citation(String source, String heading, String snippet, double score) {
}
