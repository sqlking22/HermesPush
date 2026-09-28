# FileStorage 抽象（LOCAL + S3）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现存储抽象地基——`FileStorage` 接口 + LOCAL/S3 后端 + 逻辑 URI + 多后端配置表，供模板库、Excel 产物、企微 file 推送等后续子项目使用。

**Architecture:** 新增 `com.hermes.push.storage` 包（位于 `hermes-server` 单模块内）。`FileStorage` 接口（ST-1 已冻结）由 `LocalFileStorage`（本地磁盘）与 `S3FileStorage`（AWS S3 SDK v2，覆盖 OSS/MinIO）实现；`FileStorageRegistry` 按 storageKey 查 `hp_storage` 配置（accessKey/secretKey AES-GCM 加密）动态构建并缓存实例；路径用逻辑 URI `hp://{storageKey}/{path}`，入库存逻辑 URI 不存物理绝对路径。

**Tech Stack:** Java 21 · Spring Boot 3.3.4 · MyBatis-Plus 3.5.7 · Lombok · Flyway · AWS S3 SDK v2 · JUnit 5 + Mockito · FastExcel 无关（此计划不含 Excel）。

## Global Constraints

- 运行测试加 `-DargLine=-Xmx2g`（本机 16G 内存踩线，默认堆会 native OOM errno 1455；见 `.claude` 记忆「M1 测试环境 OOM」）。命令统一用 `./mvnw.cmd`（Windows wrapper）。
- 加密一律复用 `com.hermes.push.security.AesGcmCipher`（`encrypt(String)→String`、`decrypt(String)→String`，失败抛 `BizException(SYS_001)`）；构造器入参 `MasterKeyProvider`，测试环境需 `HP_MASTER_KEY` 环境变量（M1 既有约定：缺省 `123456` 是 DB 密码，master key 用测试专值，见各测试 Step）。
- 错误码追加进 `com.hermes.push.common.ErrorCode` 枚举，格式 `XXX_NNN("XXX-NNN","用户话术","建议")`。
- 异常用 `com.hermes.push.common.BizException`（构造器 `(ErrorCode)` / `(ErrorCode,String)` / `(ErrorCode,Throwable)`）。
- 实体用 Lombok `@Data` + `@TableName` + `@TableId(type = IdType.AUTO)`；`created_at` 用 `@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)`；Mapper 用 `@Mapper interface X extends BaseMapper<T>`。
- Service 用构造器注入（无 `@Autowired`），`@Service`；DB 写操作用 `@Transactional`。
- 包名统一 `com.hermes.push.storage.*`；本子项目**不做 REST Controller / 界面**（存储管理页随后续子项目）。
- 测试类放 `hermes-server/src/test/java/com/hermes/push/storage/`；纯单测用 JUnit5+Mockito，涉及 DB/加密/Spring 的用 `@SpringBootTest`（连 `hermes_test`，Flyway 自动建表）。
- S3 集成：本机无 Docker，S3 后端单测用 **Mockito mock `S3Client`/`S3Presigner`**（验证接线与 SDK 调用参数）；真实 S3 往返留给有 OSS/MinIO 的环境补做集成验证。

---

### Task 1: 领域基础（StorageType + FileStorage 接口 + 错误码）

**Files:**
- Create: `hermes-server/src/main/java/com/hermes/push/storage/StorageType.java`
- Create: `hermes-server/src/main/java/com/hermes/push/storage/FileStorage.java`
- Modify: `hermes-server/src/main/java/com/hermes/push/common/ErrorCode.java`（末尾追加 3 个枚举项）

**Interfaces:**
- Produces: `StorageType` 枚举（`LOCAL, S3`）；`FileStorage` 接口（方法见下，后续任务全部依赖此签名）；`ErrorCode.STO_001/STO_002/STO_003/STO_004`。

- [ ] **Step 1: 写失败测试**

```java
// hermes-server/src/test/java/com/hermes/push/storage/StorageTypeTest.java
package com.hermes.push.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageTypeTest {
    @Test
    void valuesCoverLocalAndS3() {
        assertEquals(2, StorageType.values().length);
        assertSame(StorageType.LOCAL, StorageType.valueOf("LOCAL"));
        assertSame(StorageType.S3, StorageType.valueOf("S3"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=StorageTypeTest test`
Expected: 编译失败（`StorageType` 不存在）。

- [ ] **Step 3: 写实现**

```java
// StorageType.java
package com.hermes.push.storage;

public enum StorageType {
    LOCAL, S3
}
```

