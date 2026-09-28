package com.hermes.push.storage;

/** 对外 VO（不含任何密钥）。 */
public record StorageVO(
    Long id, String storageKey, String displayName, String storageType,
    String endpoint, String region, String bucket, String prefix, Integer enabled) {}
