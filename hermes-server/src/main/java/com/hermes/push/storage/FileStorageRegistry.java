package com.hermes.push.storage;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.storage.local.LocalFileStorage;
import com.hermes.push.storage.s3.S3FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 storageKey 解析并缓存 FileStorage 实现（LOCAL + S3）。 */
@Component
public class FileStorageRegistry {
    private final StorageConfigService configService;
    private final Path localRoot;
    private final Map<String, FileStorage> cache = new ConcurrentHashMap<>();

    public FileStorageRegistry(StorageConfigService configService,
            @Value("${hermes.storage.local-root:data/storage}") String localRoot) {
        this.configService = configService;
        this.localRoot = Path.of(localRoot);
    }

    public FileStorage resolve(String uri) {
        return resolveByKey(LogicalUri.parse(uri).storageKey());
    }

    public FileStorage resolveByKey(String storageKey) {
        return cache.computeIfAbsent(storageKey, this::build);
    }

    @EventListener
    public void onConfigChanged(StorageConfigChangedEvent e) {
        cache.remove(e.storageKey());
    }

    private FileStorage build(String storageKey) {
        DecryptedStorage c = configService.requireDecrypted(storageKey);
        return switch (c.type()) {
            case LOCAL -> new LocalFileStorage(c.storageKey(), localRoot, c.prefix());
            case S3 -> {
                if (isBlank(c.endpoint()) || isBlank(c.bucket())
                    || isBlank(c.accessKey()) || isBlank(c.secretKey())) {
                    throw new BizException(ErrorCode.STO_001,
                        "S3 配置缺少必填字段 (endpoint/bucket/accessKey/secretKey): " + storageKey);
                }
                String region = c.region() == null ? "us-east-1" : c.region();
                StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(c.accessKey(), c.secretKey()));
                S3Client client = S3Client.builder()
                    .region(Region.of(region))
                    .endpointOverride(URI.create(c.endpoint()))
                    .credentialsProvider(creds)
                    .build();
                S3Presigner presigner = S3Presigner.builder()
                    .region(Region.of(region))
                    .endpointOverride(URI.create(c.endpoint()))
                    .credentialsProvider(creds)
                    .build();
                yield new S3FileStorage(c.storageKey(), client, presigner, c.bucket(), c.prefix());
            }
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