```java
// FileStorage.java
package com.hermes.push.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * 存储后端统一接口（设计 spec ST-1）。
 * 所有方法都以逻辑 URI（hp://{storageKey}/{path}）为对象；put 返回逻辑 URI。
 */
public interface FileStorage {
    StorageType type();

    /** 写入并返回逻辑 URI。size 用于 S3 流式长度。 */
    String put(String path, InputStream in, long size);

    InputStream get(String uri);
    boolean delete(String uri);
    boolean exists(String uri);

    /** 可选能力：生成预签名下载 URL。LOCAL 返回 empty。 */
    Optional<String> presignedUrl(String uri, Duration ttl);
}
```

`ErrorCode.java` 末尾（`AUTH_002` 之后）追加：

```java
  STO_001("STO-001","存储后端不存在或未启用","检查存储配置"),
  STO_002("STO-002","文件读写失败","检查存储后端可用性与权限"),
  STO_003("STO-003","路径非法","检查路径是否含非法字符或穿越"),
  STO_004("STO-004","存储键已存在","换一个存储键"),
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=StorageTypeTest test`
Expected: PASS（1 test）。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/java/com/hermes/push/storage/StorageType.java hermes-server/src/main/java/com/hermes/push/storage/FileStorage.java hermes-server/src/main/java/com/hermes/push/common/ErrorCode.java hermes-server/src/test/java/com/hermes/push/storage/StorageTypeTest.java
git commit -m "feat(storage): StorageType/FileStorage 接口与错误码"
```

---

### Task 2: LogicalUri 解析与路径校验

**Files:**
- Create: `hermes-server/src/main/java/com/hermes/push/storage/LogicalUri.java`
- Test: `hermes-server/src/test/java/com/hermes/push/storage/LogicalUriTest.java`

**Interfaces:**
- Consumes: `ErrorCode.STO_003`、`BizException`。
- Produces: `LogicalUri.of(String storageKey, String path)`、`LogicalUri.parse(String uri)`、`storageKey()`、`path()`、`toString()`（`hp://key/path`）。

- [ ] **Step 1: 写失败测试**

```java
// LogicalUriTest.java
package com.hermes.push.storage;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogicalUriTest {
    @Test
    void buildAndParseRoundTrip() {
        LogicalUri u = LogicalUri.of("oss-main", "artifacts/2026/09/28/1/2/a.xlsx");
        assertEquals("hp://oss-main/artifacts/2026/09/28/1/2/a.xlsx", u.toString());
        LogicalUri p = LogicalUri.parse(u.toString());
        assertEquals("oss-main", p.storageKey());
        assertEquals("artifacts/2026/09/28/1/2/a.xlsx", p.path());
    }

    @Test
    void rejectsTraversal() {
        assertThrows(BizException.class, () -> LogicalUri.parse("hp://oss-main/../etc/passwd"));
        assertThrows(BizException.class, () -> LogicalUri.of("oss-main", "a/../../b"));
    }

    @Test
    void rejectsAbsoluteAndBackslash() {
        assertThrows(BizException.class, () -> LogicalUri.parse("hp://oss-main//etc"));
        assertThrows(BizException.class, () -> LogicalUri.of("oss-main", "C:\\a\\b"));
    }

    @Test
    void rejectsBadStorageKey() {
        assertThrows(BizException.class, () -> LogicalUri.parse("hp:///path"));
        assertThrows(BizException.class, () -> LogicalUri.of("bad key!", "a"));
    }

    @Test
    void errorCodeIsSto003() {
        BizException e = assertThrows(BizException.class, () -> LogicalUri.parse("hp://k/.."));
        assertEquals(ErrorCode.STO_003, e.getErrorCode());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=LogicalUriTest test`
Expected: 编译失败（`LogicalUri` 不存在）。

- [ ] **Step 3: 写实现**

