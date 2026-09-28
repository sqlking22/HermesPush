package com.hermes.push.storage;

import com.hermes.push.storage.local.LocalFileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 storageKey 解析并缓存 FileStorage 实现（S3 实现于 Task 7 接入）。 */
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

    private FileStorage build(String storageKey) {
        DecryptedStorage c = configService.requireDecrypted(storageKey);
        return switch (c.type()) {
            case LOCAL -> new LocalFileStorage(c.storageKey(), localRoot, c.prefix());
            case S3 -> throw new com.hermes.push.common.BizException(
                com.hermes.push.common.ErrorCode.STO_001, "S3 后端未实现(Task 7)");
        };
    }
}
