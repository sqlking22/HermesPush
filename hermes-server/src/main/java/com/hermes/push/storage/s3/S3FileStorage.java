package com.hermes.push.storage.s3;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.storage.FileStorage;
import com.hermes.push.storage.LogicalUri;
import com.hermes.push.storage.StorageType;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/** S3 兼容后端（阿里云 OSS / MinIO 统一走 S3 协议）。 */
public class S3FileStorage implements FileStorage {
    private final String storageKey;
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3FileStorage(String storageKey, S3Client client, String bucket, String prefix) {
        this.storageKey = storageKey;
        this.client = client;
        this.bucket = bucket;
        this.prefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
    }

    @Override public StorageType type() { return StorageType.S3; }

    @Override
    public String put(String path, InputStream in, long size) {
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key(path)).contentLength(size).build(),
                RequestBody.fromInputStream(in, size));
            return LogicalUri.of(storageKey, path).toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
    }

    @Override
    public InputStream get(String uri) {
        try {
            ResponseInputStream<GetObjectResponse> resp = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key(LogicalUri.parse(uri).path())).build());
            return resp;
        } catch (Exception e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
    }

    @Override
    public boolean delete(String uri) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(key(LogicalUri.parse(uri).path())).build());
            return true;
        } catch (Exception e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
    }

    @Override
    public boolean exists(String uri) {
        try {
            client.headObject(HeadObjectRequest.builder()
                .bucket(bucket).key(key(LogicalUri.parse(uri).path())).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw new BizException(ErrorCode.STO_002, e);
        }
    }

    @Override
    public Optional<String> presignedUrl(String uri, Duration ttl) {
        return Optional.empty(); // Task 8 实现
    }

    private String key(String path) { return prefix + path; }
}