```java
// LogicalUri.java
package com.hermes.push.storage;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import java.util.regex.Pattern;

/** 逻辑 URI：hp://{storageKey}/{path}。入库只存此形式，不存物理绝对路径（ST-2）。 */
public final class LogicalUri {
    public static final String SCHEME = "hp";
    private static final Pattern STORAGE_KEY = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern SEGMENT = Pattern.compile("[a-zA-Z0-9_.-]+");

    private final String storageKey;
    private final String path;

    private LogicalUri(String storageKey, String path) {
        this.storageKey = storageKey;
        this.path = path;
    }

    public static LogicalUri of(String storageKey, String path) {
        return new LogicalUri(validateKey(storageKey), validatePath(path));
    }

    public static LogicalUri parse(String uri) {
        if (uri == null || !uri.startsWith("hp://")) {
            throw new BizException(ErrorCode.STO_003, "非法 URI: " + uri);
        }
        String rest = uri.substring("hp://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new BizException(ErrorCode.STO_003, "非法 URI: " + uri);
        }
        return new LogicalUri(validateKey(rest.substring(0, slash)),
                validatePath(rest.substring(slash + 1)));
    }

    private static String validateKey(String key) {
        if (key == null || !STORAGE_KEY.matcher(key).matches()) {
            throw new BizException(ErrorCode.STO_003, "非法存储键: " + key);
        }
        return key;
    }

    private static String validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.STO_003, "路径为空");
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/")) {
            throw new BizException(ErrorCode.STO_003, "非法绝对路径: " + path);
        }
        for (String seg : p.split("/", -1)) {
            if (seg.isBlank() || "..".equals(seg) || !SEGMENT.matcher(seg).matches()) {
                throw new BizException(ErrorCode.STO_003, "非法路径段: " + path);
            }
        }
        return p;
    }

    public String storageKey() { return storageKey; }
    public String path() { return path; }
    @Override public String toString() { return "hp://" + storageKey + "/" + path; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=LogicalUriTest test`
