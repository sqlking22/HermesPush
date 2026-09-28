# FileStorage 抽象（LOCAL + S3）子项目设计

- **日期**：2026-09-28
- **里程碑**：M2（FR-STO-01 / FR-STO-02 / FR-STO-03）
- **状态**：方案已评审通过

## 一、范围与目标

实现存储抽象地基：`FileStorage` 接口 + LOCAL/S3 两个后端 + 逻辑 URI + 多后端配置表。模板库、Excel 产物、企微 file 推送等后续子项目都依赖它。

**不在本子项目**：FR-EXE-08 产物归档上传与保留期清理、HDFS（FR-STO-04 / M5）、presignedUrl 的钉钉图片消费（FR-CH-08 / M4b）。

## 二、接口（沿用设计 spec ST-1，已冻结）

```java
public interface FileStorage {
    StorageType type();
    String put(String path, InputStream in, long size);   // 返回逻辑 URI hp://{storageId}/{path}
    InputStream get(String uri);
    boolean delete(String uri);
    boolean exists(String uri);
    Optional<String> presignedUrl(String uri, Duration ttl);  // LOCAL 返回 empty
}
```

路径规范（ST-4）：`templates/{templateId}/v{versionNo}/{filename}`、`artifacts/{yyyy}/{MM}/{dd}/{taskId}/{execId}/{artifactKey}.{ext}`。

## 三、组件与包结构

新增包 `com.hermes.push.storage`（位于 `hermes-server` 单模块内，与 `channel`/`render` 并级；SPI 边界清晰，供 M5 抽 HDFS 独立模块）：

| 组件 | 职责 |
|---|---|
| `FileStorage` | 接口（上述） |
| `StorageType` | 枚举 `LOCAL`、`S3`（`HDFS` 预留位，不入库） |
| `LogicalUri` | 解析/校验 `hp://{storageId}/{path}`；路径防穿越（`..`、绝对路径拒绝）；`artifactKey` 限 `[a-zA-Z0-9_]+` |
| `FileStorageRegistry` | 按 storageId 解析配置→实例（懒加载 + 缓存），仿 `channel.PushChannelRegistry` |
| `storage.local.LocalFileStorage` | 根目录可配；`presignedUrl` 返回 empty |
| `storage.s3.S3FileStorage` | AWS S3 SDK v2；put/get/delete/exists + presigner |
| `storage.StorageConfigService` | `hp_storage` CRUD + 出参脱敏（accessKey/secretKey 不外泄） |

## 四、数据模型

Flyway `V4__storage.sql` 新建 `hp_storage`：

```sql
CREATE TABLE hp_storage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  storage_key VARCHAR(64) NOT NULL,        -- 逻辑 storageId，唯一
  display_name VARCHAR(128) NOT NULL,
  storage_type VARCHAR(16) NOT NULL,       -- LOCAL / S3
  endpoint VARCHAR(256) NULL,              -- S3 用；LOCAL 空
  region VARCHAR(64) NULL,
  bucket VARCHAR(128) NULL,
  prefix VARCHAR(256) NULL,                -- 路径前缀
  access_key_enc VARCHAR(1024) NULL,       -- AES-GCM 密文(复用 M1 AesGcmCipher)
  secret_key_enc VARCHAR(1024) NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_storage_key (storage_key)
)
```

- accessKey / secretKey 复用 M1 的 `AesGcmCipher`（key_id + nonce + ciphertext + tag，SEC-1 已冻结密文格式）。
- 出参 VO 不返回明文密钥。

## 五、逻辑 URI 与后端解析

- 入库存 `hp://{storageKey}/{path}` 形式的逻辑 URI，不存物理绝对路径（ST-2）。
- `FileStorageRegistry.resolve(uri)` → 解析 storageKey → 查 `hp_storage`（enabled=1）→ 构建/缓存对应后端实例。

## 六、错误码（`common.ErrorCode` 增补）

| 码 | 语义 |
|---|---|
| `STO-001` | 存储后端不存在或未启用 |
| `STO-002` | 文件读写失败 |
| `STO-003` | 路径非法（穿越） |

## 七、测试策略

- **LOCAL**：临时目录集成测试（put/get/delete/exists 全链路）。
- **S3**：**进程内 S3 仿真**（Adobe `s3mock`，纯 JVM、无需 Docker——本机无 Docker）做 put/get/delete/exists/presign 集成测试；若 s3mock 版本/坐标有变，退路为 mock `S3Client` 单测。
- **逻辑 URI / 路径校验**：单测覆盖 `..`、绝对路径、非法 artifactKey 拒绝。
- **配置加密**：accessKey round-trip 断言不落明文（复用 AesGcmCipher 既有单测风格）。

## 八、明确不做

FR-EXE-08 归档上传与保留期清理、HDFS、presignedUrl 的钉钉图片消费、界面（存储管理页随后续子项目接）。