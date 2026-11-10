package com.quanxiaoha.xiaoxiaoshu.ai.storage;

/**
 * @author: 犬小哈
 * @description: 已上传图片的引用，url 走本服务代理，浏览器无需接触 MinIO 凭证
 **/
public record ImageRef(String url, String objectName, String name, String contentType,
                       long size, Integer width, Integer height, String tone) {
}