Expected: PASS（5 tests）。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/java/com/hermes/push/storage/LogicalUri.java hermes-server/src/test/java/com/hermes/push/storage/LogicalUriTest.java
git commit -m "feat(storage): LogicalUri 逻辑 URI 与路径防穿越校验"
```

---

### Task 3: hp_storage 迁移 + Storage 实体 + Mapper

**Files:**
- Create: `hermes-server/src/main/resources/db/migration/V4__storage.sql`
- Create: `hermes-server/src/main/java/com/hermes/push/storage/Storage.java`
- Create: `hermes-server/src/main/java/com/hermes/push/storage/StorageMapper.java`
- Test: `hermes-server/src/test/java/com/hermes/push/storage/StorageMapperTest.java`

**Interfaces:**
- Produces: `Storage` 实体（getters/setters via Lombok）；`StorageMapper extends BaseMapper<Storage>`；`hp_storage` 表。

- [ ] **Step 1: 写迁移**

```sql
-- V4__storage.sql
CREATE TABLE hp_storage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  storage_key VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  storage_type VARCHAR(16) NOT NULL COMMENT 'LOCAL|S3',
  endpoint VARCHAR(256) NULL,
  region VARCHAR(64) NULL,
  bucket VARCHAR(128) NULL,
  prefix VARCHAR(256) NULL,
  access_key_enc VARCHAR(1024) NULL,
  secret_key_enc VARCHAR(1024) NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_storage_key (storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 写实体与 Mapper**

```java
// Storage.java
package com.hermes.push.storage;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_storage")
public class Storage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String storageKey;
    private String displayName;
    private String storageType;
    private String endpoint;
    private String region;
    private String bucket;
    private String prefix;
    private String accessKeyEnc;
    private String secretKeyEnc;
    private Integer enabled;
    private String createdBy;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
```

```java
// StorageMapper.java
package com.hermes.push.storage;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StorageMapper extends BaseMapper<Storage> {
}
```

- [ ] **Step 3: 写失败测试（表存在 + mapper 落库）**

```java
// StorageMapperTest.java
package com.hermes.push.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StorageMapperTest {
    @Autowired StorageMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertAndSelect() {
        // 确保干净
        jdbc.update("DELETE FROM hp_storage WHERE storage_key = 't1'");
        Storage s = new Storage();
        s.setStorageKey("t1");
        s.setDisplayName("测试");
        s.setStorageType("LOCAL");
        s.setEnabled(1);
        s.setCreatedBy("test");
        mapper.insert(s);
        Storage got = mapper.selectById(s.getId());
        assertEquals("t1", got.getStorageKey());
        assertEquals("LOCAL", got.getStorageType());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `set HP_MASTER_KEY=testkey&& ./mvnw.cmd -DargLine=-Xmx2g -Dtest=StorageMapperTest test`（PowerShell 用 `$env:HP_MASTER_KEY='testkey'`；若测试不需解密可免，但 `AesGcmCipher` bean 需构造故带上）
Expected: PASS。若报 `hp_storage` 不存在，说明 Flyway 未跑新迁移——在 `hermes-server/src/test/resources/application-test.yaml` 确认 `flyway.enabled=true`（M1 已配），测试时 Flyway 自动应用 V4。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/resources/db/migration/V4__storage.sql hermes-server/src/main/java/com/hermes/push/storage/Storage.java hermes-server/src/main/java/com/hermes/push/storage/StorageMapper.java hermes-server/src/test/java/com/hermes/push/storage/StorageMapperTest.java
git commit -m "feat(storage): hp_storage 表/实体/Mapper(V4 迁移)"
```

---

### Task 4: StorageConfigService 加密 CRUD

**Files:**
- Create: `hermes-server/src/main/java/com/hermes/push/storage/StorageConfigService.java`
- Create: `hermes-server/src/main/java/com/hermes/push/storage/DecryptedStorage.java`
- Create: `hermes-server/src/main/java/com/hermes/push/storage/StorageVO.java`
- Test: `hermes-server/src/test/java/com/hermes/push/storage/StorageConfigServiceTest.java`

**Interfaces:**
- Consumes: `Storage`/`StorageMapper`/`AesGcmCipher`（`encrypt(String)→String`、`decrypt`）。
- Produces:
  - `Long save(String storageKey, String displayName, String type, String endpoint, String region, String bucket, String prefix, String accessKey, String secretKey, boolean enabled, String operator)`
  - `void update(Long id, ... 同上 ...)`
  - `void delete(Long id)`
  - `List<StorageVO> list()`
  - `StorageVO get(Long id)`
  - `DecryptedStorage requireDecrypted(String storageKey)` —— 供 registry 内部使用（返回解密密钥）
- `DecryptedStorage` record：`(Long id, String storageKey, String displayName, StorageType type, String endpoint, String region, String bucket, String prefix, String accessKey, String secretKey)`。
- `StorageVO` record：`(Long id, String storageKey, String displayName, String storageType, String endpoint, String region, String bucket, String prefix, Integer enabled)`（**不含密钥**）。

- [ ] **Step 1: 写失败测试（加密不落明文 + round-trip + 唯一键）**

```java
// StorageConfigServiceTest.java
package com.hermes.push.storage;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StorageConfigServiceTest {
    @Autowired StorageConfigService svc;
    @Autowired JdbcTemplate jdbc;

    private void clean(String key) { jdbc.update("DELETE FROM hp_storage WHERE storage_key = ?", key); }

    @Test
    void saveEncryptsKeysAndRoundtrips() {
        clean("s3-main");
        Long id = svc.save("s3-main", "生产S3", "S3", "http://minio.internal:9000",
            "cn", "hermes", "prefix/", "AKIA", "SECRET", true, "admin");

        String stored = jdbc.queryForObject(
            "SELECT access_key_enc FROM hp_storage WHERE id=?", String.class, id);
        assertFalse(stored.contains("AKIA"), "accessKey 不应落明文");

        DecryptedStorage d = svc.requireDecrypted("s3-main");
        assertEquals("AKIA", d.accessKey());
        assertEquals("SECRET", d.secretKey());
        assertEquals(StorageType.S3, d.type());
        assertEquals("hermes", d.bucket());
    }

    @Test
    void duplicateKeyRejected() {
        clean("dup");
        svc.save("dup", "a", "LOCAL", null, null, null, null, null, null, true, "admin");
        BizException e = assertThrows(BizException.class,
            () -> svc.save("dup", "b", "LOCAL", null, null, null, null, null, null, true, "admin"));
        assertEquals(ErrorCode.STO_004, e.getErrorCode());
    }

    @Test
    void voDoesNotLeakKeys() {
        clean("s3-vo");
        svc.save("s3-vo", "v", "S3", "e", "r", "b", "p", "AK", "SK", true, "admin");
        var vo = svc.get(svc.list().stream()
            .filter(v -> "s3-vo".equals(v.storageKey())).findFirst().orElseThrow().id());
        // StorageVO 为 record 且不含密钥字段，编译期即保证不泄露；此处仅验证可取
        assertEquals("s3-vo", vo.storageKey());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$env:HP_MASTER_KEY='testkey'; ./mvnw.cmd -DargLine=-Xmx2g -Dtest=StorageConfigServiceTest test`
Expected: 编译失败（`StorageConfigService` 不存在）。

- [ ] **Step 3: 写实现**

```java
// DecryptedStorage.java
package com.hermes.push.storage;

/** 解密后的存储配置（服务端内部使用，绝不外发）。 */
public record DecryptedStorage(
    Long id, String storageKey, String displayName, StorageType type,
    String endpoint, String region, String bucket, String prefix,
    String accessKey, String secretKey) {}
```

```java
// StorageVO.java
package com.hermes.push.storage;

/** 对外 VO（不含任何密钥）。 */
public record StorageVO(
    Long id, String storageKey, String displayName, String storageType,
    String endpoint, String region, String bucket, String prefix, Integer enabled) {}
```

```java
// StorageConfigService.java
package com.hermes.push.storage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.security.AesGcmCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StorageConfigService {
    private final StorageMapper mapper;
    private final AesGcmCipher cipher;

    public StorageConfigService(StorageMapper mapper, AesGcmCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    @Transactional
    public Long save(String storageKey, String displayName, String type,
        String endpoint, String region, String bucket, String prefix,
        String accessKey, String secretKey, boolean enabled, String operator) {
        Storage s = new Storage();
        s.setStorageKey(storageKey);
        apply(s, displayName, type, endpoint, region, bucket, prefix, accessKey, secretKey, enabled);
        s.setEnabled(enabled ? 1 : 0);
        s.setCreatedBy(operator);
        try {
            mapper.insert(s);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.STO_004, "存储键已存在: " + storageKey);
        }
        return s.getId();
    }

    @Transactional
    public void update(Long id, String displayName, String type, String endpoint,
        String region, String bucket, String prefix, String accessKey, String secretKey,
        boolean enabled, String operator) {
        Storage s = require(id);
        apply(s, displayName, type, endpoint, region, bucket, prefix, accessKey, secretKey, enabled);
        s.setEnabled(enabled ? 1 : 0);
        mapper.updateById(s);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
    }

    public List<StorageVO> list() {
        return mapper.selectList(new QueryWrapper<Storage>().orderByAsc("id"))
            .stream().map(this::toVO).toList();
    }

    public StorageVO get(Long id) {
        return toVO(require(id));
    }

    /** 按 storageKey 取解密配置；不存在或停用抛 STO_001。 */
    public DecryptedStorage requireDecrypted(String storageKey) {
        Storage s = mapper.selectOne(new QueryWrapper<Storage>().eq("storage_key", storageKey));
        if (s == null || (s.getEnabled() != null && s.getEnabled() == 0)) {
            throw new BizException(ErrorCode.STO_001, "存储后端不存在或未启用: " + storageKey);
        }
        return new DecryptedStorage(
            s.getId(), s.getStorageKey(), s.getDisplayName(),
            StorageType.valueOf(s.getStorageType()),
            s.getEndpoint(), s.getRegion(), s.getBucket(), s.getPrefix(),
            decrypt(s.getAccessKeyEnc()), decrypt(s.getSecretKeyEnc()));
    }

    private void apply(Storage s, String displayName, String type, String endpoint,
        String region, String bucket, String prefix, String accessKey, String secretKey, boolean enabled) {
        s.setDisplayName(displayName);
        s.setStorageType(type);
        s.setEndpoint(endpoint);
        s.setRegion(region);
        s.setBucket(bucket);
        s.setPrefix(prefix);
        // 密钥留空 = 不修改（与 M1 渠道 webhook 一致）
        if (accessKey != null && !accessKey.isBlank()) s.setAccessKeyEnc(cipher.encrypt(accessKey));
        if (secretKey != null && !secretKey.isBlank()) s.setSecretKeyEnc(cipher.encrypt(secretKey));
    }

    private StorageVO toVO(Storage s) {
        return new StorageVO(s.getId(), s.getStorageKey(), s.getDisplayName(),
            s.getStorageType(), s.getEndpoint(), s.getRegion(), s.getBucket(),
            s.getPrefix(), s.getEnabled());
    }

    private Storage require(Long id) {
        Storage s = mapper.selectById(id);
        if (s == null) throw new BizException(ErrorCode.STO_001, "存储后端不存在: " + id);
        return s;
    }

    private String decrypt(String enc) {
        return (enc == null || enc.isBlank()) ? null : cipher.decrypt(enc);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$env:HP_MASTER_KEY='testkey'; ./mvnw.cmd -DargLine=-Xmx2g -Dtest=StorageConfigServiceTest test`
Expected: PASS。注意：`duplicateKeyRejected` 依赖唯一键 `uk_storage_key` 存在；`JP_MASTER_KEY` 需稳定（重跑用同一 `testkey`，否则旧密文解密失败）。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/java/com/hermes/push/storage/StorageConfigService.java hermes-server/src/main/java/com/hermes/push/storage/DecryptedStorage.java hermes-server/src/main/java/com/hermes/push/storage/StorageVO.java hermes-server/src/test/java/com/hermes/push/storage/StorageConfigServiceTest.java
git commit -m "feat(storage): StorageConfigService 加密 CRUD(复用 AesGcmCipher)"
```

---

### Task 5: LocalFileStorage

**Files:**
- Create: `hermes-server/src/main/java/com/hermes/push/storage/local/LocalFileStorage.java`
- Test: `hermes-server/src/test/java/com/hermes/push/storage/local/LocalFileStorageTest.java`

**Interfaces:**
- Consumes: `FileStorage`、`LogicalUri`、`StorageType.LOCAL`。
- Produces: `LocalFileStorage(String storageKey, Path root, String prefix)` setTimeout 构造器；实现 FileStorage 五方法。`put` 返回 `hp://{storageKey}/{path}`。

- [ ] **Step 1: 写失败测试（临时目录 round-trip）**

```java
// LocalFileStorageTest.java
package com.hermes.push.storage.local;

import com.hermes.push.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageTest {
    @TempDir Path tmp;

    private FileStorage storage() {
        return new LocalFileStorage("local", tmp.resolve("root"), "prefix");
    }

    @Test
    void roundtrip() throws Exception {
        FileStorage fs = storage();
        String uri = fs.put("artifacts/2026/09/28/1/1/a.txt",
            stream("hello"), 5);
        assertEquals("hp://local/artifacts/2026/09/28/1/1/a.txt", uri);
        assertTrue(fs.exists(uri));
        assertEquals("hello", new String(fs.get(uri).readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(fs.delete(uri));
        assertFalse(fs.exists(uri));
    }

    @Test
    void presignedUrlEmpty() {
        assertEquals(Optional.empty(), storage().presignedUrl("hp://local/a", Duration.ofMinutes(5)));
    }

    @Test
    void writesUnderPrefix() throws Exception {
        String uri = storage().put("d.txt", stream("x"), 1);
        assertTrue(Files.exists(tmp.resolve("root/prefix/d.txt")));
    }

    private InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=LocalFileStorageTest test`
Expected: 编译失败（`LocalFileStorage` 不存在）。

- [ ] **Step 3: 写实现**

```java
// LocalFileStorage.java
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=LocalFileStorageTest test`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/java/com/hermes/push/storage/local/LocalFileStorage.java hermes-server/src/test/java/com/hermes/push/storage/local/LocalFileStorageTest.java
git commit -m "feat(storage): LocalFileStorage 本地后端"
```

---

### Task 6: FileStorageRegistry（LOCAL 解析 + 缓存 + 错误）

**Files:**
- Create: `hermes-server/src/main/java/com/hermes/push/storage/FileStorageRegistry.java`
- Test: `hermes-server/src/test/java/com/hermes/push/storage/FileStorageRegistryTest.java`

**Interfaces:**
- Consumes: `StorageConfigService.requireDecrypted(storageKey)` → `DecryptedStorage`；`LocalFileStorage`。
- Produces:
  - `FileStorage resolve(String uri)` —— 解析逻辑 URI 的 storageKey 后端
  - `FileStorage resolveByKey(String storageKey)`
  - 实例按 storageKey 缓存；LOCAL 用 `@Value("${hermes.storage.local-root:data/storage}")` 作根目录。

- [ ] **Step 1: 写失败测试（seed LOCAL 配置 → resolve → 往返）**

```java
// FileStorageRegistryTest.java
package com.hermes.push.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FileStorageRegistryTest {
    @Autowired FileStorageRegistry registry;
    @Autowired StorageConfigService svc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void resolvesLocalAndRoundtrips() throws Exception {
        jdbc.update("DELETE FROM hp_storage WHERE storage_key='local-dev'");
        svc.save("local-dev", "本地", "LOCAL", null, null, null, null, null, null, true, "admin");

        FileStorage fs = registry.resolveByKey("local-dev");
        assertEquals(StorageType.LOCAL, fs.type());

        String uri = fs.put("t.txt", new java.io.ByteArrayInputStream("abc".getBytes()), 3);
        assertEquals("hp://local-dev/t.txt", uri);
        assertEquals("abc", new String(fs.get(uri).readAllBytes()));
    }

    @Test
    void unknownKeyThrowsSto001() {
        assertThrows(com.hermes.push.common.BizException.class,
            () -> registry.resolveByKey("no-such-key"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$env:HP_MASTER_KEY='testkey'; ./mvnw.cmd -DargLine=-Xmx2g -Dtest=FileStorageRegistryTest test`
Expected: 编译失败（`FileStorageRegistry` 不存在）。

- [ ] **Step 3: 写实现**

```java
// FileStorageRegistry.java
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$env:HP_MASTER_KEY='testkey'; ./mvnw.cmd -DargLine=-Xmx2g -Dtest=FileStorageRegistryTest test`
Expected: PASS（2 tests）。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/java/com/hermes/push/storage/FileStorageRegistry.java hermes-server/src/test/java/com/hermes/push/storage/FileStorageRegistryTest.java
git commit -m "feat(storage): FileStorageRegistry 按 storageKey 解析缓存(LOCAL)"
```

---

### Task 7: S3FileStorage（AWS S3 SDK v2，mock 测试）

**Files:**
- Modify: `hermes-server/pom.xml`（加 `software.amazon.awssdk:s3` 依赖；版本 `2.25.60`，若分辨率失败则换最新 2.x）
- Create: `hermes-server/src/main/java/com/hermes/push/storage/s3/S3FileStorage.java`
- Test: `hermes-server/src/test/java/com/hermes/push/storage/s3/S3FileStorageTest.java`

**Interfaces:**
- Consumes: `FileStorage`、`LogicalUri`、`StorageType.S3`。
- Produces: `S3FileStorage(String storageKey, S3Client client, String bucket, String prefix)`（`S3Presigner` 于 Task 8 加入构造器）。

- [ ] **Step 1: 加依赖**

`hermes-server/pom.xml` 的 `<dependencies>` 追加：

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.25.60</version>
</dependency>
```

Run `./mvnw.cmd -DargLine=-Xmx2g -f hermes-server/pom.xml dependency:resolve` 确认 `2.25.60` 存在；若 404，改用 Maven Central 上最新 2.x（如 `2.28.x`）并记录实际版本。

- [ ] **Step 2: 写失败测试（mock S3Client 验证接线）**

```java
// S3FileStorageTest.java
package com.hermes.push.storage.s3;

import com.hermes.push.storage.FileStorage;
import com.hermes.push.storage.StorageType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3FileStorageTest {
    private final S3Client client = mock(S3Client.class);

    private FileStorage storage() {
        return new S3FileStorage("s3-main", client, "hermes", "prefix/");
    }

    @Test
    void putUsesBucketAndPrefixedKey() {
        String uri = storage().put("artifacts/a.xlsx", new ByteArrayInputStream(new byte[]{1}), 1);
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(req.capture(), any(RequestBody.class));
        assertEquals("hermes", req.getValue().bucket());
        assertEquals("prefix/artifacts/a.xlsx", req.getValue().key());
        assertEquals("hp://s3-main/artifacts/a.xlsx", uri);
    }

    @Test
    void getReturnsStream() throws Exception {
        GetObjectResponse resp = (GetObjectResponse) GetObjectResponse.builder().build();
        when(client.getObject(any(GetObjectRequest.class)))
            .thenReturn(new ResponseInputStream<>(resp, new ByteArrayInputStream("hi".getBytes())));
        byte[] got = storage().get("hp://s3-main/x").readAllBytes();
        assertEquals("hi", new String(got));
    }

    @Test
    void existsFalseOnS3Exception() {
        when(client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(404).build());
        assertFalse(storage().exists("hp://s3-main/missing"));
    }

    @Test
    void deleteCallsDeleteObject() {
        assertTrue(storage().delete("hp://s3-main/x"));
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void presignedUrlNotYetImplemented() {
        assertEquals(Optional.empty(), storage().presignedUrl("hp://s3-main/x", java.time.Duration.ofMinutes(5)));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=S3FileStorageTest test`
Expected: 编译失败（`S3FileStorage` 不存在）。

- [ ] **Step 4: 写实现（无 presigner 版本）**

```java
// S3FileStorage.java
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
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=S3FileStorageTest test`
Expected: PASS（5 tests）。

- [ ] **Step 6: 提交**

```bash
git add hermes-server/pom.xml hermes-server/src/main/java/com/hermes/push/storage/s3/S3FileStorage.java hermes-server/src/test/java/com/hermes/push/storage/s3/S3FileStorageTest.java
git commit -m "feat(storage): S3FileStorage(AWS S3 SDK v2, OSS/MinIO 兼容)"
```

---

### Task 8: S3 presignedUrl + registry 接入 S3 + 全量回归

**Files:**
- Modify: `hermes-server/src/main/java/com/hermes/push/storage/s3/S3FileStorage.java`（加 `S3Presigner` 构造参数 + presignedUrl 实现）
- Modify: `hermes-server/src/main/java/com/hermes/push/storage/FileStorageRegistry.java`（S3 分支构建 S3Client/S3Presigner）
- Modify: `hermes-server/src/test/java/com/hermes/push/storage/s3/S3FileStorageTest.java`（presignedUrl 测试）

**Interfaces:**
- Consumes: `DecryptedStorage`（含 endpoint/region/accessKey/secretKey）；AWS `S3Client`/`S3Presigner` builder。
- Produces: 完整的 `FileStorageRegistry`（LOCAL + S3 均可解析）；`S3FileStorage.presignedUrl` 返回 `Optional.of(url)`。

- [ ] **Step 1: 改 presignedUrl 测试（替换 Task 7 的 empty 断言）**

在 `S3FileStorageTest` 中把 `presignedUrlNotYetImplemented` 换成：

```java
    @Test
    void presignedUrlUsesPresigner() {
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest pr = mock(PresignedGetObjectRequest.class);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(pr);
        when(pr.url()).thenReturn(java.net.URI.create("https://example/x?sig=abc"));

        FileStorage fs = new S3FileStorage("s3-main", client, presigner, "hermes", "prefix/");
        Optional<String> url = fs.presignedUrl("hp://s3-main/artifacts/a.xlsx", java.time.Duration.ofMinutes(5));
        assertTrue(url.isPresent());
        assertTrue(url.get().contains("sig=abc"));
    }
```

并将 `storage()` 辅助方法改为 `new S3FileStorage("s3-main", client, mock(S3Presigner.class), "hermes", "prefix/")`（其余测试不受影响）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw.cmd -DargLine=-Xmx2g -Dtest=S3FileStorageTest test`
Expected: 编译失败（构造器签名变了）。

- [ ] **Step 3: 实现 presignedUrl + 更新 registry**

`S3FileStorage`：构造器加 `S3Presigner presigner` 参数；`presignedUrl` 实现：

```java
    private final S3Presigner presigner;

    public S3FileStorage(String storageKey, S3Client client, S3Presigner presigner,
            String bucket, String prefix) {
        this.storageKey = storageKey;
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.prefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
    }

    @Override
    public Optional<String> presignedUrl(String uri, Duration ttl) {
        try {
            GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket).key(key(LogicalUri.parse(uri).path())).build();
            GetObjectPresignRequest pr = GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(req).build();
            return Optional.of(presigner.presignGetObject(pr).url().toString());
        } catch (Exception e) {
            throw new BizException(ErrorCode.STO_002, e);
        }
    }
```

`FileStorageRegistry`：`build` 的 `S3` 分支改为：

```java
            case S3 -> {
                S3Client client = S3Client.builder()
                    .region(Region.of(c.region() == null ? "us-east-1" : c.region()))
                    .endpointOverride(URI.create(c.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                    .build();
                S3Presigner presigner = S3Presigner.builder()
                    .region(Region.of(c.region() == null ? "us-east-1" : c.region()))
                    .endpointOverride(URI.create(c.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                    .build();
                yield new S3FileStorage(c.storageKey(), client, presigner, c.bucket(), c.prefix());
            }
```

在文件头部 import：`software.amazon.awssdk.auth.credentials.{AwsBasicCredentials,StaticCredentialsProvider}`、`software.amazon.awssdk.regions.Region`、`software.amazon.awssdk.services.s3.{S3Client,S3Presigner}`、`java.net.URI`。

- [ ] **Step 4: 全量回归**

Run: `$env:HP_MASTER_KEY='testkey'; ./mvnw.cmd -DargLine=-Xmx2g test`
Expected: 全部测试通过（含既有 M1 测试与新增 storage 测试）。若 BM1 测试因 `hermes_test` 脏库报 `already exists`，先 drop 库（见 Global Constraints 记忆）。

- [ ] **Step 5: 提交**

```bash
git add hermes-server/src/main/java/com/hermes/push/storage/s3/S3FileStorage.java hermes-server/src/main/java/com/hermes/push/storage/FileStorageRegistry.java hermes-server/src/test/java/com/hermes/push/storage/s3/S3FileStorageTest.java
git commit -m "feat(storage): S3 presignedUrl + registry 接入 S3 后端"
```

---

## 自检记录（writing-plans self-review）

- **Spec 覆盖**：ST-1 接口（Task 1）、ST-2 逻辑 URI（Task 2）、ST-3 LOCAL/S3（Task 5/7/8）、配置加密（Task 4）、多后端 registry（Task 6/8）、路径防穿越（Task 2 + Task 5 `resolve` 双保险）。FR-EXE-08、HDFS、controller/界面明确不在范围。✅
- **占位符扫描**：无 TODO/TBD；S3 SDK 版本在 Task 7 Step 1 给了具体版本 + 分辨率确认步骤（非占位）。✅
- **类型一致**：`FileStorage` 五方法签名在 Task 1 定义、Task 5/7/8 实现一致；`LogicalUri.of/parse/storageKey/path/toString` 全计划一致；`StorageConfigService` 方法名在 Task 4 定义、Task 6 消费一致；`S3FileStorage` 构造器在 Task 7（4 参）→ Task 8（5 参）演进，Task 7/8 各自的测试与实现同步更新。✅