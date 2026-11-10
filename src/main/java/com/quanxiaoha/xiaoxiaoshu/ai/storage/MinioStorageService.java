package com.quanxiaoha.xiaoxiaoshu.ai.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * @author: 犬小哈
 * @description: MinIO 对象存储：启动自动建桶、图片上传、图片读取
 **/
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final MinioClient client;
    private final String bucket;
    private final long maxImageBytes;

    public MinioStorageService(@Value("${app.storage.endpoint}") String endpoint,
                               @Value("${app.storage.access-key}") String accessKey,
                               @Value("${app.storage.secret-key}") String secretKey,
                               @Value("${app.storage.bucket}") String bucket,
                               @Value("${app.storage.max-image-bytes:10485760}") long maxImageBytes) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.maxImageBytes = maxImageBytes;
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("## MinIO 已创建 bucket: {}", bucket);
            }
        } catch (Exception e) {
            // MinIO 没启动时不影响文本聊天，等真正上传时再报错
            log.warn("## MinIO 初始化失败, 图片上传功能不可用: {}", e.getMessage());
        }
    }

    public ImageRef uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的图片");
        }
        if (file.getSize() > maxImageBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片不能超过 " + (maxImageBytes / 1024 / 1024) + "MB");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取图片失败");
        }

        String originalName = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/") && !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 jpg / png / gif / webp 图片");
        }

        BufferedImage image = readImage(bytes);
        Integer width = image == null ? null : image.getWidth();
        Integer height = image == null ? null : image.getHeight();
        String tone = image == null ? null : describeTone(image);

        String objectName = "images/" + LocalDate.now() + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + extension;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, bytes.length, -1)
                    .contentType(contentType.startsWith("image/") ? contentType : "image/" + extension)
                    .build());
        } catch (Exception e) {
            log.error("## 图片上传 MinIO 失败, object={}", objectName, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储服务不可用，请确认 MinIO 已启动");
        }

        return new ImageRef("/api/files/" + objectName, objectName, originalName, contentType,
                bytes.length, width, height, tone);
    }

    public StoredImage open(String objectName) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            return new StoredImage(response, stat.contentType(), stat.size());
        } catch (ErrorResponseException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        } catch (Exception e) {
            log.warn("## 读取图片失败, object={}: {}", objectName, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储服务不可用");
        }
    }

    public record StoredImage(InputStream stream, String contentType, long size) {
    }

    /**
     * 上传单个分片（用于大文件分片上传）
     */
    public void uploadChunk(String uploadId, int chunkIndex, byte[] chunkData, String contentType) {
        String chunkObject = "chunks/" + uploadId + "/" + chunkIndex;
        try (InputStream in = new ByteArrayInputStream(chunkData)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(chunkObject)
                    .stream(in, chunkData.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            log.error("## 分片上传失败, uploadId={}, chunk={}", uploadId, chunkIndex, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "分片上传失败");
        }
    }

    /**
     * 合并所有分片为完整文件
     */
    public ImageRef mergeChunks(String uploadId, int totalChunks, String objectName, String originalName,
                                String contentType, long totalSize) {
        byte[] merged = new byte[(int) totalSize];
        int offset = 0;

        for (int i = 0; i < totalChunks; i++) {
            String chunkObject = "chunks/" + uploadId + "/" + i;
            try (InputStream chunkStream = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(chunkObject)
                    .build())) {
                byte[] chunkData = chunkStream.readAllBytes();
                System.arraycopy(chunkData, 0, merged, offset, chunkData.length);
                offset += chunkData.length;
            } catch (Exception e) {
                log.error("## 读取分片失败, uploadId={}, chunk={}", uploadId, i, e);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片 " + i + " 缺失或损坏");
            }
        }

        try (InputStream in = new ByteArrayInputStream(merged)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, merged.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            log.error("## 合并文件上传 MinIO 失败, object={}", objectName, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件合并失败");
        }

        // 清理分片
        for (int i = 0; i < totalChunks; i++) {
            try {
                client.removeObject(io.minio.RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object("chunks/" + uploadId + "/" + i)
                        .build());
            } catch (Exception ignored) {
                // 清理失败不影响主流程
            }
        }

        BufferedImage image = readImage(merged);
        Integer width = image == null ? null : image.getWidth();
        Integer height = image == null ? null : image.getHeight();
        String tone = image == null ? null : describeTone(image);

        return new ImageRef("/api/files/" + objectName, objectName, originalName, contentType,
                merged.length, width, height, tone);
    }

    /**
     * 获取已上传的分片列表（用于断点续传）
     */
    public java.util.List<Integer> getUploadedChunks(String uploadId) {
        java.util.List<Integer> chunks = new java.util.ArrayList<>();
        try {
            Iterable<io.minio.Result<io.minio.messages.Item>> results = client.listObjects(
                    io.minio.ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix("chunks/" + uploadId + "/")
                            .recursive(true)
                            .build());
            for (io.minio.Result<io.minio.messages.Item> result : results) {
                io.minio.messages.Item item = result.get();
                String name = item.objectName();
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash >= 0) {
                    try {
                        chunks.add(Integer.parseInt(name.substring(lastSlash + 1)));
                    } catch (NumberFormatException ignored) {
                        // 跳过非数字后缀
                    }
                }
            }
        } catch (Exception e) {
            log.warn("## 列出分片失败, uploadId={}: {}", uploadId, e.getMessage());
        }
        java.util.Collections.sort(chunks);
        return chunks;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "png" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext) ? ext : "png";
    }

    private BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 本地演示模式下没有视觉模型，用平均色给出一句可感知的图片描述
     */
    private String describeTone(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        int stepX = Math.max(1, image.getWidth() / 48);
        int stepY = Math.max(1, image.getHeight() / 48);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return "未知色调";
        }
        int r = (int) (red / count);
        int g = (int) (green / count);
        int b = (int) (blue / count);
        int brightness = (r + g + b) / 3;
        int warmth = r - b;

        String temperature = warmth > 18 ? "暖色调" : (warmth < -18 ? "冷色调" : "中性色调");
        String light = brightness > 180 ? "整体明亮" : (brightness < 80 ? "整体偏暗" : "亮度适中");
        return temperature + "·" + light;
    }
}
