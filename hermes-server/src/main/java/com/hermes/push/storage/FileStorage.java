package com.hermes.push.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/** 存储后端统一接口（设计 spec ST-1）。put 返回逻辑 URI hp://{storageKey}/{path}。 */
public interface FileStorage {
    StorageType type();
    String put(String path, InputStream in, long size);
    InputStream get(String uri);
    boolean delete(String uri);
    boolean exists(String uri);
    Optional<String> presignedUrl(String uri, Duration ttl);
}
