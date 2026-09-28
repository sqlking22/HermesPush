package com.hermes.push.storage;

/** 存储配置变更事件（update/delete 后发布，供 registry 驱逐缓存）。 */
public record StorageConfigChangedEvent(String storageKey) {}
