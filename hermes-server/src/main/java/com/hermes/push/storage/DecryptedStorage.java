package com.hermes.push.storage;

/** 解密后的存储配置（服务端内部使用，绝不外发）。 */
public record DecryptedStorage(
    Long id, String storageKey, String displayName, StorageType type,
    String endpoint, String region, String bucket, String prefix,
    String accessKey, String secretKey) {}
