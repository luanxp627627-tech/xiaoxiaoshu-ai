package com.quanxiaoha.xiaoxiaoshu.ai.chat;

import com.quanxiaoha.xiaoxiaoshu.ai.storage.ImageRef;

import java.util.List;

/**
 * @author: 犬小哈
 * @description: 聊天请求，images 为已上传图片的引用
 **/
public record ChatRequest(String conversationId, String message, String mode, Boolean reasoning,
                          List<ImageRef> images) {

    public boolean reasoningEnabled() {
        return Boolean.TRUE.equals(reasoning);
    }

    public List<ImageRef> imageList() {
        return images == null ? List.of() : images;
    }
}
