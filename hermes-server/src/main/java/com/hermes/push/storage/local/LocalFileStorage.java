package com.hermes.push.storage.local;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.storage.FileStorage;
import com.hermes.push.storage.LogicalUri;
import com.hermes.push.storage.StorageType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** 本地磁盘后端（开发/单机）。写 root/prefix/path。 */
public class LocalFileStorage implements FileStorage {
    private final String storageKey;
    private final Path root;
    private final String prefix;

    public LocalFileStorage(String storageKey, Path root, String prefix) {
        this.storageKey = storageKey;
        this.root = root;
        this.prefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
    }

    @Override public StorageType type() { return StorageType.LOCAL; }

    @Override
    public String put(String path, InputStream in, long size) {
        Path target = resolve(path);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream os = Files.newOutputStream(target)) {
                in.transferTo(os);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
        return LogicalUri.of(storageKey, path).toString();
    }

    @Override
    public InputStream get(String uri) {
        Path target = resolve(LogicalUri.parse(uri).path());
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
    }

    @Override
    public boolean delete(String uri) {
        try {
            return Files.deleteIfExists(resolve(LogicalUri.parse(uri).path()));
        } catch (IOException e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
    }

    @Override
    public boolean exists(String uri) {
        return Files.exists(resolve(LogicalUri.parse(uri).path()));
    }

    @Override
    public Optional<String> presignedUrl(String uri, Duration ttl) {
        return Optional.empty();
    }

    private Path resolve(String path) {
        Path p = root.resolve(prefix).resolve(path).normalize();
        // 双保险：normalize 后仍需在 root 内
        if (!p.startsWith(root)) {
            throw new BizException(ErrorCode.STO_003, "路径越界: " + path);
        }
        return p;
    }
}
