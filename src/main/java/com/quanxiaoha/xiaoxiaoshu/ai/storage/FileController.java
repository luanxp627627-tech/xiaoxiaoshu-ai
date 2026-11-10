package com.quanxiaoha.xiaoxiaoshu.ai.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author: 犬小哈
 * @description: 图片上传与读取接口（支持单文件上传与分片上传）
 **/
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final MinioStorageService storageService;

    public FileController(MinioStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/images")
    public ImageRef upload(@RequestParam("file") MultipartFile file) {
        return storageService.uploadImage(file);
    }

    /**
     * 分片上传：上传单个分片
     */
    @PostMapping("/chunk")
    public Map<String, Object> uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("file") MultipartFile file) {
        try {
            byte[] chunkData = file.getBytes();
            String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            storageService.uploadChunk(uploadId, chunkIndex, chunkData, contentType);
            return Map.of(
                    "uploadId", uploadId,
                    "chunkIndex", chunkIndex,
                    "size", chunkData.length,
                    "uploaded", true
            );
        } catch (Exception e) {
            return Map.of("uploaded", false, "error", e.getMessage());
        }
    }

    /**
     * 合并分片：将所有分片合并为完整文件
     */
    @PostMapping("/merge")
    public ImageRef mergeChunks(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("fileName") String fileName,
            @RequestParam("totalSize") long totalSize) {
        String extension = extensionOf(fileName);
        String contentType = "image/" + extension;
        String objectName = "images/" + LocalDate.now() + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + extension;
        return storageService.mergeChunks(uploadId, totalChunks, objectName, fileName, contentType, totalSize);
    }

    /**
     * 查询已上传的分片列表（用于断点续传）
     */
    @GetMapping("/chunks/{uploadId}")
    public List<Integer> getUploadedChunks(@PathVariable String uploadId) {
        return storageService.getUploadedChunks(uploadId);
    }

    /**
     * 生成新的 uploadId
     */
    @PostMapping("/uploadId")
    public Map<String, String> generateUploadId() {
        return Map.of("uploadId", UUID.randomUUID().toString().replace("-", ""));
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "png" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return java.util.Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext) ? ext : "png";
    }

    @GetMapping("/**")
    public ResponseEntity<InputStreamResource> download(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = "/api/files/";
        String objectName = UriUtils.decode(uri.substring(uri.indexOf(prefix) + prefix.length()), StandardCharsets.UTF_8);
        if (objectName.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        MinioStorageService.StoredImage image = storageService.open(objectName);
        MediaType mediaType = image.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(image.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(image.size())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(new InputStreamResource(image.stream()));
    }
}
