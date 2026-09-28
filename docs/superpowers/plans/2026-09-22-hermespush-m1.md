# HermesPush M1（端到端最短闭环）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可运行的数据推送闭环：配数据源 → 写 SQL（参数/校验/预览/审计）→ Markdown 渲染 → 企微机器人推送，由 Quartz+DB 队列定时驱动，含失败重试、执行日志、单管理员登录与简单模式前端（场景模板 + 3 步向导）。

**Architecture:** Spring Boot 3 单体（Maven 多模块，M1 仅 `hermes-server`）。Quartz 集群模式只写 `hp_task_exec(PENDING)` 记录；各节点 Worker 用 `FOR UPDATE SKIP LOCKED` 领取执行，流水线为 查询→渲染→推送→汇总。重试复用原执行记录，推送幂等键四元组 `(exec_id, channel_id, artifact_key, msg_type)`。Markdown 模板走 Freemarker 沙箱。M1 模板内容内联在任务配置 JSON 中（模板库 M2 引入）。

**Tech Stack:** JDK 21、Spring Boot 3.3.4、MyBatis-Plus 3.5.7、Flyway 10、MySQL 8.0（Testcontainers 验证）、Quartz（spring-boot-starter-quartz，JDBC Store）、Druid SQL Parser 1.2.23（仅解析器）、Redisson 3.35（限流）、Sa-Token 1.39（登录）、Freemarker 2.3.33（沙箱渲染）、WireMock 3.9（企微 stub）、Lombok；前端 Vue 3 + Vite 5 + Element Plus 2.8 + axios + monaco-editor。

## Global Constraints

以下约束适用于每个任务，任务内不再重复：

- 包根 `com.hermes.push`；服务端口 8080；接口前缀 `/api`
- 统一响应 `ApiResponse{int code, String message, T data, String requestId}`：业务成功 code=0；业务失败 code=HTTP 状态 + 业务错误码字符串放 `data.errorCode`（见任务 3）
- 所有落库时间戳一律用数据库时间：SQL 中 `NOW(3)` / `CURRENT_TIMESTAMP(3)`，禁止 Java `LocalDateTime.now()` 直接落库（评审修订 H5）
- 时区 `Asia/Shanghai`：`application.yaml` 设 `spring.jackson.time-zone: Asia/Shanghai`，JVM 启动参数 `-Duser.timezone=Asia/Shanghai`
- 密文格式（M1 冻结）：`base64( keyId(1B) + nonce(12B) + ciphertext+tag )`；主密钥取环境变量 `HP_MASTER_KEY`（base64 的 32 字节），缺失时启动失败并提示
- 错误码分段（PRD FR-OPS-02）：DS-/SQL-/TPL-/RD-/PUSH-/STO-/SYS-；M1 用到的具体码在各任务中定义
- 数据表前缀 `hp_`；DDL 全部进 Flyway `V1__init.sql`（任务 1），后续变更用递增版本号，禁止改已发布脚本
- 敏感值（密码、webhook、secret）任何接口响应与日志不得出现明文；渠道 webhook 响应脱敏为 `域名/…末4位`
- 测试命令统一 `.\mvnw.cmd -pl hermes-server test "-Dtest=类名"`（Windows PowerShell；`-Dtest` 参数须加引号）；集成测试连**本地 MySQL 8**（localhost:3306，root/123456，测试库 `hermes_test` 由 URL 参数 `createDatabaseIfNotExist=true` 自动创建），**不使用 Testcontainers/Docker**（开发机为华为云电脑，存储受限，2026-09-23 用户决定）；测试连接配置放 `application-test.yaml`；隔离策略：`AbstractIntegrationTest` 每个测试方法前 `flyway.clean()+migrate()`（测试配置 `spring.flyway.clean-disabled: false`），测试库与业务库严格分离，root/123456 仅限本地开发库，生产凭据一律环境变量注入
- Redis 依赖策略（同上原因，本地无 Redis）：限流走 `RateLimiterFactory` 接口，双实现按 `hermes.rate-limiter=local|redis` 切换（默认 `local`=单机内存令牌桶，供开发/测试；**生产多节点必须配 `redis`**，写入部署清单）；test/dev profile 经 `spring.autoconfigure.exclude` 排除 Redisson 与 Redis 自动配置，应用无 Redis 也能启动
- 每个任务结束必须 commit；提交信息用 `feat|fix|test|chore(模块): 描述` 格式
- 禁止引入计划外依赖；确需引入时在任务备注写明理由并先确认中央仓库坐标存在
- 代码风格：构造器注入（Lombok `@RequiredArgsConstructor`），禁止字段注入 `@Autowired`；DTO 用 record；实体用 Lombok `@Data`

## 文件结构地图

```
HermesPush/
├─ pom.xml                                  # 父 POM（聚合、依赖版本管理）
├─ hermes-server/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/hermes/push/
│     │  ├─ HermesApplication.java
│     │  ├─ common/          # ApiResponse、BizException、ErrorCode、GlobalExceptionHandler、DbTime
│     │  ├─ security/        # AesGcmCipher、MasterKeyProvider、SaTokenConfigure、AuthController
│     │  ├─ datasource/      # 数据源 CRUD、连通性测试、HikariPoolRegistry、QueryEngine
│     │  ├─ dataset/         # DruidSqlValidator、TimeVariableResolver、ParamResolver、DatasetResult、PreviewController
│     │  ├─ audit/           # SqlAuditService、SqlAudit 实体与 Mapper
│     │  ├─ task/            # TaskConfig(JSON模型)、Task、TaskVersion、TaskService、TaskController
│     │  ├─ render/          # SandboxFreemarker、MarkdownRenderer、TemplateScanner
│     │  ├─ channel/         # PushChannel SPI、WecomBotChannel、WhitelistService、ChannelService、RateLimiterFactory
│     │  ├─ exec/            # TaskExec、ExecQueueRepository(手写XML)、ExecStatusMachine、ExecWorker、ExecPipeline、RetryScanner、ExecLogController
│     │  └─ schedule/        # QuartzConfig、ReportTriggerJob、ScheduleSyncService、CronPreviewController
│     ├─ main/resources/
│     │  ├─ application.yaml
│     │  ├─ db/migration/V1__init.sql、V2__quartz.sql
│     │  └─ mapper/ExecQueueMapper.xml     # SKIP LOCKED 领取 SQL（手写，评审修订）
│     └─ test/java/com/hermes/push/...     # 与 main 包结构镜像
└─ frontend/
   ├─ package.json、vite.config.js
   └─ src/  # api/axios.js、router、stores、views/{Login,Datasource,Tasks,Wizard3,Channels,Execs}.vue
```

模块职责边界：`dataset` 只做"SQL 文本 → 可执行语句 + 参数"，不碰连接；`datasource.QueryEngine` 只做"执行 + 保护 + DatasetResult"；`render` 不感知渠道；`channel` 不感知渲染；`exec.ExecPipeline` 是唯一编排者。任务间只通过 **Interfaces** 块声明的类与方法签名耦合。

---

### Task 1: 工程骨架 + 数据库 V1 + 健康检查

**Files:**
- Create: `pom.xml`、`hermes-server/pom.xml`、`hermes-server/src/main/java/com/hermes/push/HermesApplication.java`
- Create: `hermes-server/src/main/resources/application.yaml`、`db/migration/V1__init.sql`、`db/migration/V2__quartz.sql`
- Test: `hermes-server/src/test/java/com/hermes/push/HermesApplicationTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 可启动的 Spring 上下文；`V1__init.sql` 中全部 M1 表（后续任务的实体都映射这里的列）；Maven Wrapper `mvnw.cmd`

- [ ] **Step 1: 建父 POM 与 server 模块 POM**

父 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.hermes</groupId><artifactId>hermes-parent</artifactId><version>0.1.0</version>
  <packaging>pom</packaging><modules><module>hermes-server</module></modules>
  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
</project>
```

`hermes-server/pom.xml` 依赖清单（parent 指 spring-boot-starter-parent 3.3.4，用 `<dependencyManagement>` import 方式亦可；坐标必须逐一核对）：
`spring-boot-starter-web`、`spring-boot-starter-jdbc`、`spring-boot-starter-validation`、`spring-boot-starter-quartz`、`spring-boot-starter-freemarker`、`mybatis-plus-spring-boot3-starter:3.5.7`、`flyway-core`+`flyway-mysql`（Boot 管版本）、`mysql-connector-j`(runtime)、`druid:1.2.23`、`redisson-spring-boot-starter:3.35.0`（生产限流用，测试 profile 排除自动配置）、`sa-token-spring-boot3-starter:1.39.0`、`lombok`(provided)、测试：`spring-boot-starter-test`、`org.wiremock:wiremock-standalone:3.9.1`、`org.awaitility:awaitility`（Boot 管版本）。**不引入 Testcontainers**（环境决议，见 Global Constraints）。
构建插件：`spring-boot-maven-plugin`。

- [ ] **Step 2: 生成 Maven Wrapper 并验证空构建**

Run: `mvn -N wrapper:wrapper "-Dmaven=3.9.9"` 然后 `.\mvnw.cmd -q -DskipTests package`
Expected: BUILD SUCCESS

- [ ] **Step 3: 写 V1__init.sql（M1 全部表）**

```sql
CREATE TABLE hp_datasource (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'MYSQL|POSTGRESQL|ORACLE',
  jdbc_url VARCHAR(512) NOT NULL,
  username VARCHAR(128) NOT NULL,
  password_cipher VARCHAR(512) NOT NULL,
  ro_confirmed TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED|DISABLED',
  max_rows INT NOT NULL DEFAULT 50000,
  query_timeout_sec INT NOT NULL DEFAULT 60,
  pool_max INT NOT NULL DEFAULT 5,
  overflow_policy VARCHAR(16) NOT NULL DEFAULT 'TRUNCATE' COMMENT 'TRUNCATE|FAIL',
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ds_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_channel (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'WEWORK_BOT',
  config_cipher VARCHAR(1024) NOT NULL COMMENT 'JSON:{"webhook":...} AES-GCM',
  rate_limit_per_min INT NOT NULL DEFAULT 20,
  queue_wait_timeout_sec INT NOT NULL DEFAULT 300,
  test_flag TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ch_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_whitelist (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  type VARCHAR(32) NOT NULL COMMENT 'WEBHOOK_HOST|EMAIL_DOMAIN|EMAIL_ADDRESS',
  value VARCHAR(255) NOT NULL,
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_wl (type, value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO hp_whitelist(type,value,created_by) VALUES
 ('WEBHOOK_HOST','qyapi.weixin.qq.com','system'),
 ('WEBHOOK_HOST','oapi.dingtalk.com','system'),
 ('WEBHOOK_HOST','open.feishu.cn','system');

CREATE TABLE hp_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  task_key VARCHAR(64) NOT NULL,
  task_type VARCHAR(16) NOT NULL DEFAULT 'REPORT',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|ONLINE|OFFLINE|PAUSED|DELETED',
  cron_expr VARCHAR(64),
  jitter_enabled TINYINT NOT NULL DEFAULT 0,
  current_version_id BIGINT, pinned_version_id BIGINT,
  owner VARCHAR(64) NOT NULL,
  lock_version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_key (task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  config_json JSON NOT NULL,
  remark VARCHAR(255),
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tv (task_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_exec (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  task_version_id BIGINT NOT NULL,
  trigger_type VARCHAR(16) NOT NULL COMMENT 'CRON|MANUAL|TEST|TRIAL|API',
  priority TINYINT NOT NULL DEFAULT 40 COMMENT 'TRIAL/TEST=70 MANUAL/API=60 CRON=40',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING|RUNNING|RETRY_WAIT|SUCCESS|PARTIAL_SUCCESS|FAILED|TIMEOUT|CANCELLED',
  fire_time DATETIME(3) NOT NULL,
  biz_date DATE,
  params_json JSON,
  idempotency_key VARCHAR(128) NULL,
  idem_key_eff VARCHAR(128) AS (IFNULL(idempotency_key,'')) STORED,
  retry_count INT NOT NULL DEFAULT 0,
  max_retry INT NOT NULL DEFAULT 3,
  next_retry_at DATETIME(3) NULL,
  node_id VARCHAR(64), heartbeat_at DATETIME(3),
  rows_total INT, stage_costs_json JSON, cost_ms BIGINT,
  error_code VARCHAR(32), error_msg VARCHAR(1024),
  parent_exec_id BIGINT NULL, fanout_key VARCHAR(128) NULL,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_fire (task_id, fire_time, trigger_type, idem_key_eff),
  UNIQUE KEY uk_idem (task_id, idempotency_key),
  UNIQUE KEY uk_fanout (parent_exec_id, fanout_key),
  KEY idx_claim (status, priority, fire_time),
  KEY idx_task_time (task_id, fire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_exec_push (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  exec_id BIGINT NOT NULL, channel_id BIGINT NOT NULL,
  artifact_key VARCHAR(64) NOT NULL, msg_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SUCCESS|FAILED|SKIPPED',
  retry_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(32), error_msg VARCHAR(512),
  sent_at DATETIME(3), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_push (exec_id, channel_id, artifact_key, msg_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_exec_artifact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  exec_id BIGINT NOT NULL, artifact_key VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN',
  render_provider VARCHAR(32), rows_count INT, bytes INT,
  content MEDIUMTEXT NULL COMMENT 'M1 文本产物内联；M2 起改 storage_uri',
  storage_uri VARCHAR(512) NULL, cost_ms BIGINT,
  error_code VARCHAR(32), error_msg VARCHAR(512),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_art (exec_id, artifact_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_sql_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene VARCHAR(24) NOT NULL COMMENT 'PREVIEW|TRIAL|EXEC|VALIDATION_FAILED',
  exec_id BIGINT NULL, operator VARCHAR(64), datasource_id BIGINT NOT NULL,
  sql_text TEXT NOT NULL, params_json JSON,
  rows_returned INT, cost_ms BIGINT, client_ip VARCHAR(64),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_scene_time (scene, created_at), KEY idx_ds (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'ADMIN',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`V2__quartz.sql`：从本地 Maven 仓库 `org/quartz-scheduler/quartz/2.3.2/quartz-2.3.2.jar` 解压 `org/quartz/impl/jdbcjobstore/tables_mysql_innodb.sql`，文件头加注释 `-- source: quartz 2.3.2 tables_mysql_innodb.sql`，表名前缀保持 `QRTZ_`（application.yaml 中 `spring.quartz.jdbc.table-prefix: QRTZ_` 对应，若脚本内为无前缀 `qrtz_` 则批量替换为 `QRTZ_`）。

- [ ] **Step 4: application.yaml + 启动类**

```yaml
spring:
  application.name: hermes-server
  datasource:
    url: jdbc:mysql://${HP_DB_HOST:localhost}:${HP_DB_PORT:3306}/${HP_DB_NAME:hermes}?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ${HP_DB_USER:root}
    password: ${HP_DB_PASSWORD:root}
  flyway: { enabled: true, locations: classpath:db/migration }
  quartz:
    job-store-type: jdbc
    jdbc: { initialize-schema: never, table-prefix: QRTZ_ }
    properties:
      org.quartz.scheduler.instanceId: AUTO
      org.quartz.jobStore.isClustered: true
      org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
  data.redis: { host: ${HP_REDIS_HOST:localhost}, port: ${HP_REDIS_PORT:6379} }
  jackson: { time-zone: Asia/Shanghai }
mybatis-plus:
  mapper-locations: classpath*:mapper/*.xml
  configuration.map-underscore-to-camel-case: true
hermes:
  master-key: ${HP_MASTER_KEY:}
  node-id: ${HOSTNAME:node-dev}
  worker: { concurrency: 4, poll-interval-ms: 2000 }
  rate-limiter: ${HP_RATE_LIMITER:local}   # local=单机内存(开发默认) | redis=生产多节点
server.port: 8080
```

`src/test/resources/application-test.yaml`（集成测试统一配置）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/hermes_test?createDatabaseIfNotExist=true&useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: ${HP_TEST_DB_PASSWORD:123456}
  flyway: { enabled: true, clean-disabled: false, locations: classpath:db/migration }
  autoconfigure.exclude:
    - org.redisson.spring.starter.RedissonAutoConfigurationV2
    - org.redisson.spring.starter.RedissonAutoConfiguration
    - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
  quartz: { auto-startup: false }
hermes:
  master-key: MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
  rate-limiter: local
  worker.concurrency: 0
  auth.enabled: false
  maintenance.enabled: false
```

（`autoconfigure.exclude` 的 Redisson 类名以实际引入版本的 spring.factories/AutoConfiguration.imports 为准，实现时核对；Quartz auto-startup 默认关，Task 13 测试用 `@TestPropertySource` 单独打开。）

```java
package com.hermes.push;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class HermesApplication {
  public static void main(String[] args) {
    System.setProperty("user.timezone", "Asia/Shanghai");
    SpringApplication.run(HermesApplication.class, args);
  }
}
```

- [ ] **Step 5: 写集成测试（上下文启动 + Flyway 迁移成功，连本地 MySQL）**

```java
package com.hermes.push;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HermesApplicationTest {
  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayMigratesAllM1Tables() {
    for (String t : new String[]{"hp_datasource","hp_channel","hp_whitelist","hp_task",
        "hp_task_version","hp_task_exec","hp_task_exec_push","hp_task_exec_artifact",
        "hp_sql_audit","hp_user","QRTZ_JOB_DETAILS"}) {
      Integer n = jdbc.queryForObject(
          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='hermes_test' AND table_name=?",
          Integer.class, t);
      assertThat(n).as("table %s exists", t).isEqualTo(1);
    }
    // 队列领取 SQL 的生成列与唯一索引可用（先清表保证可重复执行）
    jdbc.update("DELETE FROM hp_task_exec");
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES (1,1,'CRON',40,'PENDING',NOW(3))");
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES (1,1,'CRON',40,'PENDING',NOW(3)+INTERVAL 1 DAY)");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec", Integer.class)).isEqualTo(2);
    // 同 (task,fire_time,trigger_type,idem='') 重复插入必须被唯一索引拒绝
    assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
        jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES (1,1,'CRON',40,'PENDING',NOW(3))"))).isNotNull();
  }
}
```

注意：重复插入断言里第二条 INSERT 的 fire_time 用 `NOW(3)` 与第一条同秒时才会撞唯一键，若因毫秒不同未撞键导致断言失败，改为显式固定时间：两条都用 `'2026-01-01 09:00:00.000'`。前置条件：本地 MySQL 8 已运行且 root/123456 可登录（`mysql -uroot -p123456 -e "SELECT VERSION()"` 验证）。

- [ ] **Step 6: 跑测试**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=HermesApplicationTest"`
Expected: PASS（需要本地 MySQL 8 运行中；`hermes_test` 库由 URL 参数自动创建）

- [ ] **Step 7: Commit**

```powershell
git add pom.xml hermes-server .gitignore
git commit -m "feat(server): 工程骨架 + Flyway V1 全部 M1 表 + Quartz 集群表"
```

（`.gitignore` 补 `target/`、`node_modules/`、`dist/`、`.env`）

---

### Task 2: 凭据加密 AesGcmCipher（FR-SEC-01）

**Files:**
- Create: `hermes-server/src/main/java/com/hermes/push/security/MasterKeyProvider.java`、`AesGcmCipher.java`
- Test: `hermes-server/src/test/java/com/hermes/push/security/AesGcmCipherTest.java`

**Interfaces:**
- Consumes: `hermes.master-key` 配置（Task 1）
- Produces: `AesGcmCipher.encrypt(String plain) -> String`（base64 密文）、`AesGcmCipher.decrypt(String cipher) -> String`、失败抛 `BizException(ErrorCode.SYS_001_CRYPTO)`；`MasterKeyProvider.requireKey() -> SecretKeySpec`。Task 4/15/16 依赖这两个类。

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.security;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class AesGcmCipherTest {
  // base64 of 32 bytes 0x00..0x1f
  static final String KEY_B64 = Base64.getEncoder().encodeToString(
      java.util.stream.IntStream.range(0,32).mapToObj(i->(byte)i).toArray(byte[]::new));
  AesGcmCipher cipher() { return new AesGcmCipher(new MasterKeyProvider(KEY_B64)); }

  @Test void roundTrip() {
    String c = cipher().encrypt("p@ssw0rd-中文");
    assertThat(cipher().decrypt(c)).isEqualTo("p@ssw0rd-中文");
  }
  @Test void nonceRandomized_eachCiphertextDiffers() {
    assertThat(cipher().encrypt("same")).isNotEqualTo(cipher().encrypt("same"));
  }
  @Test void ciphertextLayout_keyIdNonceBody() {
    byte[] raw = Base64.getDecoder().decode(cipher().encrypt("x"));
    assertThat(raw[0]).isEqualTo((byte) 1);          // keyId=1（M1 固定）
    assertThat(raw.length).isGreaterThan(1 + 12 + 16); // keyId+nonce+tag 至少存在
  }
  @Test void wrongKey_throwsSys001() {
    String c = cipher().encrypt("secret");
    String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
    AesGcmCipher other = new AesGcmCipher(new MasterKeyProvider(otherKey));
    assertThatThrownBy(() -> other.decrypt(c)).isInstanceOf(BizException.class)
        .hasMessageContaining("SYS-001");
  }
  @Test void missingMasterKey_failsFast() {
    assertThatThrownBy(() -> new MasterKeyProvider(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HP_MASTER_KEY");
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=AesGcmCipherTest"`
Expected: COMPILATION ERROR（AesGcmCipher 不存在）

- [ ] **Step 3: 实现**

```java
package com.hermes.push.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Component
public class MasterKeyProvider {
  private final SecretKeySpec key;
  public MasterKeyProvider(@Value("${hermes.master-key:}") String b64) {
    if (b64 == null || b64.isBlank()) {
      throw new IllegalStateException("缺少主密钥：请设置环境变量 HP_MASTER_KEY（base64 的 32 字节），参见部署手册");
    }
    byte[] raw;
    try { raw = Base64.getDecoder().decode(b64); }
    catch (IllegalArgumentException e) { throw new IllegalStateException("HP_MASTER_KEY 不是合法 base64", e); }
    if (raw.length != 32) throw new IllegalStateException("HP_MASTER_KEY 必须是 base64 的 32 字节");
    this.key = new SecretKeySpec(raw, "AES");
  }
  public SecretKeySpec requireKey() { return key; }
}
```

```java
package com.hermes.push.security;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/** 密文格式: base64( keyId(1B)=1 + nonce(12B) + ciphertext+tag )。格式 M1 冻结，keyId 为轮换预留。 */
@Component
public class AesGcmCipher {
  private static final byte KEY_ID = 1;
  private static final int NONCE_LEN = 12, TAG_BITS = 128;
  private final MasterKeyProvider keys;
  private final SecureRandom random = new SecureRandom();

  public AesGcmCipher(MasterKeyProvider keys) { this.keys = keys; }

  public String encrypt(String plain) {
    try {
      byte[] nonce = new byte[NONCE_LEN];
      random.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, keys.requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
      byte[] body = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      ByteBuffer buf = ByteBuffer.allocate(1 + NONCE_LEN + body.length);
      buf.put(KEY_ID).put(nonce).put(body);
      return Base64.getEncoder().encodeToString(buf.array());
    } catch (Exception e) { throw new BizException(ErrorCode.SYS_001_CRYPTO, e); }
  }

  public String decrypt(String cipherText) {
    try {
      byte[] raw = Base64.getDecoder().decode(cipherText);
      if (raw.length < 1 + NONCE_LEN + 16 || raw[0] != KEY_ID) throw new IllegalArgumentException("bad format");
      ByteBuffer buf = ByteBuffer.wrap(raw);
      buf.get(); // keyId
      byte[] nonce = new byte[NONCE_LEN]; buf.get(nonce);
      byte[] body = new byte[buf.remaining()]; buf.get(body);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, keys.requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
      return new String(c.doFinal(body), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) { throw new BizException(ErrorCode.SYS_001_CRYPTO, e); }
  }
}
```

注意：`BizException`/`ErrorCode` 在 Task 3 定义。为让本任务测试先编译，可先建最小 `ErrorCode.SYS_001_CRYPTO("SYS-001","加解密失败")` 与 `BizException(ErrorCode, Throwable)`（Task 3 会补全枚举，不冲突）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=AesGcmCipherTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(security): AES-GCM 凭据加密，密文格式 keyId+nonce+body（M1 冻结）"
```

---

### Task 3: 错误码体系 + 统一响应 + 全局异常处理（FR-OPS-02 基础）

**Files:**
- Create: `common/ErrorCode.java`、`common/BizException.java`、`common/ApiResponse.java`、`common/GlobalExceptionHandler.java`
- Test: `common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `ErrorCode` 枚举（`String code, String userMessage, String suggestion` 三字段 + `of(String code)` 查找）；`BizException(ErrorCode, String detail)` / `BizException(ErrorCode, Throwable)`，`getCode()`；`ApiResponse.ok(T)` / `ApiResponse.fail(ErrorCode, String detail)`；全局异常处理器把 `BizException` 映射为 HTTP 200 + `{code!=0, message, data:{errorCode, suggestion, detail}}`。**后续所有任务抛业务错误只用 `BizException`，Controller 不 try-catch。**

M1 错误码全集（userMessage 与 suggestion 为界面展示文案，直接可用）：

| code | userMessage | suggestion |
|---|---|---|
| DS-001 | 数据库不存在或名称错误 | 检查 JDBC URL 中的库名 |
| DS-002 | 数据源连接失败或超时 | 检查网络、账号状态，确认只读账号未过期 |
| DS-003 | 数据库认证失败 | 检查用户名密码 |
| SQL-001 | SQL 语法错误 | 查看报错定位的关键字 |
| SQL-002 | 仅允许单条 SELECT 查询 | 移除多余语句或 DDL/DML |
| SQL-003 | SQL 校验失败 | 见详情 |
| SQL-004 | 必填参数缺失 | 在触发弹窗补填或为参数设默认值 |
| SQL-005 | 文本替换参数未通过白名单校验 | 检查参数值是否匹配配置的白名单/正则 |
| TPL-003 | 模板引用了不存在的字段 | 核对数据集字段名与模板变量 |
| TPL-010 | 模板包含被禁用的危险指令 | 移除 ?new/?api 等指令 |
| RD-002 | 内容超出渠道长度限制且策略为失败 | 调大截断阈值或改用文件发送 |
| PUSH-011 | 渠道限流等待超时 | 错峰调度或调大排队等待超时 |
| PUSH-012 | webhook 无效或已失效 | 在渠道管理执行健康检查 |
| SYS-001 | 加解密失败 | 检查 HP_MASTER_KEY 是否变更 |
| SYS-002 | 任务状态不允许该操作 | 刷新后重试 |
| SYS-003 | 资源不存在 | 检查 ID |
| SYS-004 | 保存冲突，数据已被他人修改 | 刷新后重试 |
| SYS-005 | 渲染超时 | 简化模板或调大超时 |

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
  GlobalExceptionHandler h = new GlobalExceptionHandler();

  @Test void bizExceptionMapsToCodeAndSuggestion() {
    ResponseEntity<ApiResponse<Void>> r = h.handleBiz(new BizException(ErrorCode.DS_002, "connect timed out"));
    ApiResponse<Void> body = r.getBody();
    assertThat(body.code()).isNotZero();
    assertThat(body.data().get("errorCode")).isEqualTo("DS-002");
    assertThat((String) body.data().get("suggestion")).contains("只读账号");
    assertThat((String) body.data().get("detail")).contains("connect timed out");
  }
  @Test void okResponseHasZeroCode() {
    assertThat(ApiResponse.ok("x").code()).isZero();
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=GlobalExceptionHandlerTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现四个类**

```java
package com.hermes.push.common;

import lombok.Getter;
import java.util.Arrays;

@Getter
public enum ErrorCode {
  DS_001("DS-001","数据库不存在或名称错误","检查 JDBC URL 中的库名"),
  DS_002("DS-002","数据源连接失败或超时","检查网络、账号状态，确认只读账号未过期"),
  DS_003("DS-003","数据库认证失败","检查用户名密码"),
  SQL_001("SQL-001","SQL 语法错误","查看报错定位的关键字"),
  SQL_002("SQL-002","仅允许单条 SELECT 查询","移除多余语句或 DDL/DML"),
  SQL_003("SQL-003","SQL 校验失败","见详情"),
  SQL_004("SQL-004","必填参数缺失","在触发弹窗补填或为参数设默认值"),
  SQL_005("SQL-005","文本替换参数未通过白名单校验","检查参数值是否匹配配置的白名单/正则"),
  TPL_003("TPL-003","模板引用了不存在的字段","核对数据集字段名与模板变量"),
  TPL_010("TPL-010","模板包含被禁用的危险指令","移除 ?new/?api 等指令"),
  RD_002("RD-002","内容超出渠道长度限制且策略为失败","调大截断阈值或改用文件发送"),
  PUSH_011("PUSH-011","渠道限流等待超时","错峰调度或调大排队等待超时"),
  PUSH_012("PUSH-012","webhook 无效或已失效","在渠道管理执行健康检查"),
  SYS_001("SYS-001","加解密失败","检查 HP_MASTER_KEY 是否变更"),
  SYS_002("SYS-002","任务状态不允许该操作","刷新后重试"),
  SYS_003("SYS-003","资源不存在","检查 ID"),
  SYS_004("SYS-004","保存冲突，数据已被他人修改","刷新后重试"),
  SYS_005("SYS-005","渲染超时","简化模板或调大超时");

  private final String code, userMessage, suggestion;
  ErrorCode(String code, String userMessage, String suggestion) {
    this.code = code; this.userMessage = userMessage; this.suggestion = suggestion;
  }
  public static ErrorCode of(String code) {
    return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown error code " + code));
  }
}
```

```java
package com.hermes.push.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
  private final ErrorCode errorCode;
  private final String detail;
  public BizException(ErrorCode ec, String detail) { super(ec.getUserMessage() + " | " + detail); this.errorCode = ec; this.detail = detail; }
  public BizException(ErrorCode ec, Throwable cause) { super(ec.getUserMessage(), cause); this.errorCode = ec; this.detail = cause.getMessage(); }
  public BizException(ErrorCode ec) { this(ec, ""); }
}
```

```java
package com.hermes.push.common;

import java.util.Map;
import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String requestId) {
  public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "ok", data, UUID.randomUUID().toString().substring(0,8)); }
  @SuppressWarnings("unchecked")
  public static ApiResponse<Map<String,Object>> fail(ErrorCode ec, String detail) {
    return new ApiResponse<>(500, ec.getUserMessage(),
        Map.of("errorCode", ec.getCode(), "suggestion", ec.getSuggestion(), "detail", detail == null ? "" : detail),
        UUID.randomUUID().toString().substring(0,8));
  }
}
```

```java
package com.hermes.push.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleBiz(BizException e) {
    log.warn("biz error {}: {}", e.getErrorCode().getCode(), e.getDetail());
    return ResponseEntity.ok((ApiResponse) ApiResponse.fail(e.getErrorCode(), e.getDetail()));
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleOther(Exception e) {
    log.error("unhandled", e);
    return ResponseEntity.internalServerError().body((ApiResponse) ApiResponse.fail(ErrorCode.SYS_003, String.valueOf(e.getMessage())));
  }
}
```

同时把 Task 2 中临时建的最小 ErrorCode/BizException 删除，统一为本任务版本，重跑 `AesGcmCipherTest` 确认仍 PASS（SYS_001 消息含 "SYS-001" 由 `BizException.getMessage()` 拼接保证）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=GlobalExceptionHandlerTest+AesGcmCipherTest"`
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(common): 错误码字典(18码)+统一响应+全局异常处理"
```

---

### Task 4: 数据源 CRUD + 加密存储 + 只读确认（FR-DS-01/03）

**Files:**
- Create: `datasource/Datasource.java`（实体）、`DatasourceMapper.java`、`DatasourceService.java`、`DatasourceController.java`、`DatasourceVO.java`、`DatasourceSaveRequest.java`
- Create: `test/.../AbstractIntegrationTest.java`（Testcontainers 基类，后续所有集成测试复用）
- Test: `datasource/DatasourceServiceTest.java`

**Interfaces:**
- Consumes: `AesGcmCipher`（Task 2）、`BizException/ErrorCode`（Task 3）
- Produces:
  - `Datasource` 实体：字段与 `hp_datasource` 列一一对应（驼峰），MyBatis-Plus `@TableName("hp_datasource")`
  - `DatasourceService.save(DatasourceSaveRequest, String operator) -> Long`；`update(Long, DatasourceSaveRequest, String operator)`；`getEnabled(Long) -> Datasource`（DISABLED 或不存在抛 `SYS_003`）；`list() -> List<DatasourceVO>`；`setStatus(Long, boolean enable) -> int`（返回受影响上线任务数校验前的引用数，引用>0 时抛 `SYS_002`，detail 列出数量）
  - `DatasourceVO`：record，**无密码字段**，含 `boolean hasPassword`；webhook 类字段无
  - `DatasourceSaveRequest`：record `(String name, String type, String jdbcUrl, String username, String password, boolean roConfirmed, Integer maxRows, Integer queryTimeoutSec, Integer poolMax, String overflowPolicy)`；password 为空串表示"不修改"（update 时）
- REST：`POST /api/datasources`、`PUT /api/datasources/{id}`、`GET /api/datasources`、`GET /api/datasources/{id}`、`POST /api/datasources/{id}/status?enable=`

- [ ] **Step 1: Testcontainers 集成测试基类**

```java
package com.hermes.push;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
  /** 本地 MySQL 测试库（hermes_test 由 URL 参数自动建库）；业务表每用例 clean+migrate 重建 */
  public static final String TEST_DB_URL = "jdbc:mysql://localhost:3306/hermes_test?createDatabaseIfNotExist=true&useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
  public static final String TEST_DB_USER = "root";
  public static final String TEST_DB_PASSWORD = System.getenv().getOrDefault("HP_TEST_DB_PASSWORD", "123456");

  @Autowired Flyway flyway;

  @BeforeEach void resetSchema() { flyway.clean(); flyway.migrate(); } // 用例级隔离（无容器环境的替代方案）
}
```

（测试 profile 的连接、Redisson/Redis 自动配置排除、quartz/worker/auth/maintenance 开关全部在 Task 1 的 `application-test.yaml` 中，基类不再注入属性。`@BeforeEach resetSchema` 会重建全部表——重量级种子数据（如 Task 8 的 6 万行）必须放到独立的 `hermes_seed` 库，见 Task 8。）

- [ ] **Step 2: 写失败测试**

```java
package com.hermes.push.datasource;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class DatasourceServiceTest extends AbstractIntegrationTest {
  @Autowired DatasourceService svc;
  @Autowired JdbcTemplate jdbc;

  DatasourceSaveRequest req(String name, String pwd) {
    return new DatasourceSaveRequest(name, "MYSQL",
        "jdbc:mysql://10.0.0.1:3306/trade", "ro_user", pwd, true,
        null, null, null, null);
  }

  @Test void saveStoresCipherNotPlain_andVoNeverEchoes() {
    Long id = svc.save(req("ds-a", "S3cret!"), "admin");
    String cipher = jdbc.queryForObject("SELECT password_cipher FROM hp_datasource WHERE id=?", String.class, id);
    assertThat(cipher).doesNotContain("S3cret");
    assertThat(svc.list()).allSatisfy(vo -> assertThat(vo.toString()).doesNotContain("S3cret"));
    assertThat(svc.list().get(0).hasPassword()).isTrue();
  }
  @Test void updateWithBlankPasswordKeepsOld() {
    Long id = svc.save(req("ds-b", "OldPwd"), "admin");
    String before = jdbc.queryForObject("SELECT password_cipher FROM hp_datasource WHERE id=?", String.class, id);
    svc.update(id, req("ds-b", ""), "admin");
    String after = jdbc.queryForObject("SELECT password_cipher FROM hp_datasource WHERE id=?", String.class, id);
    assertThat(after).isEqualTo(before);
  }
  @Test void saveWithoutRoConfirmRejected() {
    var r = new DatasourceSaveRequest("ds-c","MYSQL","jdbc:mysql://x/y","u","p",false,null,null,null,null);
    assertThatThrownBy(() -> svc.save(r, "admin")).isInstanceOf(BizException.class).hasMessageContaining("SYS-002");
  }
  @Test void disableWithOnlineTaskReferenceRejected() {
    Long id = svc.save(req("ds-d", "pwd"), "admin");
    // 造一个引用该数据源的上线任务（config_json 直插，绕过 TaskService——该任务表已存在）
    jdbc.update("INSERT INTO hp_task(name,task_key,task_type,status,owner,current_version_id) VALUES('t','t1','REPORT','ONLINE','admin',1)");
    jdbc.update("INSERT INTO hp_task_version(id,task_id,version_no,config_json) VALUES(1,1,1,JSON_OBJECT('datasets',JSON_ARRAY(JSON_OBJECT('datasourceId',?))))", id);
    assertThatThrownBy(() -> svc.setStatus(id, false)).isInstanceOf(BizException.class).hasMessageContaining("1 个上线任务");
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=DatasourceServiceTest"`
Expected: COMPILATION ERROR

- [ ] **Step 4: 实现**

实体（字段对齐 DDL，`@TableName("hp_datasource")`，`@TableId(type = IdType.AUTO)`，Lombok `@Data`；`created_at/updated_at` 用 `@TableField(fill = FieldFill.INSERT/INSERT_UPDATE)` 由 MetaObjectHandler 填 **数据库时间**：handler 内执行 `SELECT NOW(3)` 缓存秒级复用，或 DDL DEFAULT 已兜底则实体 insert 时不传该列——采用后者：实体字段加 `@TableField(insertStrategy = FieldStrategy.NOT_EMPTY)` 且 service 不 set 时间字段）。

Service 关键逻辑（完整方法体）：

```java
@Service @RequiredArgsConstructor
public class DatasourceService {
  private final DatasourceMapper mapper;
  private final AesGcmCipher cipher;
  private final JdbcTemplate jdbc;

  public Long save(DatasourceSaveRequest r, String operator) {
    validateRequest(r, true);
    Datasource d = new Datasource();
    applyRequest(d, r);
    d.setPasswordCipher(cipher.encrypt(r.password()));
    d.setRoConfirmed(r.roConfirmed() ? 1 : 0);
    d.setStatus("ENABLED");
    d.setCreatedBy(operator);
    mapper.insert(d);
    return d.getId();
  }

  public void update(Long id, DatasourceSaveRequest r, String operator) {
    Datasource d = require(id);
    validateRequest(r, false);
    applyRequest(d, r);
    if (r.password() != null && !r.password().isBlank()) d.setPasswordCipher(cipher.encrypt(r.password()));
    d.setRoConfirmed(r.roConfirmed() ? 1 : 0);
    mapper.updateById(d);
    // 池缓存失效（Task 8 的 HikariPoolRegistry.evict(id)，此处经 ObjectProvider 可选注入避免循环）
  }

  public void setStatus(Long id, boolean enable) {
    require(id);
    if (!enable) {
      Integer refs = jdbc.queryForObject(
        "SELECT COUNT(*) FROM hp_task t JOIN hp_task_version v ON v.id = t.current_version_id " +
        "WHERE t.status='ONLINE' AND JSON_CONTAINS(v.config_json, CAST(? AS JSON), '$.datasets[*].datasourceId')",
        Integer.class, String.valueOf(id));
      if (refs != null && refs > 0)
        throw new BizException(ErrorCode.SYS_002, "存在 " + refs + " 个上线任务引用该数据源，请先下线相关任务");
    }
    Datasource d = require(id);
    d.setStatus(enable ? "ENABLED" : "DISABLED");
    mapper.updateById(d);
  }

  public Datasource getEnabled(Long id) {
    Datasource d = require(id);
    if (!"ENABLED".equals(d.getStatus())) throw new BizException(ErrorCode.SYS_002, "数据源已停用");
    return d;
  }

  public List<DatasourceVO> list() {
    return mapper.selectList(null).stream().map(DatasourceVO::from).toList();
  }

  private Datasource require(Long id) {
    Datasource d = mapper.selectById(id);
    if (d == null) throw new BizException(ErrorCode.SYS_003, "数据源 " + id);
    return d;
  }
  private void validateRequest(DatasourceSaveRequest r, boolean isCreate) {
    if (!r.roConfirmed()) throw new BizException(ErrorCode.SYS_002, "必须勾选只读账号确认（FR-DS-03）");
    if (isCreate && (r.password() == null || r.password().isBlank()))
      throw new BizException(ErrorCode.SYS_002, "新建数据源必须提供密码");
    if (!Set.of("MYSQL","POSTGRESQL","ORACLE").contains(r.type()))
      throw new BizException(ErrorCode.SYS_002, "不支持的数据源类型 " + r.type());
  }
  private void applyRequest(Datasource d, DatasourceSaveRequest r) {
    d.setName(r.name()); d.setType(r.type()); d.setJdbcUrl(r.jdbcUrl()); d.setUsername(r.username());
    d.setMaxRows(r.maxRows() == null ? 50000 : r.maxRows());
    d.setQueryTimeoutSec(r.queryTimeoutSec() == null ? 60 : r.queryTimeoutSec());
    d.setPoolMax(r.poolMax() == null ? 5 : r.poolMax());
    d.setOverflowPolicy(r.overflowPolicy() == null ? "TRUNCATE" : r.overflowPolicy());
  }
}
```

`DatasourceVO.from(Datasource)`：拷贝除 `passwordCipher` 外全部字段 + `hasPassword=true`。Controller 为薄封装：入参 `@Valid`，operator 暂取请求头 `X-User`（Task 20 登录后改 Sa-Token 会话，预留 `CurrentUserHolder.get()` 静态门面，M1 默认 "admin"）。

- [ ] **Step 5: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=DatasourceServiceTest"`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(datasource): CRUD+AES加密存储+只读确认+停用引用校验(FR-DS-01/03)"
```

---

### Task 5: 连通性测试（FR-DS-02）

**Files:**
- Create: `datasource/ConnectionTester.java`、`datasource/TestResultVO.java`；Modify: `DatasourceController.java`（加 `POST /api/datasources/{id}/test` 与 `POST /api/datasources/test-inline`）
- Test: `datasource/ConnectionTesterTest.java`

**Interfaces:**
- Consumes: `DatasourceService.getEnabled(Long)`、`AesGcmCipher.decrypt`
- Produces: `ConnectionTester.test(Datasource d) -> TestResultVO(boolean ok, String dbVersion, long costMs, String errorCode, String userMessage)`；**不抛异常**，失败分类进 errorCode：连不上/超时→`DS-002`，认证失败→`DS-003`，库不存在→`DS-001`。判定规则：异常消息含 `Access denied`→DS-003；含 `Unknown database`→DS-001；其余→DS-002。`test-inline` 用于新建前测试（密码明文在请求体，不落库不打日志）。

- [ ] **Step 1: 写失败测试（集成，用 Testcontainers 的 MySQL 当被测目标）**

```java
package com.hermes.push.datasource;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTesterTest extends AbstractIntegrationTest {
  @Autowired ConnectionTester tester;
  @Autowired DatasourceService svc;

  Datasource build(String url, String user, String pwd) {
    Long id = svc.save(new DatasourceSaveRequest("ct-" + System.nanoTime(), "MYSQL", url, user, pwd, true, null, 10, null, null), "admin");
    return svc.getEnabled(id);
  }

  @Test void okPath() {
    var r = tester.test(build(TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD));
    assertThat(r.ok()).isTrue();
    assertThat(r.dbVersion()).contains("8.0");
  }
  @Test void wrongPassword_ds003() {
    var r = tester.test(build(TEST_DB_URL, TEST_DB_USER, "wrong-password"));
    assertThat(r.ok()).isFalse(); assertThat(r.errorCode()).isEqualTo("DS-003");
  }
  @Test void unknownDb_ds001() {
    var r = tester.test(build(TEST_DB_URL.replace("hermes_test", "no_such_db_xyz"), TEST_DB_USER, TEST_DB_PASSWORD));
    assertThat(r.ok()).isFalse(); assertThat(r.errorCode()).isEqualTo("DS-001");
  }
  @Test void unreachable_ds002() {
    var r = tester.test(build("jdbc:mysql://127.0.0.1:1/x?connectTimeout=3000", "root", "root"));
    assertThat(r.ok()).isFalse(); assertThat(r.errorCode()).isEqualTo("DS-002");
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ConnectionTesterTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

```java
package com.hermes.push.datasource;

import com.hermes.push.common.ErrorCode;
import com.hermes.push.security.AesGcmCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

@Component @RequiredArgsConstructor
public class ConnectionTester {
  private final AesGcmCipher cipher;

  public TestResultVO test(Datasource d) { return doTest(d.getJdbcUrl(), d.getUsername(), cipher.decrypt(d.getPasswordCipher())); }
  public TestResultVO testInline(String url, String user, String pwd) { return doTest(url, user, pwd); }

  private TestResultVO doTest(String url, String user, String pwd) {
    long t0 = System.nanoTime();
    DriverManager.setLoginTimeout(10);
    Properties p = new Properties(); p.setProperty("user", user); p.setProperty("password", pwd);
    p.setProperty("connectTimeout", "10000"); p.setProperty("socketTimeout", "10000");
    try (Connection c = DriverManager.getConnection(url, p)) {
      String ver = c.getMetaData().getDatabaseProductVersion();
      c.createStatement().execute("SELECT 1");
      return new TestResultVO(true, ver, (System.nanoTime() - t0) / 1_000_000, null, null);
    } catch (Exception e) {
      String msg = String.valueOf(e.getMessage());
      ErrorCode ec = msg.contains("Access denied") ? ErrorCode.DS_003
                   : msg.contains("Unknown database") ? ErrorCode.DS_001
                   : ErrorCode.DS_002;
      return new TestResultVO(false, null, (System.nanoTime() - t0) / 1_000_000, ec.getCode(), ec.getUserMessage() + " | " + msg);
    }
  }
}
// record TestResultVO(boolean ok, String dbVersion, long costMs, String errorCode, String userMessage) {}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ConnectionTesterTest"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(datasource): 连通性测试与错误分类 DS-001/002/003(FR-DS-02)"
```

---

### Task 6: Druid SQL 安全校验器（FR-SQL-01）

**Files:**
- Create: `dataset/DruidSqlValidator.java`
- Test: `dataset/DruidSqlValidatorTest.java`

**Interfaces:**
- Consumes: `ErrorCode`（Task 3）
- Produces: `DruidSqlValidator.validate(String sql, String dsType) -> String`（返回原 SQL；失败抛 `BizException`：解析失败→`SQL_001`，非单条 SELECT→`SQL_002`，INTO OUTFILE/DUMPFILE→`SQL_002`）。dsType 映射 Druid DbType：MYSQL→mysql、POSTGRESQL→postgresql、ORACLE→oracle。**纯函数，不做审计**（审计由调用方在捕获异常后写 VALIDATION_FAILED，见 Task 9/10）。

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DruidSqlValidatorTest {
  DruidSqlValidator v = new DruidSqlValidator();

  @Test void plainSelectPasses() {
    assertThat(v.validate("SELECT a, b FROM t WHERE dt = ?", "MYSQL")).contains("SELECT");
  }
  @Test void withCtePasses() {
    assertThat(v.validate("WITH x AS (SELECT 1 AS n) SELECT n FROM x", "MYSQL")).isNotBlank();
  }
  @ParameterizedTest
  @ValueSource(strings = {
      "UPDATE t SET a=1",
      "DELETE FROM t",
      "INSERT INTO t VALUES(1)",
      "DROP TABLE t",
      "TRUNCATE TABLE t",
      "CREATE TABLE x(a int)",
      "SELECT 1; DROP TABLE t",
      "SELECT 1; SELECT 2",
      "SELECT * FROM t INTO OUTFILE '/tmp/x'",
      "SELECT * FROM t INTO DUMPFILE '/tmp/x'"
  })
  void rejected(String sql) {
    assertThatThrownBy(() -> v.validate(sql, "MYSQL"))
        .isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).startsWith("SQL-"));
  }
  @Test void commentBypassRejected() {
    assertThatThrownBy(() -> v.validate("SELECT 1 /* ; */; DROP TABLE t -- x", "MYSQL"))
        .isInstanceOf(BizException.class);
  }
  @Test void garbageSyntax_sql001() {
    assertThatThrownBy(() -> v.validate("SELCT ((( FROM", "MYSQL"))
        .isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo("SQL-001"));
  }
  @Test void postgresFlavorOk() {
    assertThat(v.validate("SELECT 1", "POSTGRESQL")).isNotBlank();
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=DruidSqlValidatorTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

```java
package com.hermes.push.dataset;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class DruidSqlValidator {
  private static final Pattern INTO_FILE = Pattern.compile("(?i)INTO\\s+(OUTFILE|DUMPFILE)");

  public String validate(String sql, String dsType) {
    if (sql == null || sql.isBlank()) throw new BizException(ErrorCode.SQL_003, "SQL 为空");
    if (INTO_FILE.matcher(sql).find()) throw new BizException(ErrorCode.SQL_002, "禁止 INTO OUTFILE/DUMPFILE");
    DbType db = switch (dsType) {
      case "MYSQL" -> DbType.mysql;
      case "POSTGRESQL" -> DbType.postgresql;
      case "ORACLE" -> DbType.oracle;
      default -> throw new BizException(ErrorCode.SQL_003, "未知数据源类型 " + dsType);
    };
    List<SQLStatement> stmts;
    try {
      stmts = SQLUtils.parseStatements(sql, db);
    } catch (Exception e) {
      throw new BizException(ErrorCode.SQL_001, firstLine(e.getMessage()));
    }
    if (stmts.size() != 1) throw new BizException(ErrorCode.SQL_002, "只允许单条语句，实际 " + stmts.size() + " 条");
    if (!(stmts.get(0) instanceof SQLSelectStatement))
      throw new BizException(ErrorCode.SQL_002, "只允许 SELECT，实际为 " + stmts.get(0).getClass().getSimpleName());
    return sql;
  }
  private String firstLine(String s) { return s == null ? "" : s.split("\n")[0]; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=DruidSqlValidatorTest"`
Expected: 全部 PASS（commentBypassRejected 若 Druid 对 `-- x` 尾注释解析为单条 SELECT+DROP 两条则命中 size!=1；实测调整断言前必须先跑）

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(dataset): Druid SQL 白名单校验，单条SELECT限定(FR-SQL-01)"
```

---

### Task 7: 时间变量与参数解析（FR-SQL-02）

**Files:**
- Create: `dataset/TimeVariableResolver.java`、`dataset/ParamResolver.java`、`dataset/PreparedSql.java`、`dataset/ParameterDefinition.java`
- Test: `dataset/ParamResolverTest.java`

**Interfaces:**
- Consumes: `ErrorCode.SQL_004 / SQL_005`
- Produces:
  - `record ParameterDefinition(String name, ParamType type, boolean required, String defaultValue, boolean allowTextSubstitution, String whitelistPattern)`，`enum ParamType{STRING,INT,DECIMAL,DATE}`
  - `record PreparedSql(String sql, List<Object> bindValues, Map<String,String> auditParams)`
  - `TimeVariableResolver.resolve(String token, LocalDate runDate, int bizOffsetDays) -> String`：token 形如 `bizDate-1d:yyyyMMdd`；支持链式偏移 `±N(d|w|M|y)`；无 pattern 时 DATE 输出 `yyyy-MM-dd`
  - `ParamResolver.prepare(String rawSql, List<ParameterDefinition> defs, Map<String,String> staticParams, Map<String,String> runtimeParams, LocalDate runDate, int bizOffsetDays) -> PreparedSql`
    规则：`#{name...}` → `?` 占位 + 按出现顺序进 bindValues（值解析优先级 runtime > static > 内置时间变量 > defaultValue；必填缺失抛 SQL_004）；`${name}` → 仅当对应 def `allowTextSubstitution=true` 且值匹配 `whitelistPattern`（空 pattern 拒绝一切）时做文本替换，否则抛 SQL_005；类型转换按 ParamType（DATE→`java.sql.Date`，INT→`Long`，DECIMAL→`BigDecimal`，其余 String）；auditParams 记录每个参数的最终字面值

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ParamResolverTest {
  static final LocalDate RUN = LocalDate.of(2026, 9, 22);
  ParamResolver resolver = new ParamResolver(new TimeVariableResolver());

  ParameterDefinition def(String name, ParamType t, boolean req, String dft, boolean textSub, String pattern) {
    return new ParameterDefinition(name, t, req, dft, textSub, pattern);
  }

  @Test void bizDateDefaultsToRunMinusOne() {
    PreparedSql p = resolver.prepare("SELECT * FROM t WHERE dt = #{bizDate}",
        List.of(), Map.of(), Map.of(), RUN, -1);
    assertThat(p.sql()).isEqualTo("SELECT * FROM t WHERE dt = ?");
    assertThat(p.bindValues()).containsExactly(java.sql.Date.valueOf(LocalDate.of(2026, 9, 21)));
    assertThat(p.auditParams()).containsEntry("bizDate", "2026-09-21");
  }
  @Test void offsetAndPattern() {
    PreparedSql p = resolver.prepare("SELECT #{bizDate-1d:yyyyMMdd}, #{bizDate:yyyy-MM-01}",
        List.of(), Map.of(), Map.of(), RUN, -1);
    assertThat(p.bindValues()).containsExactly("20260920", "2026-09-01");
  }
  @Test void runtimeOverridesStaticOverridesDefault() {
    var defs = List.of(def("deptId", ParamType.INT, true, "1", false, null));
    assertThat(resolver.prepare("SELECT #{deptId}", defs, Map.of("deptId","2"), Map.of(), RUN, -1).bindValues())
        .containsExactly(2L);
    assertThat(resolver.prepare("SELECT #{deptId}", defs, Map.of("deptId","2"), Map.of("deptId","9"), RUN, -1).bindValues())
        .containsExactly(9L);
    assertThat(resolver.prepare("SELECT #{deptId}", defs, Map.of(), Map.of(), RUN, -1).bindValues())
        .containsExactly(1L);
  }
  @Test void requiredMissing_sql004() {
    var defs = List.of(def("branchId", ParamType.INT, true, null, false, null));
    assertThatThrownBy(() -> resolver.prepare("SELECT #{branchId}", defs, Map.of(), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-004");
  }
  @Test void textSubstitutionWhitelist() {
    var defs = List.of(def("weekTag", ParamType.STRING, true, null, true, "^\\d{4}W\\d{2}$"));
    PreparedSql ok = resolver.prepare("SELECT * FROM t WHERE wk = '${weekTag}'", defs, Map.of("weekTag","2026W38"), Map.of(), RUN, -1);
    assertThat(ok.sql()).contains("'2026W38'");
    assertThatThrownBy(() -> resolver.prepare("SELECT * FROM t WHERE wk = '${weekTag}'", defs, Map.of("weekTag","x' OR 1=1--"), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-005");
  }
  @Test void textSubstitutionNotEnabledRejected() {
    var defs = List.of(def("col", ParamType.STRING, true, null, false, null));
    assertThatThrownBy(() -> resolver.prepare("SELECT ${col} FROM t", defs, Map.of("col","a"), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-005");
  }
  @Test void decimalType() {
    var defs = List.of(def("th", ParamType.DECIMAL, true, null, false, null));
    assertThat(resolver.prepare("SELECT #{th}", defs, Map.of("th","1000.5"), Map.of(), RUN, -1).bindValues())
        .containsExactly(new BigDecimal("1000.5"));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ParamResolverTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现（核心正则与流程）**

```java
package com.hermes.push.dataset;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TimeVariableResolver {
  // 例：bizDate-1d:yyyyMMdd、runDate+2w、bizDate-1M+3d:yyyy-MM-dd
  private static final Pattern TOKEN = Pattern.compile("^(runDate|bizDate)((?:[+-]\\d+[dwMy])*)(?::(.+))?$");

  public String resolve(String token, LocalDate runDate, int bizOffsetDays) {
    Matcher m = TOKEN.matcher(token);
    if (!m.matches()) return null; // 非时间变量，交给参数解析
    LocalDate base = m.group(1).equals("runDate") ? runDate : runDate.plusDays(bizOffsetDays);
    String offsets = m.group(2);
    if (offsets != null && !offsets.isEmpty()) {
      Matcher om = Pattern.compile("([+-])(\\d+)([dwMy])").matcher(offsets);
      while (om.find()) {
        long n = Long.parseLong(om.group(2)) * (om.group(1).equals("-") ? -1 : 1);
        base = switch (om.group(3)) {
          case "d" -> base.plusDays(n);
          case "w" -> base.plusWeeks(n);
          case "M" -> base.plusMonths(n);
          case "y" -> base.plusYears(n);
          default -> base;
        };
      }
    }
    String pattern = m.group(3);
    return base.format(pattern == null || pattern.isBlank()
        ? DateTimeFormatter.ISO_LOCAL_DATE : DateTimeFormatter.ofPattern(pattern));
  }
}
```

```java
package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component @RequiredArgsConstructor
public class ParamResolver {
  private static final Pattern HASH = Pattern.compile("#\\{([A-Za-z_]\\w*(?:[+-]\\d+[dwMy])*(?::[^}]+)?)\\}");
  private static final Pattern DOLLAR = Pattern.compile("\\$\\{(\\w+)\\}");
  private final TimeVariableResolver time;

  public PreparedSql prepare(String rawSql, List<ParameterDefinition> defs,
                             Map<String,String> staticParams, Map<String,String> runtimeParams,
                             LocalDate runDate, int bizOffsetDays) {
    Map<String, ParameterDefinition> defMap = new HashMap<>();
    defs.forEach(d -> defMap.put(d.name(), d));
    Map<String,String> audit = new LinkedHashMap<>();
    List<Object> binds = new ArrayList<>();

    // 1) ${name} 文本替换（先做，避免 #{} 占位后位置漂移）
    Matcher dm = DOLLAR.matcher(rawSql);
    StringBuilder sb = new StringBuilder();
    while (dm.find()) {
      String name = dm.group(1);
      ParameterDefinition def = defMap.get(name);
      String value = firstNonNull(runtimeParams.get(name), staticParams.get(name), def == null ? null : def.defaultValue());
      if (def == null || !def.allowTextSubstitution() || value == null
          || def.whitelistPattern() == null || !value.matches(def.whitelistPattern())) {
        throw new BizException(ErrorCode.SQL_005, "参数 " + name + " 文本替换校验失败");
      }
      audit.put(name, value);
      dm.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    dm.appendTail(sb);

    // 2) #{token} → ? 绑定
    Matcher hm = HASH.matcher(sb.toString());
    StringBuilder out = new StringBuilder();
    while (hm.find()) {
      String token = hm.group(1);
      String baseName = token.contains(":") ? token.substring(0, token.indexOf(':')) : token.replaceAll("[+-]\\d+[dwMy].*$", "");
      String value = firstNonNull(runtimeParams.get(baseName), staticParams.get(baseName));
      if (value == null) value = time.resolve(token, runDate, bizOffsetDays);
      ParameterDefinition def = defMap.get(baseName);
      if (value == null && def != null) value = def.defaultValue();
      if (value == null) {
        if (def != null && def.required()) throw new BizException(ErrorCode.SQL_004, "参数 " + baseName);
        if (def == null) throw new BizException(ErrorCode.SQL_004, "参数 " + baseName + "（无定义且非内置时间变量）");
      }
      ParamType type = def == null ? ParamType.DATE : def.type();
      binds.add(convert(value, type));
      audit.putIfAbsent(baseName, value);
      hm.appendReplacement(out, "?");
    }
    hm.appendTail(out);
    return new PreparedSql(out.toString(), binds, audit);
  }

  private Object convert(String v, ParamType t) {
    return switch (t) {
      case INT -> Long.parseLong(v);
      case DECIMAL -> new BigDecimal(v);
      case DATE -> java.sql.Date.valueOf(v.length() == 10 ? v : v.substring(0, 10));
      case STRING -> v;
    };
  }
  private String firstNonNull(String a, String b) { return a != null ? a : b; }
}
// record PreparedSql(String sql, List<Object> bindValues, Map<String,String> auditParams) {}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ParamResolverTest"`
Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(dataset): 时间变量+三级参数解析+#{}绑定/${}白名单替换(FR-SQL-02)"
```

---

### Task 8: 查询引擎 + 连接池注册表 + DatasetResult（FR-DS-04/FR-SQL-04）

**Files:**
- Create: `datasource/HikariPoolRegistry.java`、`datasource/QueryEngine.java`、`dataset/DatasetResult.java`、`dataset/ColumnMeta.java`
- Test: `datasource/QueryEngineTest.java`

**Interfaces:**
- Consumes: `PreparedSql`（Task 7）、`Datasource` 实体、`ConnectionTester` 的解密逻辑（复用 `AesGcmCipher`）
- Produces:
  - `record ColumnMeta(String name, String typeName)`
  - `DatasetResult`：`List<ColumnMeta> columns()`、`int totalRows()`、`boolean truncated()`、`void forEachRow(Consumer<Map<String,Object>> consumer)`、`List<Map<String,Object>> toList(int max)`
  - `QueryEngine.execute(Datasource ds, PreparedSql sql, int rowLimitOverride, Long execId) -> DatasetResult`（rowLimitOverride>0 时覆盖 ds.maxRows，预览用 200；execId 可空）
  - `HikariPoolRegistry.getOrCreate(Datasource) -> HikariDataSource`、`evict(Long dsId)`（数据源更新/停用时调用，Task 4 update 处补挂钩）
  - 超限行为：TRUNCATE→截断置 truncated=true；FAIL→抛 `BizException(SQL_003,"行数超限")`；超时→`DS_002`
  - 类型映射：DATE→`yyyy-MM-dd` 字符串，DATETIME/TIMESTAMP→ISO `yyyy-MM-dd'T'HH:mm:ss` 字符串，DECIMAL→BigDecimal，NULL→null
  - **M1 决议**：内部读满至上限后物化 List（Markdown 渲染需要随机访问）；M2 Excel 分批渲染时引入 ResultSet 直通迭代器（在 DatasetResult 实现类上扩展，不改接口）

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.datasource;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.dataset.DatasetResult;
import com.hermes.push.dataset.PreparedSql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QueryEngineTest extends AbstractIntegrationTest {
  @Autowired QueryEngine engine;
  @Autowired DatasourceService svc;

  // 种子库与测试库分离：hermes_test 每用例被 flyway clean 重建，6 万行 big_t 放 hermes_seed（每 JVM 只灌一次，不受 clean 影响）
  static final String SEED_DB_URL = TEST_DB_URL.replace("hermes_test", "hermes_seed");
  static boolean seeded = false;

  Long dsId(int maxRows, String overflow, int timeoutSec) {
    Long id = svc.save(new DatasourceSaveRequest("qe-" + System.nanoTime(), "MYSQL",
        SEED_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, true, maxRows, timeoutSec, 2, overflow), "admin");
    return id;
  }

  void seedRows() {
    if (seeded) return;
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(SEED_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD);
         java.sql.Statement st = c.createStatement()) {
      st.execute("CREATE TABLE IF NOT EXISTS big_t (id INT PRIMARY KEY, d DATE, amt DECIMAL(12,2))");
      var rs = st.executeQuery("SELECT COUNT(*) FROM big_t"); rs.next();
      if (rs.getInt(1) == 0) {
        StringBuilder sb = new StringBuilder("INSERT INTO big_t VALUES ");
        for (int i = 1; i <= 60001; i++) {
          sb.append("(").append(i).append(",'2026-09-21',").append(i).append(".5)");
          if (i < 60001) sb.append(",");
        }
        st.execute(sb.toString());
      }
    } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    seeded = true;
  }

  @Test void truncateAtMaxRows() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(50000, "TRUNCATE", 60));
    DatasetResult r = engine.execute(ds, new PreparedSql("SELECT id,d,amt FROM big_t", List.of(), Map.of()), 0, null);
    assertThat(r.totalRows()).isEqualTo(50000);
    assertThat(r.truncated()).isTrue();
    assertThat(r.columns()).extracting("name").contains("id","d","amt");
  }
  @Test void failPolicyThrows() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(100, "FAIL", 60));
    assertThatThrownBy(() -> engine.execute(ds, new PreparedSql("SELECT id FROM big_t", List.of(), Map.of()), 0, null))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-003");
  }
  @Test void timeout_ds002() {
    Datasource ds = svc.getEnabled(dsId(100, "TRUNCATE", 1));
    assertThatThrownBy(() -> engine.execute(ds, new PreparedSql("SELECT SLEEP(5)", List.of(), Map.of()), 0, null))
        .isInstanceOf(BizException.class).hasMessageContaining("DS-002");
  }
  @Test void typeMapping() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(10, "TRUNCATE", 60));
    DatasetResult r = engine.execute(ds, new PreparedSql("SELECT d, amt FROM big_t WHERE id=1", List.of(), Map.of()), 0, null);
    List<Map<String,Object>> rows = r.toList(10);
    assertThat(rows.get(0).get("d")).isEqualTo("2026-09-21");
    assertThat(rows.get(0).get("amt")).isEqualByComparingTo("1.5");
  }
  @Test void rowLimitOverrideForPreview() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(50000, "TRUNCATE", 60));
    DatasetResult r = engine.execute(ds, new PreparedSql("SELECT id FROM big_t", List.of(), Map.of()), 200, null);
    assertThat(r.totalRows()).isEqualTo(200);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=QueryEngineTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

`HikariPoolRegistry`：`ConcurrentHashMap<Long, HikariDataSource>`；`getOrCreate` 用 computeIfAbsent 建池（jdbcUrl/username/password(解密)/maximumPoolSize=ds.poolMax/connectionTimeout=10s/readOnly=true/autoCommit=true/poolName=hp-ds-{id}）；`evict` close 并移除。

`QueryEngine` 核心：

```java
public DatasetResult execute(Datasource ds, PreparedSql sql, int rowLimitOverride, Long execId) {
  int limit = rowLimitOverride > 0 ? rowLimitOverride : ds.getMaxRows();
  HikariDataSource pool = pools.getOrCreate(ds);
  long t0 = System.nanoTime();
  try (Connection c = pool.getConnection();
       PreparedStatement ps = c.prepareStatement(sql.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
    ps.setQueryTimeout(ds.getQueryTimeoutSec());
    ps.setFetchSize(1000);
    ps.setMaxRows(limit + 1); // 多取一行判定截断
    for (int i = 0; i < sql.bindValues().size(); i++) ps.setObject(i + 1, sql.bindValues().get(i));
    try (ResultSet rs = ps.executeQuery()) {
      ResultSetMetaData md = rs.getMetaData();
      List<ColumnMeta> cols = new ArrayList<>();
      for (int i = 1; i <= md.getColumnCount(); i++) cols.add(new ColumnMeta(md.getColumnLabel(i), md.getColumnTypeName(i)));
      List<Map<String,Object>> rows = new ArrayList<>();
      boolean truncated = false;
      while (rs.next()) {
        if (rows.size() >= limit) { truncated = true; break; }
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols.size(); i++) row.put(cols.get(i-1).name(), mapValue(rs, i));
        rows.add(row);
      }
      if (truncated && "FAIL".equals(ds.getOverflowPolicy()))
        throw new BizException(ErrorCode.SQL_003, "行数超限(" + limit + ")且策略为 FAIL");
      return new MaterializedDatasetResult(cols, truncated ? rows : rows, rows.size(), truncated,
          (System.nanoTime() - t0) / 1_000_000);
    }
  } catch (BizException e) { throw e; }
  catch (SQLTimeoutException e) { throw new BizException(ErrorCode.DS_002, "查询超时(" + ds.getQueryTimeoutSec() + "s)"); }
  catch (Exception e) { throw new BizException(ErrorCode.DS_002, e.getMessage()); }
}
private Object mapValue(ResultSet rs, int i) throws SQLException {
  Object v = rs.getObject(i);
  if (v == null) return null;
  if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
  if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  if (v instanceof LocalDateTime ldt) return ldt.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  if (v instanceof LocalDate ld) return ld.toString();
  return v;
}
```

`MaterializedDatasetResult` 实现 `DatasetResult`（toList(max) 取前 max 行；forEachRow 迭代内部 List）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=QueryEngineTest"`
Expected: 5 tests PASS（60k 行 seed 首次较慢属预期；超时用例 5s 内返回）

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(datasource): 查询引擎(超时/行数保护/类型映射)+按数据源独立连接池(FR-DS-04,FR-SQL-04)"
```

---

### Task 9: SQL 审计落库（FR-SEC-03）

**Files:**
- Create: `audit/SqlAudit.java`、`SqlAuditMapper.java`、`SqlAuditService.java`
- Test: `audit/SqlAuditServiceTest.java`

**Interfaces:**
- Consumes: `hp_sql_audit` 表（Task 1）
- Produces: `SqlAuditService.record(SqlAuditScene scene, Long datasourceId, String sqlText, Map<String,String> params, Integer rows, Long costMs, String operator, Long execId, String clientIp)`；`enum SqlAuditScene{PREVIEW,TRIAL,EXEC,VALIDATION_FAILED,ALERT_EVAL,FANOUT_LIST}`（后两个 M1 只定义不使用）；`SqlAuditService.recordValidationFailed(Long datasourceId, String sqlText, String operator, String clientIp, String reason)`（params 存 `{reason: ...}`）。敏感参数脱敏：调用方传入的 params 中 key 命中 ParameterDefinition.sensitive（M1 无该字段，规则预留：key 以 `pwd`/`secret`/`idcard`/`phone` 开头时值替换 `***`）。查询接口 `page(scene, datasourceId, from, to, page, size)` 给 M4a 页面用，M1 先提供 count 断言能力。

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.audit;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SqlAuditServiceTest extends AbstractIntegrationTest {
  @Autowired SqlAuditService audit;
  @Autowired JdbcTemplate jdbc;

  @Test void recordExecScene() {
    audit.record(SqlAuditScene.EXEC, 1L, "SELECT 1", Map.of("bizDate","2026-09-21"), 1, 12L, "exec", 100L, "10.0.0.9");
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='EXEC'", Integer.class);
    assertThat(n).isGreaterThanOrEqualTo(1);
  }
  @Test void sensitiveParamMasked() {
    Map<String,String> p = new HashMap<>(); p.put("phoneNo","13800001111"); p.put("bizDate","2026-09-21");
    audit.record(SqlAuditScene.PREVIEW, 1L, "SELECT 1", p, 1, 1L, "admin", null, "127.0.0.1");
    String json = jdbc.queryForObject("SELECT params_json FROM hp_sql_audit WHERE scene='PREVIEW' ORDER BY id DESC LIMIT 1", String.class);
    assertThat(json).doesNotContain("13800001111").contains("***").contains("2026-09-21");
  }
  @Test void validationFailedRecorded() {
    audit.recordValidationFailed(1L, "DROP TABLE x", "admin", "127.0.0.1", "SQL-002 只允许单条 SELECT");
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='VALIDATION_FAILED'", Integer.class);
    assertThat(n).isEqualTo(1);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=SqlAuditServiceTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

实体 `SqlAudit` 映射 `hp_sql_audit`（params_json 用 String 存 Jackson 序列化结果）。`SqlAuditService`：脱敏规则 `SENSITIVE_PREFIXES = {"pwd","secret","idcard","phone"}`（startsWith 忽略大小写）；record 内捕获自身异常仅 log.error（审计失败不阻断业务，但 VALIDATION_FAILED 场景除外——它本身就是记录动作）。MyBatis-Plus BaseMapper 即可，无自定义 SQL。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=SqlAuditServiceTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(audit): SQL审计落库(PREVIEW/TRIAL/EXEC/VALIDATION_FAILED)+敏感参数脱敏(FR-SEC-03)"
```

---

### Task 10: 数据预览 API（FR-SQL-03，串起 4-9）

**Files:**
- Create: `dataset/PreviewController.java`、`dataset/PreviewRequest.java`、`dataset/PreviewService.java`
- Test: `dataset/PreviewServiceTest.java`

**Interfaces:**
- Consumes: `DruidSqlValidator`、`ParamResolver`、`QueryEngine`、`SqlAuditService`、`DatasourceService`
- Produces: `POST /api/datasources/{id}/preview`，请求体 `record PreviewRequest(String sql, Map<String,String> params)`，响应 `PreviewVO(List<ColumnMeta> columns, List<Map<String,Object>> rows, int totalRows, boolean truncated, long costMs)`。**行为顺序**：validate（失败→记 VALIDATION_FAILED 审计后抛出）→ resolve（runDate=数据库当前日期，bizOffsetDays=-1 固定）→ execute(rowLimitOverride=200) → 记 PREVIEW 审计（rows=返回行数）。

- [ ] **Step 1: 写失败测试（MockMvc）**

```java
package com.hermes.push.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.datasource.DatasourceSaveRequest;
import com.hermes.push.datasource.DatasourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class PreviewServiceTest extends AbstractIntegrationTest {
  @Autowired MockMvc mvc; @Autowired DatasourceService dsSvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;

  Long ds() {
    jdbc.execute("CREATE TABLE IF NOT EXISTS pv_t(a INT, dt DATE)");
    jdbc.execute("INSERT IGNORE INTO pv_t VALUES (1,'2026-09-21'),(2,'2026-09-21')");
    return dsSvc.save(new DatasourceSaveRequest("pv-" + System.nanoTime(), "MYSQL", MYSQL.getJdbcUrl(), "root", "root", true, null, 30, null, null), "admin");
  }

  @Test void previewOk_andAuditsPreview() throws Exception {
    Long id = ds();
    mvc.perform(post("/api/datasources/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(new PreviewRequest("SELECT a, dt FROM pv_t WHERE dt = #{bizDate}", Map.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalRows").value(2))
        .andExpect(jsonPath("$.data.rows[0].dt").value("2026-09-21"));
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='PREVIEW' AND datasource_id=?", Integer.class, id);
    org.assertj.core.api.Assertions.assertThat(n).isEqualTo(1);
  }
  @Test void previewRejectsDml_andAuditsValidationFailed() throws Exception {
    Long id = ds();
    mvc.perform(post("/api/datasources/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(new PreviewRequest("DELETE FROM pv_t", Map.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.data.errorCode").value("SQL-002"));
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='VALIDATION_FAILED' AND datasource_id=?", Integer.class, id);
    org.assertj.core.api.Assertions.assertThat(n).isEqualTo(1);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=PreviewServiceTest"`
Expected: 404 / COMPILATION ERROR

- [ ] **Step 3: 实现 PreviewService + Controller**

```java
@Service @RequiredArgsConstructor
public class PreviewService {
  private final DatasourceService dsSvc; private final DruidSqlValidator validator;
  private final ParamResolver params; private final QueryEngine engine;
  private final SqlAuditService audit; private final JdbcTemplate jdbc;

  public PreviewVO preview(Long dsId, PreviewRequest req, String operator, String ip) {
    var ds = dsSvc.getEnabled(dsId);
    try {
      validator.validate(req.sql(), ds.getType());
    } catch (BizException e) {
      audit.recordValidationFailed(dsId, req.sql(), operator, ip, e.getErrorCode().getCode() + " " + e.getDetail());
      throw e;
    }
    LocalDate runDate = jdbc.queryForObject("SELECT CURDATE()", LocalDate.class); // 数据库日期
    PreparedSql p = params.prepare(req.sql(), List.of(), Map.of(), req.params() == null ? Map.of() : req.params(), runDate, -1);
    long t0 = System.nanoTime();
    DatasetResult r = engine.execute(ds, p, 200, null);
    long cost = (System.nanoTime() - t0) / 1_000_000;
    audit.record(SqlAuditScene.PREVIEW, dsId, req.sql(), p.auditParams(), r.totalRows(), cost, operator, null, ip);
    return new PreviewVO(r.columns(), r.toList(200), r.totalRows(), r.truncated(), cost);
  }
}
```

Controller：`@PostMapping("/api/datasources/{id}/preview")`，operator 取 `CurrentUserHolder.get()`，ip 取 `request.getRemoteAddr()`，返回 `ApiResponse.ok(vo)`。注意：preview 的 `#{bizDate}` 无 defs 时 ParamResolver 按内置时间变量解析（Task 7 已支持 def==null 走 time.resolve）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=PreviewServiceTest"`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(dataset): 数据预览API(200行上限,PREVIEW/VALIDATION_FAILED审计)(FR-SQL-03)"
```

---

### Task 11: 执行记录 + DB 队列（SKIP LOCKED 领取 / 心跳 / 状态迁移）（FR-EXE-02/03）

**Files:**
- Create: `exec/TaskExec.java`、`exec/ExecStatus.java`、`exec/TriggerType.java`、`exec/ExecPriority.java`、`exec/ExecQueueMapper.java`、`resources/mapper/ExecQueueMapper.xml`、`exec/ExecQueueRepository.java`
- Test: `exec/ExecQueueRepositoryTest.java`

**Interfaces:**
- Consumes: `hp_task_exec` 表（Task 1）
- Produces（Task 13/17/18 依赖，签名冻结）:
  - `enum ExecStatus{PENDING,RUNNING,RETRY_WAIT,SUCCESS,PARTIAL_SUCCESS,FAILED,TIMEOUT,CANCELLED}`、`enum TriggerType{CRON,MANUAL,TEST,TRIAL,API}`
  - `ExecPriority`：常量 `TRIAL_TEST=70, MANUAL_API=60, CRON=40`
  - `ExecQueueRepository`：
    - `Long insertPending(long taskId, long taskVersionId, TriggerType trig, int priority, LocalDateTime fireTime, LocalDate bizDate, String paramsJson, String idemKey)`——时间字段由 XML 内 `NOW(3)`/传入值决定；**普通 INSERT + 捕获 DuplicateKeyException 返回 null**（幂等，评审修订 H2 + Task 11 评审轮修订：不用 INSERT IGNORE，避免吞掉数据截断/NOT NULL 等真实错误）
    - `Optional<Long> claim(String nodeId)`——事务内两步：SKIP LOCKED 选 id → 条件 UPDATE 置 RUNNING；无任务返回 empty
    - `boolean heartbeat(long execId, String nodeId)`——`UPDATE ... SET heartbeat_at=NOW(3) WHERE id=? AND node_id=? AND status='RUNNING'`；返回 false 表示已被巡检重置，Worker 必须放弃（评审修订 B3）
    - `boolean retryWait(long execId, String errorCode, String errorMsg, int backoffSeconds)`——置 RETRY_WAIT、retry_count+1、`next_retry_at=NOW(3)+INTERVAL ? SECOND`（数据库时间，评审修订 H5）；**返回影响行数>0**（false=状态已非 RUNNING，调用方须放弃，Task 11 评审轮修订）
    - `boolean finish(long execId, ExecStatus status, String stageCostsJson, Integer rowsTotal, String errorCode, String errorMsg)`——cost_ms 用 `TIMESTAMPDIFF(MICROSECOND, created_at, NOW(3))/1000`；**返回影响行数>0**（false=状态已被并发迁移，调用方须记录告警日志，Task 11 评审轮修订）
    - `int promoteDueRetries()`——RETRY_WAIT 且到期 → PENDING（Task 18 调用）
    - `TaskExec getById(long execId)`
- 领取 SQL 手写 XML，不经 MyBatis-Plus 封装（PRD 第 8 章约束），Mapper 接口方法逐个对应 XML 语句

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.exec;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ExecQueueRepositoryTest extends AbstractIntegrationTest {
  @Autowired ExecQueueRepository queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() { jdbc.update("DELETE FROM hp_task_exec"); } // 用例间隔离，断言可精确

  Long pending(int priority, String idem) {
    return queue.insertPending(1L, 1L, TriggerType.CRON, priority,
        LocalDateTime.now().minusMinutes(1), LocalDate.now(), "{}", idem);
  }

  @Test void claimReturnsHighestPriorityFirst() {
    Long low = pending(40, "c1");
    Long high = pending(70, "c2");
    assertThat(queue.claim("node-a")).contains(high); // priority DESC 先领 70
    assertThat(queue.claim("node-a")).contains(low);
    assertThat(queue.claim("node-a")).isEmpty();
  }
  @Test void concurrentClaimNoDuplicate() throws Exception {
    for (int i = 0; i < 6; i++) pending(40, "cc" + i);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    AtomicInteger claimed = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    var futures = new java.util.ArrayList<Future<?>>();
    for (int t = 0; t < 3; t++) futures.add(pool.submit(() -> {
      try { start.await(); } catch (InterruptedException ignored) {}
      while (queue.claim("node-" + Thread.currentThread().threadId()).isPresent()) claimed.incrementAndGet();
    }));
    start.countDown();
    for (var f : futures) f.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(claimed.get()).isEqualTo(6); // 每条恰好被领取一次
    Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec WHERE status='RUNNING'", Integer.class);
    assertThat(running).isEqualTo(6);
  }
  @Test void heartbeatFailsForWrongNode() {
    Long id = pending(40, "hb1");
    queue.claim("node-x");
    assertThat(queue.heartbeat(id, "node-x")).isTrue();
    assertThat(queue.heartbeat(id, "node-other")).isFalse(); // 节点不符
  }
  @Test void duplicateIdemKeyReturnsNull() {
    Long a = queue.insertPending(2L, 1L, TriggerType.API, 60, LocalDateTime.now(), LocalDate.now(), "{}", "idem-1");
    Long b = queue.insertPending(2L, 1L, TriggerType.API, 60, LocalDateTime.now(), LocalDate.now(), "{}", "idem-1");
    assertThat(a).isNotNull(); assertThat(b).isNull();
  }
  @Test void retryWaitSetsDbTimeAndPromote() throws Exception {
    Long id = pending(40, "rw1");
    queue.claim("node-y");
    queue.retryWait(id, "PUSH-011", "rate limited", 1);
    Thread.sleep(1500);
    assertThat(queue.promoteDueRetries()).isEqualTo(1);
    assertThat(queue.getById(id).getStatus()).isEqualTo(ExecStatus.PENDING.name());
    assertThat(queue.getById(id).getRetryCount()).isEqualTo(1);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ExecQueueRepositoryTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 XML（核心语句全文）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.hermes.push.exec.ExecQueueMapper">

  <insert id="insertPending" useGeneratedKeys="true" keyProperty="id">
    INSERT IGNORE INTO hp_task_exec
      (task_id, task_version_id, trigger_type, priority, status, fire_time, biz_date, params_json, idempotency_key)
    VALUES
      (#{taskId}, #{taskVersionId}, #{triggerType}, #{priority}, 'PENDING', #{fireTime}, #{bizDate}, #{paramsJson}, #{idemKey})
  </insert>

  <select id="selectClaimableId" resultType="long">
    SELECT id FROM hp_task_exec
    WHERE status = 'PENDING'
    ORDER BY priority DESC, fire_time ASC
    LIMIT 1
    FOR UPDATE SKIP LOCKED
  </select>

  <update id="markRunning">
    UPDATE hp_task_exec SET status='RUNNING', node_id=#{nodeId}, heartbeat_at=NOW(3)
    WHERE id=#{id} AND status='PENDING'
  </update>

  <update id="heartbeat">
    UPDATE hp_task_exec SET heartbeat_at=NOW(3)
    WHERE id=#{id} AND node_id=#{nodeId} AND status='RUNNING'
  </update>

  <update id="retryWait">
    UPDATE hp_task_exec
    SET status='RETRY_WAIT', retry_count=retry_count+1,
        next_retry_at=NOW(3) + INTERVAL #{backoffSeconds} SECOND,
        error_code=#{errorCode}, error_msg=#{errorMsg}
    WHERE id=#{id} AND status='RUNNING'
  </update>

  <update id="promoteDueRetries">
    UPDATE hp_task_exec SET status='PENDING', node_id=NULL, heartbeat_at=NULL
    WHERE status='RETRY_WAIT' AND next_retry_at &lt;= NOW(3)
  </update>

  <update id="finish">
    UPDATE hp_task_exec
    SET status=#{status}, stage_costs_json=#{stageCosts}, rows_total=#{rowsTotal},
        error_code=#{errorCode}, error_msg=#{errorMsg},
        cost_ms=TIMESTAMPDIFF(MICROSECOND, created_at, NOW(3))/1000
    WHERE id=#{id} AND status='RUNNING'
  </update>
</mapper>
```

`ExecQueueRepository.claim(nodeId)`：`@Transactional` 方法内先 `selectClaimableId`（无则 empty），再 `markRunning`，影响行数 0 时返回 empty（被并发抢走）。`insertPending` 用 `INSERT IGNORE` + 检查 `keyProperty` 是否回填（0 表示唯一键冲突）返回 null。实体 `TaskExec` 为普通 `@Data` + `@TableName("hp_task_exec")`，status 存 String。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ExecQueueRepositoryTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(exec): DB队列SKIP LOCKED领取+心跳+重试状态迁移，手写XML(FR-EXE-02/03)"
```

---

### Task 12: 任务配置 JSON 模型 + 版本快照 + 生命周期（FR-TSK-01/03/05）

**Files:**
- Create: `task/TaskConfig.java`（含嵌套 record）、`task/Task.java`、`task/TaskVersion.java`、两个 Mapper、`task/TaskService.java`、`task/ScheduleSyncPort.java`、`task/CurrentUserHolder.java`
- Test: `task/TaskServiceTest.java`

**Interfaces:**
- Consumes: `ExecQueueRepository`（Task 11，试运行门槛查询用 JdbcTemplate 亦可）、`ErrorCode`
- Produces（Task 13/17 依赖，签名冻结）:
  - `record TaskConfig(List<DatasetDef> datasets, List<ArtifactDef> artifacts, List<ChannelBinding> channelBindings, ScheduleDef schedule)`
    - `record DatasetDef(String key, Long datasourceId, String sql, List<ParameterDefinition> params)`
    - `record ArtifactDef(String key, String type, String inlineTemplate, String overflowStrategy, Integer maxBytes)`（M1 type 仅 `MARKDOWN`；overflowStrategy `TRUNCATE|FAIL`；maxBytes 默认按渠道 4096）
    - `record ChannelBinding(Long channelId, List<String> artifactKeys, String msgType)`（M1 msgType `text|markdown`）
    - `record ScheduleDef(String cron, Integer bizOffsetDays, Integer timeoutMinutes, Integer maxRetry, boolean jitterEnabled)`
  - `TaskService.create(String name, String taskKey, TaskConfig config, String remark, String operator) -> Long`（建 task + version 1）
  - `TaskService.saveVersion(Long taskId, TaskConfig config, String remark, int expectedLockVersion, String operator) -> Long versionId`（乐观锁冲突抛 `SYS_004`）
  - `TaskService.publish(Long taskId)` / `offline(Long taskId)` / `pause(Long taskId)` / `resume(Long taskId)`
  - `TaskService.loadEffectiveConfig(Long taskId) -> record EffectiveConfig(Long taskId, Long taskVersionId, TaskConfig config, String cron, String owner)`（pinned 优先，否则 current）
  - `TaskService.hasSuccessTrial(Long taskId, Long taskVersionId) -> boolean`
  - `interface ScheduleSyncPort { void onPublish(Long taskId, String cron); void onOffline(Long taskId); void onCronChange(Long taskId, String cron); }`（Task 13 实现；Task 12 注入 `ObjectProvider<ScheduleSyncPort>`，缺省 no-op 以便独立测试）
  - `CurrentUserHolder.get() -> String`：ThreadLocal 门面，M1 默认 "admin"，Task 20 接 Sa-Token
- 保存校验规则（`TaskConfigValidator` 静态方法，publish 与 saveVersion 都跑）：datasets/artifacts/channelBindings 各 ≥1；dataset.key 唯一且匹配 `^[a-zA-Z]\w{0,31}$`；params 名不得为 `runDate`/`bizDate`（评审修订 M22）；artifact.inlineTemplate 必须过 `TemplateScanner.scan`（Task 14 提供——Task 12 先定义 `interface TemplateScanPort { void scan(String content); }` 注入 ObjectProvider，Task 14 实现）；binding 的 artifactKeys 必须存在于 artifacts；cron 合法（`CronExpression.isValidExpression`）。

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.task;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.dataset.ParamType;
import com.hermes.push.dataset.ParameterDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TaskServiceTest extends AbstractIntegrationTest {
  @Autowired TaskService svc; @Autowired JdbcTemplate jdbc;

  TaskConfig cfg(String cron) {
    return new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", 1L, "SELECT 1 AS n", List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", "结果: ${ds1.rows[0].n}", "TRUNCATE", 4096)),
      List.of(new TaskConfig.ChannelBinding(1L, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef(cron, -1, 10, 3, false));
  }

  @Test void createMakesVersion1() {
    Long id = svc.create("日报", "daily-" + System.nanoTime(), cfg("0 0 9 * * ?"), "init", "admin");
    var eff = svc.loadEffectiveConfig(id);
    assertThat(eff.config().datasets()).hasSize(1);
    assertThat(eff.taskVersionId()).isNotNull();
  }
  @Test void publishWithoutTrialRejected() {
    Long id = svc.create("日报2", "daily2-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    assertThatThrownBy(() -> svc.publish(id)).isInstanceOf(BizException.class).hasMessageContaining("试运行");
  }
  @Test void publishWithTrialSucceeds() {
    Long id = svc.create("日报3", "daily3-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    var eff = svc.loadEffectiveConfig(id);
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))", id, eff.taskVersionId());
    svc.publish(id);
    assertThat(svc.loadEffectiveConfig(id)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT status FROM hp_task WHERE id=?", String.class, id)).isEqualTo("ONLINE");
  }
  @Test void optimisticLockConflict() {
    Long id = svc.create("日报4", "daily4-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    svc.saveVersion(id, cfg("0 0 10 * * ?"), "v2", 0, "admin");           // lock 0->1
    assertThatThrownBy(() -> svc.saveVersion(id, cfg("0 0 11 * * ?"), "v3", 0, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("SYS-004");
  }
  @Test void paramCollidingWithBuiltinRejected() {
    var bad = new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", 1L, "SELECT #{bizDate}",
          List.of(new ParameterDefinition("bizDate", ParamType.DATE, false, null, false, null)))),
      cfg("0 0 9 * * ?").artifacts(), cfg("0 0 9 * * ?").channelBindings(), cfg("0 0 9 * * ?").schedule());
    assertThatThrownBy(() -> svc.create("坏任务", "bad-" + System.nanoTime(), bad, null, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("内置时间变量");
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=TaskServiceTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现要点（非样板代码全文）**

```java
@Service @RequiredArgsConstructor
public class TaskService {
  private final TaskMapper taskMapper; private final TaskVersionMapper versionMapper;
  private final JdbcTemplate jdbc; private final ObjectMapper om;
  private final ObjectProvider<ScheduleSyncPort> syncPort;
  private final ObjectProvider<TemplateScanPort> scanPort;

  public Long create(String name, String taskKey, TaskConfig config, String remark, String operator) {
    validate(config);
    Task t = new Task(); t.setName(name); t.setTaskKey(taskKey); t.setTaskType("REPORT");
    t.setStatus("DRAFT"); t.setOwner(operator);
    t.setCronExpr(config.schedule().cron());
    taskMapper.insert(t);
    Long vid = insertVersion(t.getId(), 1, config, remark, operator);
    t.setCurrentVersionId(vid); taskMapper.updateById(t);
    return t.getId();
  }

  public Long saveVersion(Long taskId, TaskConfig config, String remark, int expectedLock, String operator) {
    validate(config);
    Task t = require(taskId);
    if (t.getLockVersion() != expectedLock) throw new BizException(ErrorCode.SYS_004, "lockVersion " + expectedLock + " != " + t.getLockVersion());
    int no = nextVersionNo(taskId);
    Long vid = insertVersion(taskId, no, config, remark, operator);
    int updated = jdbc.update("UPDATE hp_task SET current_version_id=?, cron_expr=?, lock_version=lock_version+1 WHERE id=? AND lock_version=?",
        vid, config.schedule().cron(), taskId, expectedLock);
    if (updated == 0) throw new BizException(ErrorCode.SYS_004, "并发保存冲突");
    if ("ONLINE".equals(t.getStatus())) syncPort().onCronChange(taskId, config.schedule().cron());
    return vid;
  }

  public void publish(Long taskId) {
    Task t = require(taskId);
    if (!Set.of("DRAFT","OFFLINE","PAUSED").contains(t.getStatus())) throw new BizException(ErrorCode.SYS_002, "当前状态 " + t.getStatus());
    Long vid = t.getPinnedVersionId() != null ? t.getPinnedVersionId() : t.getCurrentVersionId();
    if (!hasSuccessTrial(taskId, vid)) throw new BizException(ErrorCode.SYS_002, "当前版本未试运行通过，不能上线（FR-TSK-02）");
    t.setStatus("ONLINE"); taskMapper.updateById(t);
    syncPort().onPublish(taskId, t.getCronExpr());
  }

  public boolean hasSuccessTrial(Long taskId, Long versionId) {
    Integer n = jdbc.queryForObject(
      "SELECT COUNT(*) FROM hp_task_exec WHERE task_id=? AND task_version_id=? AND trigger_type='TRIAL' AND status='SUCCESS'",
      Integer.class, taskId, versionId);
    return n != null && n > 0;
  }

  public EffectiveConfig loadEffectiveConfig(Long taskId) {
    Task t = require(taskId);
    Long vid = t.getPinnedVersionId() != null ? t.getPinnedVersionId() : t.getCurrentVersionId();
    TaskVersion v = versionMapper.selectById(vid);
    try { return new EffectiveConfig(taskId, vid, om.readValue(v.getConfigJson(), TaskConfig.class), t.getCronExpr(), t.getOwner()); }
    catch (Exception e) { throw new BizException(ErrorCode.SYS_003, "配置反序列化失败 v" + v.getVersionNo()); }
  }
  // offline/pause/resume: 状态机校验 + syncPort().onOffline；require/insertVersion/nextVersionNo/validate 常规实现
  // validate(): 见 Interfaces 中校验规则；模板扫描 scanPort.ifAvailable(p -> artifacts.forEach(a -> p.scan(a.inlineTemplate())))
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=TaskServiceTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(task): 任务配置JSON模型+版本快照+乐观锁+上线试运行门槛(FR-TSK-01/03/05)"
```

---

### Task 13: Quartz 调度接线（FR-EXE-01）

**Files:**
- Create: `schedule/ReportTriggerJob.java`、`schedule/ScheduleSyncService.java`、`schedule/JitterCalculator.java`、`schedule/CronPreviewController.java`
- Modify: `application.yaml`（quartz 集群属性已在 Task 1）；`hermes-server/pom.xml` 加 `org.awaitility:awaitility`(test)
- Test: `schedule/ScheduleSyncServiceTest.java`、`schedule/JitterCalculatorTest.java`

**Interfaces:**
- Consumes: `ScheduleSyncPort`（Task 12 定义）、`ExecQueueRepository.insertPending`、`TaskService.loadEffectiveConfig`
- Produces:
  - `ScheduleSyncService implements ScheduleSyncPort`：Quartz JobKey=`hermes-task-{taskId}`；CronTrigger `withMisfireHandlingInstructionDoNothing()`（错过超过一个周期不补发，PRD FR-EXE-01）
  - `ReportTriggerJob implements Job`：execute 内加载 EffectiveConfig → bizDate = `SELECT CURDATE() + INTERVAL offset DAY`（数据库日期）→ fireTime = `context.getScheduledFireTime()` + jitter → `insertPending(CRON, priority=40, idemKey=null)`；任务非 ONLINE 或插入冲突（返回 null）静默跳过
  - `JitterCalculator.offsetSeconds(long taskId, LocalDate day) -> int`：`(sha256(taskId+":"+day) 前4字节转int) mod 300`，同一任务当日固定（PRD：抖动当日固定）
  - `GET /api/cron/preview?expr=` → `{valid:boolean, next:[5个ISO时间]}`（`CronExpression.isValidExpression` + 循环 `getNextValidTimeAfter`）

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.schedule;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.task.TaskConfig;
import com.hermes.push.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class ScheduleSyncServiceTest extends AbstractIntegrationTest {
  @Autowired TaskService tasks; @Autowired ScheduleSyncService sync; @Autowired JdbcTemplate jdbc;

  TaskConfig cfg(String cron) {
    return new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", 1L, "SELECT 1 AS n", List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", "n=${ds1.rows[0].n}", "TRUNCATE", 4096)),
      List.of(new TaskConfig.ChannelBinding(1L, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef(cron, -1, 10, 3, false));
  }

  @Test void publishSchedulesQuartzTrigger() {
    Long id = tasks.create("qz1", "qz1-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    var eff = tasks.loadEffectiveConfig(id);
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))", id, eff.taskVersionId());
    tasks.publish(id);
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE TRIGGER_NAME=?", Integer.class, "hermes-task-" + id);
    assertThat(n).isEqualTo(1);
    tasks.offline(id);
    n = jdbc.queryForObject("SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE TRIGGER_NAME=?", Integer.class, "hermes-task-" + id);
    assertThat(n).isZero();
  }
  @Test void manualJobFireInsertsPendingExec() {
    Long id = tasks.create("qz2", "qz2-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    sync.onPublish(id, "0 0 9 * * ?");
    sync.triggerNow(id); // 测试辅助：scheduler.triggerJob
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec WHERE task_id=? AND trigger_type='CRON'", Integer.class, id);
      assertThat(n).isEqualTo(1);
    });
  }
  @Test void jitterDeterministicPerDay() {
    JitterCalculator j = new JitterCalculator();
    int a = j.offsetSeconds(7L, LocalDate.of(2026,9,22));
    int b = j.offsetSeconds(7L, LocalDate.of(2026,9,22));
    int c = j.offsetSeconds(8L, LocalDate.of(2026,9,22));
    assertThat(a).isEqualTo(b).isBetween(0, 299);
    // 不同任务大概率不同（sha256），允许碰撞，仅验证范围
    assertThat(c).isBetween(0, 299);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ScheduleSyncServiceTest+JitterCalculatorTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 ReportTriggerJob 核心（全文）**

```java
package com.hermes.push.schedule;

import com.hermes.push.exec.*;
import com.hermes.push.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;

@Slf4j @Component @RequiredArgsConstructor
@DisallowConcurrentExecution
public class ReportTriggerJob implements Job {
  private final TaskService tasks; private final ExecQueueRepository queue;
  private final JdbcTemplate jdbc; private final JitterCalculator jitter;

  @Override public void execute(JobExecutionContext ctx) {
    Long taskId = ctx.getMergedJobDataMap().getLong("taskId");
    try {
      var eff = tasks.loadEffectiveConfig(taskId);
      String status = jdbc.queryForObject("SELECT status FROM hp_task WHERE id=?", String.class, taskId);
      if (!"ONLINE".equals(status)) { log.info("task {} not ONLINE, skip", taskId); return; }
      int offset = eff.config().schedule().bizOffsetDays() == null ? -1 : eff.config().schedule().bizOffsetDays();
      LocalDate bizDate = jdbc.queryForObject("SELECT CURDATE() + INTERVAL ? DAY", LocalDate.class, offset);
      LocalDate today = jdbc.queryForObject("SELECT CURDATE()", LocalDate.class);
      var fire = ctx.getScheduledFireTime().toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
      if (jdbc.queryForObject("SELECT jitter_enabled FROM hp_task WHERE id=?", Boolean.class, taskId)) {
        fire = fire.plusSeconds(jitter.offsetSeconds(taskId, today));
      }
      Long execId = queue.insertPending(taskId, eff.taskVersionId(), TriggerType.CRON, ExecPriority.CRON,
          fire, bizDate, "{}", null);
      log.info("task {} enqueued exec={} fire={}", taskId, execId, fire);
    } catch (Exception e) {
      log.error("trigger job failed task={}", taskId, e); // 调度失败不抛回 Quartz（避免 refire），下个周期自愈
    }
  }
}
```

`ScheduleSyncService`：注入 `Scheduler`；`onPublish` = `scheduleJob(JobBuilder.newJob(ReportTriggerJob.class).withIdentity(key).usingJobData("taskId", id).build(), TriggerBuilder.newTrigger().withIdentity(key).withSchedule(CronScheduleBuilder.cronSchedule(cron).inTimeZone(TimeZone.getTimeZone("Asia/Shanghai")).withMisfireHandlingInstructionDoNothing()).build())`，已存在则 `rescheduleJob`；`onOffline` = `deleteJob`；`triggerNow(id)` = `scheduler.triggerJob(key)`（测试与手动补偿共用）。Job 实例化交给 Spring（`SpringBeanJobFactory` 由 boot starter 自动配置）。

`CronPreviewController`：expr 非法返回 `{valid:false,next:[]}`；合法则从数据库 `SELECT NOW(3)` 起算循环取 5 次。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ScheduleSyncServiceTest+JitterCalculatorTest"`
Expected: PASS（manualJobFire 用例需要 quartz auto-startup=true——本测试类用 `@TestPropertySource(properties="spring.quartz.auto-startup=true")` 覆盖基类设置）

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src hermes-server/pom.xml
git commit -m "feat(schedule): Quartz集群接线(misfire do-nothing/抖动/唯一索引防重)+cron预览(FR-EXE-01)"
```

---

### Task 14: Freemarker 沙箱 + Markdown 渲染器（FR-RD-02/FR-RD-10，阻塞级安全项）

**Files:**
- Create: `render/TemplateScanner.java`、`render/SandboxFreemarker.java`、`render/MarkdownRenderer.java`、`render/RenderedArtifact.java`、`render/TemplateScanAdapter.java`（实现 Task 12 的 `TemplateScanPort`）
- Modify: `application.yaml` 加 `hermes.render.timeout-sec: 30`
- Test: `render/MarkdownRendererTest.java`、`render/TemplateScannerTest.java`

**Interfaces:**
- Consumes: `TaskConfig.ArtifactDef`（Task 12）、`DatasetResult`（Task 8）、`ErrorCode.TPL_003/TPL_010/SYS_005/RD_002`
- Produces:
  - `TemplateScanner.scan(String content)`——命中 `?new`、`?api`、`freemarker.template.utility`、`ObjectConstructor`、`Execute`、`JythonRuntime` 抛 `BizException(TPL_010, 命中词)`
  - `SandboxFreemarker.render(String templateName, String content, Map<String,Object> model, int timeoutSec) -> String`——ALLOWS_NOTHING_RESOLVER + apiBuiltinEnabled(false) + 超时（虚拟线程 Future）抛 `SYS_005`；模板解析/引用错误抛 `TPL_003`（detail 带模板行号）
  - `record RenderedArtifact(String key, String type, String content, int bytes)`
  - `MarkdownRenderer.render(ArtifactDef def, Map<String,DatasetResult> datasets, Map<String,String> params, int timeoutSec) -> RenderedArtifact`——model 注入：每个 dataset key → `{rows: List<Map> (≤5000), columns, totalRows, truncated}`、`params`、`bizDate`、`runDate`；内置函数 `fmtNumber/fmtPercent/fmtDate/dft` 以 `TemplateMethodModelEx` 注册
  - `MarkdownRenderer.enforceBytes(String content, int maxBytes, String strategy) -> String`——TRUNCATE：按 UTF-8 字节截断（不切断多字节字符）+ 追加 `\n…（内容超长已截断）`；FAIL：抛 `RD_002`

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.render;

import com.hermes.push.common.BizException;
import com.hermes.push.dataset.ColumnMeta;
import com.hermes.push.dataset.DatasetResult;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class MarkdownRendererTest {
  SandboxFreemarker fm = new SandboxFreemarker();
  MarkdownRenderer r = new MarkdownRenderer(fm);

  DatasetResult ds(List<Map<String,Object>> rows) {
    return new DatasetResult() {
      public List<ColumnMeta> columns() { return List.of(new ColumnMeta("amount","DECIMAL"), new ColumnMeta("name","VARCHAR")); }
      public int totalRows() { return rows.size(); }
      public boolean truncated() { return false; }
      public void forEachRow(Consumer<Map<String,Object>> c) { rows.forEach(c); }
      public List<Map<String,Object>> toList(int max) { return rows.stream().limit(max).toList(); }
    };
  }
  Map<String,DatasetResult> model() {
    return Map.of("ds1", ds(List.of(
        Map.of("name","成都","amount", new java.math.BigDecimal("312004")),
        Map.of("name","绵阳","amount", new java.math.BigDecimal("208771")))));
  }
  TaskConfig.ArtifactDef def(String tpl) {
    return new TaskConfig.ArtifactDef("a1","MARKDOWN", tpl, "TRUNCATE", 4096);
  }

  @Test void rendersLoopAndFormatters() {
    var out = r.render(def("**日报**\n<#list ds1.rows as row>- ${row.name}: ${fmtNumber(row.amount)}\n</#list>合计占比 ${fmtPercent(0.1234,2)}"),
        model(), Map.of("bizDate","2026-09-21"), 30);
    assertThat(out.content()).contains("**日报**").contains("成都: 312,004").contains("12.34%");
    assertThat(out.bytes()).isEqualTo(out.content().getBytes(StandardCharsets.UTF_8).length);
  }
  @Test void missingField_tpl003WithLineNumber() {
    assertThatThrownBy(() -> r.render(def("line1\n${ds1.rows[0].noSuchField}"), model(), Map.of(), 30))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-003").hasMessageContaining("line 2");
  }
  @Test void sandboxBlocksNewBuiltin() {
    assertThatThrownBy(() -> r.render(def("<#assign ex='freemarker.template.utility.Execute'?new()>${ex('whoami')}"), model(), Map.of(), 30))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-010");
  }
  @Test void sandboxBlocksApiBuiltin() {
    assertThatThrownBy(() -> r.render(def("${'x'?api.class.forName('java.lang.Runtime')}"), model(), Map.of(), 30))
        .isInstanceOf(BizException.class).hasMessageContaining("TPL-010");
  }
  @Test void renderTimeout_sys005() {
    assertThatThrownBy(() -> r.render(def("<#list 1.. as i>${i}</#list>"), model(), Map.of(), 1))
        .isInstanceOf(BizException.class).hasMessageContaining("SYS-005");
  }
  @Test void truncateRespectsUtf8Bytes() {
    String big = "中文".repeat(3000); // 18000 bytes
    String t = r.enforceBytes(big, 4096, "TRUNCATE");
    assertThat(t.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
    assertThat(t).endsWith("…（内容超长已截断）");
  }
  @Test void failStrategy_rd002() {
    assertThatThrownBy(() -> r.enforceBytes("x".repeat(5000), 4096, "FAIL"))
        .isInstanceOf(BizException.class).hasMessageContaining("RD-002");
  }
}
```

`TemplateScannerTest`：`scan("正常 ${x} 模板")` 通过；含 `?new` / `?api` / `Execute` 各抛 TPL-010 且 detail 含命中词。

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=MarkdownRendererTest+TemplateScannerTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现要点**

```java
@Component
public class SandboxFreemarker {
  private final Configuration cfg;
  public SandboxFreemarker() {
    cfg = new Configuration(Configuration.VERSION_2_3_33);
    cfg.setDefaultEncoding("UTF-8");
    cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER); // 禁 ?new
    cfg.setAPIBuiltinEnabled(false);                                               // 禁 ?api
    cfg.setTemplateLoader(new StringTemplateLoader()); // 不需要文件加载器时用空实现
    cfg.setTemplateExceptionHandler((te, env, out) -> { throw new RuntimeException(te); }); // 快速失败带行号
    cfg.setLogTemplateExceptions(false);
  }
  public String render(String name, String content, Map<String,Object> model, int timeoutSec) {
    Template t;
    try { t = new Template(name, new StringReader(content), cfg); }
    catch (Exception e) { throw new BizException(ErrorCode.TPL_003, "模板解析失败: " + e.getMessage()); }
    StringWriter out = new StringWriter();
    try {
      Future<?> f = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        try { t.process(model, out); } catch (Exception e) { throw new RuntimeException(e); }
      });
      f.get(timeoutSec, TimeUnit.SECONDS);
    } catch (TimeoutException e) { throw new BizException(ErrorCode.SYS_005, "模板渲染超时(" + timeoutSec + "s)"); }
    catch (ExecutionException e) {
      Throwable c = e.getCause() instanceof RuntimeException re && re.getCause() != null ? re.getCause() : e.getCause();
      if (c instanceof freemarker.core.InvalidReferenceException ire)
        throw new BizException(ErrorCode.TPL_003, "引用不存在的字段: " + ire.getBlamedExpressionString() + " (line " + ire.getLineNumber() + ")");
      throw new BizException(ErrorCode.TPL_003, String.valueOf(c.getMessage()));
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BizException(ErrorCode.SYS_005, "渲染被中断"); }
    return out.toString();
  }
}
```

注意：`TemplateScanner.scan` 在 `MarkdownRenderer.render` 入口先调用（双保险：Task 12 保存时扫一次，渲染时再扫一次，防绕过——评审修订 M18）。fmt 函数用 `SimpleHash` 放入共享 model：`fmtNumber`→`DecimalFormat("#,##0.##")`；`fmtPercent(x,d)`→`x*100` 保留 d 位加 `%`；`fmtDate(iso,pattern)`；`dft(v,def)`→ v 为 null/空时取 def。`enforceBytes` 截断算法：从 maxBytes-后缀字节数 处向前找不切断 UTF-8 多字节序列的边界（`Character.isHighSurrogate` 检查或按 `new String(bytes,0,cut,UTF_8)` 重编码长度回退）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=MarkdownRendererTest+TemplateScannerTest"`
Expected: 全部 PASS（若 `?api` 用例因 ALLOWS_NOTHING 先拦截报 TPL-010 以外的码，以扫描器先行为准调整断言前先确认扫描器命中 `?api` 字面量）

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(render): Freemarker沙箱(禁?new/?api+静态扫描+超时)+Markdown渲染与UTF-8截断(FR-RD-02/10)"
```

---

### Task 15: 企微机器人渠道（白名单 + 限流 + 幂等 + 错误分类）（FR-CH-02/04/05/06）

**Files:**
- Create: `channel/PushChannel.java`、`channel/PushMessage.java`、`channel/PushResult.java`、`channel/WecomBotChannel.java`、`channel/WhitelistService.java`、`channel/RateLimiterFactory.java`、`exec/ExecPushRepository.java`、`exec/TaskExecPush.java`
- Test: `channel/WecomBotChannelTest.java`（WireMock）、`channel/WhitelistServiceTest.java`

**Interfaces:**
- Consumes: Redisson（限流）、`hp_whitelist`/`hp_task_exec_push` 表、`ErrorCode.PUSH_011/PUSH_012/SYS_002`
- Produces（Task 17 依赖，签名冻结）:
  - `record PushMessage(String msgType, String content, List<String> mentionedList)`（M1 仅 text/markdown）
  - `record PushResult(boolean success, boolean retryable, String errorCode, String errorMsg)`
  - `interface PushChannel { String type(); PushResult send(String webhookUrl, PushMessage msg, int rateLimitPerMin, int queueWaitTimeoutSec); }`
  - `WecomBotChannel implements PushChannel`，`type()="WEWORK_BOT"`
  - `WhitelistService.assertWebhookAllowed(String url)`——host 不在 `hp_whitelist(WEBHOOK_HOST)` 抛 `SYS_002`；`WhitelistService.isAllowed(String host) -> boolean`
  - `interface RateLimiterFactory { boolean acquire(String webhookUrl, int limitPerMin, int waitTimeoutSec); void evict(String webhookUrl); }`（false=等待超时）——**双实现按 `hermes.rate-limiter` 切换**：`LocalRateLimiterFactory`（默认，`@ConditionalOnProperty(name="hermes.rate-limiter", havingValue="local", matchIfMissing=true)`，单机内存令牌桶，key=sha256(url)）；`RedissonRateLimiterFactory`（`havingValue="redis"`，`RRateLimiter` key=`hermes:rl:`+sha256(url)，`trySetRate(OVERALL, limitPerMin, 60, SECONDS)`）。生产多节点部署必须配 redis（部署清单项）；本环境决议（无 Docker/Redis）下 M1 全程用 local，redis 实现的验证放到有 Redis 的部署联调阶段
  - `ExecPushRepository.markSuccess/markFailed/existsSuccess(long execId, long channelId, String artifactKey, String msgType)`——四元组幂等（评审修订 B2）
- 企微响应分类：`errcode=0` 成功；`45009`（接口调用超频）与 HTTP 5xx/超时 → retryable `PUSH-011`；`93000/40001`（invalid webhook/secret）→ 不可重试 `PUSH-012`；其余 errcode → 不可重试 `PUSH-012`（detail 带 errcode+errmsg，实现时对照官方文档补全映射表常量 `WecomErrCodes`）

- [ ] **Step 1: 写失败测试（WireMock 起本地 HTTP，把 webhook 指到 localhost 并临时加白名单）**

```java
package com.hermes.push.channel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.exec.ExecPushRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class WecomBotChannelTest extends AbstractIntegrationTest {
  static WireMockServer wm;
  @Autowired WecomBotChannel channel; @Autowired WhitelistService whitelist;
  @Autowired ExecPushRepository pushRepo; @Autowired JdbcTemplate jdbc;

  @BeforeAll static void startWm() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
  @AfterAll static void stopWm() { wm.stop(); }
  @BeforeEach void reset() { wm.resetAll(); jdbc.update("DELETE FROM hp_whitelist WHERE value='localhost'");
    jdbc.update("INSERT INTO hp_whitelist(type,value,created_by) VALUES('WEBHOOK_HOST','localhost','test')"); }

  String url() { return "http://localhost:" + wm.port() + "/cgi-bin/webhook/send?key=test"; }
  PushMessage md() { return new PushMessage("markdown", "**日报** 内容", List.of()); }

  @Test void successPath() {
    wm.stubFor(post(urlPathEqualTo("/cgi-bin/webhook/send")).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    PushResult r = channel.send(url(), md(), 20, 5);
    assertThat(r.success()).isTrue();
    wm.verify(postRequestedFor(urlPathEqualTo("/cgi-bin/webhook/send"))
        .withRequestBody(matchingJsonPath("$.msgtype", equalTo("markdown"))));
  }
  @Test void invalidWebhook_notRetryable() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}")));
    PushResult r = channel.send(url(), md(), 20, 5);
    assertThat(r.success()).isFalse(); assertThat(r.retryable()).isFalse(); assertThat(r.errorCode()).isEqualTo("PUSH-012");
  }
  @Test void rateLimitedByWecom_retryable() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":45009,\"errmsg\":\"api freq limit\"}")));
    PushResult r = channel.send(url(), md(), 20, 5);
    assertThat(r.success()).isFalse(); assertThat(r.retryable()).isTrue(); assertThat(r.errorCode()).isEqualTo("PUSH-011");
  }
  @Test void serverError_retryable() {
    wm.stubFor(post(anyUrl()).willReturn(serverError()));
    assertThat(channel.send(url(), md(), 20, 5).retryable()).isTrue();
  }
  @Test void whitelistRejectsForeignHost() {
    assertThatThrownBy(() -> whitelist.assertWebhookAllowed("https://evil.example.com/hook"))
        .isInstanceOf(com.hermes.push.common.BizException.class).hasMessageContaining("白名单");
  }
  @Test void localRateLimiterWaitsThenFails() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    assertThat(channel.send(url(), md(), 2, 1).success()).isTrue();
    assertThat(channel.send(url(), md(), 2, 1).success()).isTrue();
    PushResult third = channel.send(url(), md(), 2, 1); // 限流 2/min，等待 1s 超时
    assertThat(third.success()).isFalse(); assertThat(third.errorCode()).isEqualTo("PUSH-011");
  }
  @Test void idempotentSkipOnSecondSuccess() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    pushRepo.markSuccess(9001L, 1L, "a1", "markdown");
    assertThat(pushRepo.existsSuccess(9001L, 1L, "a1", "markdown")).isTrue();
    assertThat(pushRepo.existsSuccess(9001L, 1L, "a1", "text")).isFalse(); // 消息类型维度独立
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=WecomBotChannelTest+WhitelistServiceTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现要点**

`WecomBotChannel.send` 流程（顺序固定）：
1. `whitelist.assertWebhookAllowed(webhookUrl)`（发送时实时校验，评审修订 M9）
2. `rateLimiter.acquire(...)` false → `PushResult(false,true,"PUSH-011","限流等待超时")`
3. RestClient POST JSON（connect/read timeout 10s）；text 消息含 `mentioned_list`（空列表则省略字段）
4. 解析 errcode 按分类表返回 PushResult；网络异常 → retryable PUSH-011

`LocalRateLimiterFactory`：`ConcurrentHashMap<String, Bucket>`；Bucket 为 synchronized 令牌桶——容量与补充速率由首次 acquire 的 `limitPerMin` 决定（每分钟 limit 个令牌，按 `limit/60.0` 每秒补充），`acquire` 轮询等待（50ms 间隔）直至拿到令牌或超过 `waitTimeoutSec` 返回 false；`evict(url)` 移除对应 Bucket（渠道保存钩子调用，保证限流配置变更生效）。
`RedissonRateLimiterFactory`：`ConcurrentHashMap<String, RRateLimiter>` 缓存实例；`trySetRate(OVERALL, limitPerMin, 60, SECONDS)`（不覆盖已有配置）+ `tryAcquire(1, waitTimeoutSec, TimeUnit.SECONDS)`；配置变更经 `evict`（内部 `getRateLimiter(key).delete()` 后移除缓存）。

`ExecPushRepository`：MyBatis-Plus BaseMapper + `existsSuccess` 用 QueryWrapper（exec_id, channel_id, artifact_key, msg_type, status='SUCCESS'）；`markSuccess/markFailed` 用 `INSERT ... ON DUPLICATE KEY UPDATE status=?, error_code=?, error_msg=?, sent_at=NOW(3), retry_count=retry_count+?`（XML 手写）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=WecomBotChannelTest+WhitelistServiceTest"`
Expected: 全部 PASS（限流走 local 内存实现，无需 Redis；`localRateLimiterWaitsThenFails` 用例验证令牌桶等待与超时）

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(channel): 企微机器人推送(白名单双校验/Redisson限流/四元组幂等/错误分类)(FR-CH-02/04/05/06)"
```

---

### Task 16: 渠道管理 API + 白名单管理 + 健康检查（FR-CH-01/03）

**Files:**
- Create: `channel/Channel.java`、`ChannelMapper.java`、`ChannelService.java`、`ChannelController.java`、`WhitelistController.java`、`channel/ChannelVO.java`
- Test: `channel/ChannelServiceTest.java`

**Interfaces:**
- Consumes: `AesGcmCipher`、`WhitelistService`、`WecomBotChannel`、`RateLimiterFactory.evict`
- Produces:
  - `POST/PUT/GET /api/channels`、`POST /api/channels/{id}/status`、`POST /api/channels/{id}/health-check`
  - `GET/POST/DELETE /api/whitelist`（DELETE 前校验：被启用渠道引用的 host 不可删，抛 `SYS_002`）
  - `ChannelVO`：`config_cipher` 解密后**脱敏输出** `webhook` 为 `https://{host}/…{末4位}`；`testFlag` 可编辑
  - `ChannelService.saveChannel(...)`：保存时 `whitelist.assertWebhookAllowed` + `RateLimiterFactory.evict`
  - `ChannelService.getDecryptedWebhook(Long channelId) -> String`（仅服务端内部使用，Task 17 推送用）
  - `healthCheck(id)`：向该渠道发 `[HermesPush] 健康检查 {时间戳}` text，返回 `TestResultVO` 复用 Task 5 的 record

- [ ] **Step 1: 写失败测试**

```java
package com.hermes.push.channel;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class ChannelServiceTest extends AbstractIntegrationTest {
  @Autowired ChannelService svc; @Autowired JdbcTemplate jdbc;

  @Test void saveRejectsNonWhitelistedWebhook() {
    assertThatThrownBy(() -> svc.save("外部群", "WEWORK_BOT", "{\"webhook\":\"https://evil.example.com/x?key=abcd1234\"}", 20, 300, false, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("白名单");
  }
  @Test void voMasksWebhook() {
    Long id = svc.save("一大群", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=SECRET9999\"}", 20, 300, false, "admin");
    ChannelVO vo = svc.list().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    assertThat(vo.webhookMasked()).contains("qyapi.weixin.qq.com").contains("9999").doesNotContain("SECRET");
    assertThat(svc.getDecryptedWebhook(id)).contains("SECRET9999");
  }
  @Test void whitelistEntryReferencedCannotBeDeleted() {
    svc.save("二大群", "WEWORK_BOT", "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k2\"}", 20, 300, false, "admin");
    Long wlId = jdbc.queryForObject("SELECT id FROM hp_whitelist WHERE value='qyapi.weixin.qq.com'", Long.class);
    assertThatThrownBy(() -> svc.deleteWhitelist(wlId)).isInstanceOf(BizException.class).hasMessageContaining("引用");
  }
}
```

- [ ] **Step 2: 跑测试确认失败** → Run 同上模式，Expected: COMPILATION ERROR
- [ ] **Step 3: 实现**（样板 CRUD 参照 Task 4 模式；`config_cipher` 存 `AesGcmCipher.encrypt(configJson)`；脱敏函数 `mask(url)`：`URI.create(url).getHost()` + path 省略 + query 末 4 位；`deleteWhitelist`：SELECT 所有启用渠道解密 webhook 比对 host）
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(channel): 渠道CRUD+脱敏输出+白名单管理(删除引用校验)+健康检查(FR-CH-01/03/04)"
```

---

### Task 17: Worker 执行流水线 + 手动触发/试运行/测试发送 API（核心编排）

**Files:**
- Create: `exec/ExecPipeline.java`、`exec/ExecWorker.java`、`exec/ErrorClassifier.java`、`exec/StageCosts.java`、`task/TaskExecController.java`
- Test: `exec/ExecPipelineIntegrationTest.java`

**Interfaces:**
- Consumes: 任务 8/9/11/12/14/15/16 的全部 Produces
- Produces:
  - `ExecPipeline.run(TaskExec exec)`：完整 REPORT/TRIAL/TEST 流水线（下述 9 步）
  - `ExecWorker`：`ApplicationReadyEvent` 启动 `hermes.worker.concurrency` 个虚拟线程循环 `claim→run`；每执行一个心跳定时（30s），`heartbeat()==false` 时置中断标志，流水线在阶段边界检查并放弃（评审修订 B3）；`concurrency=0` 时不启动（测试基类依赖此开关）
  - `ErrorClassifier.isRetryable(String errorCode)`：`{DS-002, PUSH-011, SYS-005, NODE_LOST}` 为 true，其余 false
  - `StageCosts`：`{long queueMs, long queryMs, long renderMs, long pushMs}` Jackson 序列化进 `stage_costs_json`；queueMs = fire_time→claim 的数据库时间差
  - REST：`POST /api/tasks/{id}/trial`（体 `{params:{}}` 可选）、`POST /api/tasks/{id}/trigger`（体 `{bizDate?, params?, idempotencyKey?}`）、`POST /api/tasks/{id}/test-send`（体 `{channelId}`，该渠道必须 `test_flag=1`）
- 流水线 9 步（顺序冻结）：
  1. `loadEffectiveConfig`，校验 `task_version_id` 与执行记录一致（TRIAL/TEST 用 current 版本）
  2. 解析 bizDate（exec.biz_date）与 runtime params（exec.params_json）
  3. 逐 dataset：`validate`（失败记 VALIDATION_FAILED 审计）→ `prepare` → `QueryEngine.execute`（scene=EXEC 或 TRIAL 审计）→ `Map<String,DatasetResult>`
  4. 逐 artifact（M1 均 MARKDOWN，拓扑序即声明序）：`MarkdownRenderer.render` + `enforceBytes(def.maxBytes, def.overflowStrategy)` → 写 `hp_task_exec_artifact`（content 内联）
  5. TRIAL：跳过推送，`finish(SUCCESS)`，artifact content 即预览数据
  6. 逐 binding 逐 artifactKey：`ExecPushRepository.existsSuccess` → SKIPPED；否则取渠道解密 webhook → `PushChannel.send`（TEST 触发时 content 加前缀 `[测试] `）→ markSuccess/markFailed
  7. 汇总：全成 SUCCESS；有成有败 PARTIAL_SUCCESS；全败 FAILED
  8. FAILED 且（ErrorClassifier 或任一 PushResult.retryable）且 `retry_count < max_retry` → `retryWait(backoff)`，backoff 序列 `[30,120,480]` 秒按 retry_count 取
  9. `finish(status, stageCosts, rowsTotal=sum(dataset.totalRows), errorCode/msg)`

- [ ] **Step 1: 写失败集成测试（WireMock 企微 + Testcontainers MySQL/Redis，全链路）**

```java
package com.hermes.push.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.channel.ChannelService;
import com.hermes.push.datasource.DatasourceSaveRequest;
import com.hermes.push.datasource.DatasourceService;
import com.hermes.push.task.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class ExecPipelineIntegrationTest extends AbstractIntegrationTest {
  static WireMockServer wm;
  @Autowired ExecPipeline pipeline; @Autowired ExecQueueRepository queue;
  @Autowired TaskService tasks; @Autowired DatasourceService dsSvc; @Autowired ChannelService chSvc;
  @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;

  @BeforeAll static void startWm() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
  @AfterAll static void stopWm() { wm.stop(); }
  @BeforeEach void reset() { wm.resetAll(); wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    jdbc.update("DELETE FROM hp_whitelist WHERE value='localhost'");
    jdbc.update("INSERT INTO hp_whitelist(type,value,created_by) VALUES('WEBHOOK_HOST','localhost','test')"); }

  record Ctx(Long dsId, Long channelId, Long taskId, Long versionId) {}
  Ctx setup(String sql, String template) throws Exception {
    jdbc.execute("CREATE TABLE IF NOT EXISTS sales(dt DATE, branch VARCHAR(32), amount DECIMAL(12,2))");
    jdbc.execute("INSERT IGNORE INTO sales VALUES ('2026-09-21','成都',312004.00),('2026-09-21','绵阳',208771.00)");
    Long dsId = dsSvc.save(new DatasourceSaveRequest("e2e-" + System.nanoTime(), "MYSQL", TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, true, null, 30, null, null), "admin");
    Long chId = chSvc.save("测试群" + System.nanoTime(), "WEWORK_BOT",
        "{\"webhook\":\"http://localhost:" + wm.port() + "/cgi-bin/webhook/send?key=e2e\"}", 20, 5, true, "admin");
    var cfg = new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", dsId, sql, List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", template, "TRUNCATE", 4096)),
      List.of(new TaskConfig.ChannelBinding(chId, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef("0 0 9 * * ?", -1, 10, 3, false));
    Long taskId = tasks.create("E2E日报", "e2e-" + System.nanoTime(), cfg, null, "admin");
    return new Ctx(dsId, chId, taskId, tasks.loadEffectiveConfig(taskId).taskVersionId());
  }
  Long enqueue(Ctx c, TriggerType trig, String paramsJson, LocalDate bizDate) {
    return queue.insertPending(c.taskId, c.versionId, trig, 70,
        java.time.LocalDateTime.now(), bizDate, paramsJson, "k-" + System.nanoTime());
  }
  void runOne() {
    Long id = queue.claim("node-test").orElseThrow();
    pipeline.run(queue.getById(id));
  }

  @Test void trialRendersWithoutPush() throws Exception {
    Ctx c = setup("SELECT branch, amount FROM sales WHERE dt = #{bizDate}",
        "**日报 ${bizDate}**\n<#list ds1.rows as r>- ${r.branch}: ${fmtNumber(r.amount)}\n</#list>");
    Long execId = enqueue(c, TriggerType.TRIAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    TaskExec e = queue.getById(execId);
    assertThat(e.getStatus()).isEqualTo("SUCCESS");
    String content = jdbc.queryForObject("SELECT content FROM hp_task_exec_artifact WHERE exec_id=?", String.class, execId);
    assertThat(content).contains("成都: 312,004");
    wm.verify(0, postRequestedFor(anyUrl())); // 试运行不推送
    Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='TRIAL'", Integer.class);
    assertThat(audits).isGreaterThanOrEqualTo(1);
  }
  @Test void manualTriggerPushesToWecom() throws Exception {
    Ctx c = setup("SELECT branch, amount FROM sales WHERE dt = #{bizDate}",
        "<#list ds1.rows as r>${r.branch} ${fmtNumber(r.amount)}\n</#list>");
    Long execId = enqueue(c, TriggerType.MANUAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    assertThat(queue.getById(execId).getStatus()).isEqualTo("SUCCESS");
    wm.verify(1, postRequestedFor(urlPathEqualTo("/cgi-bin/webhook/send"))
        .withRequestBody(matchingJsonPath("$.msgtype", equalTo("markdown"))
        .and(matchingJsonPath("$.markdown.content", containing("成都 312,004")))));
    Integer pushes = jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec_push WHERE exec_id=? AND status='SUCCESS'", Integer.class, execId);
    assertThat(pushes).isEqualTo(1);
  }
  @Test void retryReusesExecAndSkipsSucceededPush() throws Exception {
    Ctx c = setup("SELECT branch FROM sales WHERE dt = #{bizDate}", "<#list ds1.rows as r>${r.branch}\n</#list>");
    Long execId = enqueue(c, TriggerType.MANUAL, "{}", LocalDate.of(2026, 9, 21));
    wm.resetAll(); wm.stubFor(post(anyUrl()).willReturn(serverError())); // 第一次推送 5xx
    runOne();
    TaskExec e = queue.getById(execId);
    assertThat(e.getStatus()).isEqualTo("RETRY_WAIT");
    assertThat(e.getRetryCount()).isEqualTo(1);
    assertThat(e.getErrorCode()).isEqualTo("PUSH-011");
    // 恢复 stub，模拟巡检 promote 后重跑同一 execId
    wm.resetAll(); wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    jdbc.update("UPDATE hp_task_exec SET status='PENDING', node_id=NULL WHERE id=?", execId);
    runOne();
    assertThat(queue.getById(execId).getStatus()).isEqualTo("SUCCESS");
    assertThat(queue.getById(execId).getId()).isEqualTo(execId); // 同一执行记录（评审修订 B1）
    wm.verify(1, postRequestedFor(anyUrl())); // 仅失败渠道重发一次
  }
  @Test void badSqlFailsNonRetryableWithAudit() throws Exception {
    Ctx c = setup("SELCT bad syntax", "x");
    Long execId = enqueue(c, TriggerType.MANUAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    TaskExec e = queue.getById(execId);
    assertThat(e.getStatus()).isEqualTo("FAILED");
    assertThat(e.getErrorCode()).startsWith("SQL-");
    assertThat(e.getRetryCount()).isZero(); // 不可重试
    Integer vf = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='VALIDATION_FAILED'", Integer.class);
    assertThat(vf).isGreaterThanOrEqualTo(1);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ExecPipelineIntegrationTest"`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 ExecPipeline（骨架全文，阶段方法内部调用前序任务产物）**

```java
@Service @RequiredArgsConstructor @Slf4j
public class ExecPipeline {
  private final TaskService tasks; private final DatasourceService dsSvc;
  private final DruidSqlValidator validator; private final ParamResolver params;
  private final QueryEngine engine; private final SqlAuditService audit;
  private final MarkdownRenderer renderer; private final ExecQueueRepository queue;
  private final ExecPushRepository pushRepo; private final ExecArtifactRepository artifactRepo;
  private final ChannelService channels; private final PushChannelRegistry registry; // Map<type,PushChannel>
  private final JdbcTemplate jdbc; private final ErrorClassifier classifier;

  public void run(TaskExec exec) {
    StageCosts sc = new StageCosts();
    long t0 = System.nanoTime();
    sc.queueMs = jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, fire_time, NOW(3))/1000 FROM hp_task_exec WHERE id=?", Long.class, exec.getId());
    try {
      var eff = tasks.loadEffectiveConfig(exec.getTaskId());
      LocalDate runDate = jdbc.queryForObject("SELECT CURDATE()", LocalDate.class);
      Map<String,String> runtime = readParams(exec.getParamsJson());
      // bizDate 覆盖语义（FR-TSK-04）：手动触发/补数传入的 biz_date 折算为等效偏移，
      // 使 ParamResolver 的 #{bizDate} 解析到覆盖值；未传则用任务配置的默认偏移
      int offset = offset(eff);
      if (exec.getBizDate() != null) offset = (int) (exec.getBizDate().toEpochDay() - runDate.toEpochDay());
      // 阶段1：查询
      long q0 = System.nanoTime();
      Map<String, DatasetResult> results = new LinkedHashMap<>();
      for (var ds : eff.config().datasets()) {
        var datasource = dsSvc.getEnabled(ds.datasourceId());
        try { validator.validate(ds.sql(), datasource.getType()); }
        catch (BizException e) { audit.recordValidationFailed(ds.datasourceId(), ds.sql(), "exec:" + exec.getId(), null, e.getErrorCode().getCode() + " " + e.getDetail()); throw e; }
        PreparedSql p = params.prepare(ds.sql(), ds.params(), Map.of(), runtime, runDate, offset);
        DatasetResult r = engine.execute(datasource, p, 0, exec.getId());
        audit.record(exec.getTriggerType() == TriggerType.TRIAL ? SqlAuditScene.TRIAL : SqlAuditScene.EXEC,
            ds.datasourceId(), ds.sql(), p.auditParams(), r.totalRows(), null, "exec:" + exec.getId(), exec.getId(), null);
        results.put(ds.key(), r);
      }
      sc.queryMs = (System.nanoTime() - q0) / 1_000_000;
      // 阶段2：渲染
      long r0 = System.nanoTime();
      Map<String, RenderedArtifact> rendered = new LinkedHashMap<>();
      for (var a : eff.config().artifacts()) {
        String content = renderer.render(a, results, runtime, renderTimeoutSec()).content();
        content = renderer.enforceBytes(content, a.maxBytes() == null ? 4096 : a.maxBytes(), a.overflowStrategy());
        rendered.put(a.key(), new RenderedArtifact(a.key(), a.type(), content, content.getBytes(StandardCharsets.UTF_8).length));
        artifactRepo.upsertInline(exec.getId(), a.key(), a.type(), content, rowsOf(results));
      }
      sc.renderMs = (System.nanoTime() - r0) / 1_000_000;
      // 阶段3：推送（TRIAL 跳过）
      if (exec.getTriggerType() != TriggerType.TRIAL) {
        long p0 = System.nanoTime();
        boolean anySuccess = false, anyFail = false; boolean anyRetryable = false; String firstErr = null, firstCode = null;
        for (var b : eff.config().channelBindings()) {
          Long testChannelId = runtime.containsKey("__testChannelId") ? Long.valueOf(runtime.get("__testChannelId")) : null;
          if (exec.getTriggerType() == TriggerType.TEST && !b.channelId().equals(testChannelId)) continue;
          for (String ak : b.artifactKeys()) {
            if (pushRepo.existsSuccess(exec.getId(), b.channelId(), ak, b.msgType())) { anySuccess = true; continue; }
            var ra = rendered.get(ak);
            if (ra == null) { anyFail = true; firstCode = "SYS-003"; firstErr = "产物 " + ak + " 渲染失败，跳过"; continue; }
            String content = exec.getTriggerType() == TriggerType.TEST ? "[测试] " + ra.content() : ra.content();
            var ch = channels.getDecrypted(b.channelId());
            PushResult pr = registry.get(ch.type()).send(ch.webhook(), new PushMessage(b.msgType(), content, List.of()), ch.rateLimit(), ch.waitTimeout());
            if (pr.success()) { pushRepo.markSuccess(exec.getId(), b.channelId(), ak, b.msgType()); anySuccess = true; }
            else { pushRepo.markFailed(exec.getId(), b.channelId(), ak, b.msgType(), pr.errorCode(), pr.errorMsg());
                   anyFail = true; anyRetryable |= pr.retryable();
                   if (firstCode == null) { firstCode = pr.errorCode(); firstErr = pr.errorMsg(); } }
          }
        }
        sc.pushMs = (System.nanoTime() - p0) / 1_000_000;
        if (anyFail && anySuccess) { finishOrRetry(exec, ExecStatus.PARTIAL_SUCCESS, sc, results, firstCode, firstErr, anyRetryable); return; }
        if (anyFail) { finishOrRetry(exec, ExecStatus.FAILED, sc, results, firstCode, firstErr, anyRetryable); return; }
      }
      finishOrRetry(exec, ExecStatus.SUCCESS, sc, results, null, null, false);
    } catch (BizException e) {
      finishOrRetry(exec, ExecStatus.FAILED, sc, Map.of(), e.getErrorCode().getCode(), e.getDetail(), classifier.isRetryable(e.getErrorCode().getCode()));
    } catch (Exception e) {
      log.error("exec {} unexpected", exec.getId(), e);
      finishOrRetry(exec, ExecStatus.FAILED, sc, Map.of(), "SYS-003", e.getMessage(), false);
    }
  }
  private void finishOrRetry(TaskExec exec, ExecStatus st, StageCosts sc, Map<String,DatasetResult> results,
                             String code, String msg, boolean retryable) {
    int rows = results.values().stream().mapToInt(DatasetResult::totalRows).sum();
    if (st != ExecStatus.SUCCESS && retryable && exec.getRetryCount() < exec.getMaxRetry()) {
      int backoff = switch (exec.getRetryCount()) { case 0 -> 30; case 1 -> 120; default -> 480; };
      queue.retryWait(exec.getId(), code, msg, backoff);
      return;
    }
    queue.finish(exec.getId(), st, sc.toJson(), rows, code, msg);
  }
  // offset/readParams/renderTimeoutSec/rowsOf 为私有辅助
}
```

`ExecWorker`：`@EventListener(ApplicationReadyEvent.class)` 启动 N 个线程：`while(running){ queue.claim(nodeId).ifPresentOrElse(id -> { var exec=queue.getById(id); startHeartbeat(exec); try{ pipeline.run(exec);} finally {stopHeartbeat();} }, () -> sleep(pollInterval)); }`。心跳：`ScheduledExecutorService` 每 30s `queue.heartbeat(execId,nodeId)`，false 时 `Thread.currentThread().interrupt()` 并记录（流水线阶段边界 catch InterruptedException → 直接 return 不 finish）。

`TaskExecController`：trial → `insertPending(TRIAL, 70, bizDate=CURDATE()+offset, params)`；trigger → 校验 bizDate 格式 `yyyy-MM-dd`（非法抛 SQL-004 detail），`insertPending(MANUAL, 60, idemKey=请求或生成)`；test-send → 校验渠道 `test_flag=1`，params 加 `__testChannelId`，`insertPending(TEST, 70)`。三者都立即返回 `{execId}`（异步执行），前端轮询执行详情。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ExecPipelineIntegrationTest"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```powershell
git add hermes-server/src
git commit -m "feat(exec): Worker流水线(查询→渲染→推送→重试复用原执行)+trial/trigger/test-send API"
```

---

### Task 18: 重试提升 + 执行超时扫描（FR-EXE-04/03）

**Files:**
- Create: `exec/ExecMaintenance.java`；Modify: `ExecQueueMapper.xml`（加 `timeoutRunning` 语句）
- Test: `exec/ExecMaintenanceTest.java`

**Interfaces:**
- Produces: `ExecMaintenance`：`@Scheduled(fixedDelay=10_000)` 调 `promoteDueRetries()`；`@Scheduled(fixedDelay=30_000)` 执行超时扫描：`UPDATE hp_task_exec SET status='RETRY_WAIT', error_code='NODE_LOST', next_retry_at=NOW(3)+INTERVAL 30 SECOND, retry_count=retry_count+1 WHERE status='RUNNING' AND heartbeat_at < NOW(3)-INTERVAL 120 SECOND AND retry_count<max_retry`；超限的直接 `FAILED`。全部条件更新（评审修订 B3）。M1 同时覆盖"进程重启遗留 RUNNING"场景。

- [ ] **Step 1: 失败测试**：插入一条 RUNNING、heartbeat_at=NOW()-3分钟、retry_count=0 → 调维护方法一次 → 状态变 RETRY_WAIT；再插一条 retry_count=max_retry → 变 FAILED。promote：RETRY_WAIT next_retry_at=NOW()-1s → 变 PENDING。
- [ ] **Step 2: 跑测试确认失败** → Run: `.\mvnw.cmd -pl hermes-server test "-Dtest=ExecMaintenanceTest"`
- [ ] **Step 3: 实现**（两条 XML UPDATE + `@Scheduled` 方法，`hermes.worker.concurrency=0` 时扫描仍启用——用独立开关 `hermes.maintenance.enabled` 默认 true，测试基类保持 true）
- [ ] **Step 4: 通过** → Run 同上，Expected: 3 tests PASS
- [ ] **Step 5: Commit** `git commit -m "feat(exec): 重试到期提升+RUNNING超时/失联恢复(条件更新防竞态)(FR-EXE-03/04/07简化版)"`

---

### Task 19: 执行日志 API（FR-EXE-05）

**Files:**
- Create: `exec/ExecLogController.java`、`exec/ExecLogService.java`、`exec/vo/ExecListVO.java`、`exec/vo/ExecDetailVO.java`
- Test: `exec/ExecLogControllerTest.java`（MockMvc）

**Interfaces:**
- Produces:
  - `GET /api/execs?taskId=&status=&triggerType=&from=&to=&page=&size=` → 分页 `ExecListVO(id,taskId,taskName,triggerType,status,fireTime,bizDate,rowsTotal,costMs,errorCode,ver,nodeId)`
  - `GET /api/execs/{id}` → `ExecDetailVO(exec 字段全量, List<ArtifactVO> artifacts, List<PushVO> pushes, StageCosts stageCosts, String sqlText仅当有权限)`——M1 单管理员不做 VIEWER 过滤，字段保留 `sqlVisible=true` 常量，M2 RBAC 接管
  - taskName 联查 `hp_task`；分页用 MyBatis-Plus `Page`
- [ ] **Step 1: 失败测试**：造 2 条 exec（1 SUCCESS 带 artifact/push 子记录、1 FAILED 带 error_code）→ 列表按 status 过滤断言 → 详情断言 artifacts/pushes/stageCosts 反序列化非空。
- [ ] **Step 2-5**: 常规（Run 模式同前；Commit: `feat(exec): 执行日志列表与详情API(FR-EXE-05)`）

---

### Task 20: 单管理员登录（FR-SEC-02 M1 形态）

**Files:**
- Create: `security/AuthController.java`、`security/SaTokenConfigure.java`、`security/AdminUserInitializer.java`、`security/User.java`、`UserMapper`
- Modify: `hermes-server/pom.xml`（加 `org.springframework.security:spring-security-crypto`，仅用 BCrypt，不引入完整 security starter）、`application.yaml`（`hermes.auth.enabled: true`、`sa-token.token-name: Authorization`、`sa-token.timeout: 28800`）
- Modify: `task/CurrentUserHolder.java`（改为 `StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : "admin"`）
- Test: `security/AuthControllerTest.java`

**Interfaces:**
- Produces: `POST /api/auth/login {username,password}` → `{token}`；`POST /api/auth/logout`；`GET /api/auth/me` → `{username, role}`。`AdminUserInitializer`：启动时 `hp_user` 为空则创建 `admin`，密码取环境变量 `HP_ADMIN_PASSWORD`（缺省 `hermes@2026` 且打 WARN 提示立即修改），BCrypt 存 hash。`SaTokenConfigure`：`hermes.auth.enabled=true` 时拦截 `/api/**`，放行 `/api/auth/login`；`enabled=false`（集成测试基类设置）不注册拦截器。
- [ ] **Step 1: 失败测试**（`@TestPropertySource(properties="hermes.auth.enabled=true")`）：未带 token 访问 `/api/datasources` → 401 语义（Sa-Token 未登录异常经 GlobalExceptionHandler 映射 code=401）；login 成功 → 带 token 访问 200；错误密码 → code=401 detail"用户名或密码错误"（不区分存在性，防枚举）。
- [ ] **Step 2-5**: 常规（Commit: `feat(security): Sa-Token单管理员登录+BCrypt+初始化器(FR-SEC-02 M1形态)`）

---

### Task 21: 前端骨架 + 登录页

**Files:**
- Create: `frontend/package.json`、`vite.config.js`、`index.html`、`src/main.js`、`src/api/http.js`、`src/router.js`、`src/store.js`、`src/styles/tokens.css`、`src/views/LoginView.vue`
- 依赖版本：`vue@3.5`、`vue-router@4.4`、`element-plus@2.8`、`axios@1.7`、`vite@5.4`、`monaco-editor@0.52` + `vite-plugin-monaco-editor@1.1`

**Interfaces:**
- Produces: `http.js` 导出 `get/post/put/del`，拦截器：`code!=0` 时 `ElMessage.error(data.errorCode + ' ' + message + '｜' + data.suggestion)` 并 reject（错误码字典的用户可读呈现，FR-OPS-02 的 M1 部分）；401 跳登录。`store.js`：`reactive({token, username, mode:'simple'})` 持久化 localStorage。`tokens.css`：从原型 `docs/prototype/index.html` 的 `:root` 变量表整体拷贝（品牌色 #2a78d6、状态色、表面色），保证前端与已定版原型视觉一致。路由守卫：无 token 且非 /login → 重定向。
- vite.config：`server.proxy['/api'] = 'http://localhost:8080'`；build 输出 `../hermes-server/src/main/resources/static`（Spring Boot 直接托管，M1 免 nginx）。

- [ ] **Step 1: 初始化工程**：`npm create vite@5 frontend -- --template vue`，装依赖，替换生成文件为上述清单；LoginView 按原型登录卡样式（角色切换演示去掉，M1 单管理员）。
- [ ] **Step 2: 构建验证**：Run: `cd frontend; npm run build`　Expected: 构建成功且 `hermes-server/src/main/resources/static/index.html` 生成
- [ ] **Step 3: 联调冒烟**：启动后端：本地 MySQL 建运行库（`mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS hermes DEFAULT CHARSET utf8mb4"`，Flyway 自动建表；与测试库 hermes_test 分离）；无需 Redis（`hermes.rate-limiter` 默认 local）；PowerShell 设 `$env:HP_MASTER_KEY`（用 Task 2 测试密钥）与 `$env:HP_DB_PASSWORD='123456'` 后 `.\mvnw.cmd -pl hermes-server spring-boot:run`，浏览器登录成功进入首页。
- [ ] **Step 4: Commit** `git add frontend hermes-server/src/main/resources/static; git commit -m "feat(frontend): Vite+Vue3+ElementPlus骨架、axios错误码提示、登录页、设计令牌同步原型"`

---

### Task 22: 前端 数据源页 + 渠道页

**Files:**
- Create: `src/views/DatasourceView.vue`、`src/views/ChannelView.vue`、`src/components/PageHead.vue`（页头带组件，样式取原型 `.page-head`）
- 消费端点与字段表（与后端 VO 一一对应，禁止自造字段）：
  - 数据源：`GET/POST/PUT /api/datasources`、`POST /{id}/test`、`POST /{id}/status?enable=`；表单字段 name/type/jdbcUrl/username/password(编辑留空=不改)/roConfirmed(必勾)/maxRows/queryTimeoutSec；测试按钮展示 `dbVersion/costMs` 或 `errorCode+userMessage`
  - 渠道：`GET/POST /api/channels`、`POST /{id}/health-check`、白名单 `GET/POST/DELETE /api/whitelist`；webhook 输入后**前端先校验 host 是否在白名单下拉中**（数据来自 GET /api/whitelist?type=WEBHOOK_HOST），不在则提示联系管理员；列表展示 `webhookMasked`
- [ ] **Step 1-4**: 实现两页（表格+抽屉表单，交互模式与原型一致）→ `npm run build` → 手工冒烟清单（新增数据源带只读勾选拦截、测试连接成功/失败各一次、渠道健康检查、白名单删除被引用项报错提示）→ Commit `feat(frontend): 数据源与渠道管理页(只读确认/连通测试/健康检查/白名单)`

---

### Task 23: 前端 简单模式建任务（场景模板 + 3 步向导）+ 任务列表（FR-OPS-05/FR-TSK-02）

**Files:**
- Create: `src/views/HomeView.vue`、`src/views/ScenarioView.vue`、`src/views/Wizard3View.vue`、`src/views/TaskListView.vue`、`src/views/SqlEditor.vue`（monaco 封装）
- 行为规格（对应原型已定版交互，逐条实现）：
  - Scenario：4 张场景卡（M1 仅"日报发群""明细附件(占位提示 M2)"可进向导；监控/按人分发卡片置灰标注里程碑）
  - Wizard3 三步：①数据源下拉 + Monaco SQL 编辑器 + "先看看查出来的数据"（调 preview，200 行表格展示）②推送内容勾选（M1 仅摘要卡片可用，表格图片/Excel 置灰标注 M2/M3）③渠道勾选（仅 WEWORK_BOT）+ 时间快捷 pills（映射 cron：每天09:00=`0 0 9 * * ?` 等 4 个 + 自定义 cron 输入带 preview 接口校验与未来5次展示）
  - 完成页：人话配置摘要（拼字符串）+ "发到测试群"（POST test-send，需先存在 test_flag 渠道，否则提示去渠道页创建）+ "试运行"（POST trial）+ "上线"（publish；后端试运行门槛未过时按错误码提示）
  - 任务列表：状态徽章、下次触发、操作列（详情/手动触发弹窗带 bizDate 覆盖与格式校验/上线下线/删除）；详情抽屉 M1 简化版（基本信息 dl + 版本列表只读）
  - 简单/专家模式切换按钮进顶栏（专家模式 M1 仅展示"五步向导建设中（M2 完整）"占位页，路由存在）
- [ ] **Step 1-4**: 实现 → build → 手工冒烟（走通 场景→3步→试运行→测试发送→上线 全链路，对照 FR-OPS-05 验收第 1 条：全程点击 ≤10 次、无文档术语）→ Commit `feat(frontend): 简单模式(场景模板+3步向导+试运行门槛)+任务列表(FR-OPS-05,FR-TSK-01~04)`

---

### Task 24: 前端 执行记录页 + 今日页

**Files:**
- Create: `src/views/ExecListView.vue`、`src/components/ExecDetailDrawer.vue`
- 行为规格：列表（筛选：任务/状态/触发方式/日期区间；分页）；详情抽屉按原型分节结构（① 概览 dl ② 失败原因卡（errorCode+userMessage+suggestion 来自 API）③ 阶段耗时条（stage_costs_json 渲染，色板用原型验证过的 #898781/#2a78d6/#eb6834/#1baf7a/#eda100）④ 推送内容（content 预览 Markdown 渲染，M1 用 `v-html` 前置 sanitize——引入 `dompurify@3`）⑤ 渠道推送明细）；今日页 = 原型 home 简化版（统计条 + 失败卡 + 接下来触发 + 我的任务），数据源端点 `GET /api/execs/today-summary`（Task 19 补充此端点：今日计数 + 未来1小时触发列表，触发列表由 Quartz `scheduler.getTriggerKeys` + `getNextFireTime` 聚合）
- [ ] **Step 1-4**: 实现 → build → 手工冒烟（失败执行可见错误码建议卡；阶段耗时条渲染）→ Commit `feat(frontend): 执行记录+详情分节抽屉+今日概览页(FR-EXE-05/06,FR-OPS-01)`

---

### Task 25: docker-compose + E2E 验收 + 峰值压测建模

**Files:**
- Create: `deploy/docker-compose.yml`、`deploy/.env.example`、`deploy/Dockerfile`、`docs/m1-acceptance.md`（验收记录模板与步骤）、`scripts/seed-load-test.sql`
- Test: 无新增单测；本任务交付验收证据

**docker-compose.yml（全文）**：

```yaml
services:
  mysql:
    image: mysql:8.0
    environment: { MYSQL_ROOT_PASSWORD: root, MYSQL_DATABASE: hermes, TZ: Asia/Shanghai }
    ports: ["3306:3306"]
    healthcheck: { test: ["CMD", "mysqladmin", "ping", "-proot"], interval: 5s, retries: 20 }
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  hermes:
    build: { context: .., dockerfile: deploy/Dockerfile }
    environment:
      HP_DB_HOST: mysql
      HP_REDIS_HOST: redis
      HP_MASTER_KEY: ${HP_MASTER_KEY}
      HP_ADMIN_PASSWORD: ${HP_ADMIN_PASSWORD}
    ports: ["8080:8080"]
    shm_size: "1gb"          # M3 Chromium 预留，M1 无害
    depends_on:
      mysql: { condition: service_healthy }
      redis: { condition: service_started }
```

`Dockerfile`：两阶段（`maven:3.9-eclipse-temurin-21` 构建 → `eclipse-temurin:21-jre` 运行，COPY static 已在构建内）。`.env.example` 含 `HP_MASTER_KEY=`（注释给出 openssl 生成命令 `openssl rand 32 | base64`）与 `HP_ADMIN_PASSWORD=`。

**E2E 验收步骤（写入 docs/m1-acceptance.md，逐条对应 M1 验收标准；开发机无 Docker，按本地模式执行，compose 文件作为服务器部署交付物保留）**：
1. 本地模式启动：MySQL 本地 `hermes` 库 + `$env:HP_MASTER_KEY`/`$env:HP_DB_PASSWORD` + `spring-boot:run` → 打开 `http://localhost:8080` 登录（有 Docker 的服务器上 `docker compose up -d --build` 等效）
2. 新建数据源指向一个演示库（compose 内加 `seed` 服务或指向宿主机 MySQL），勾选只读确认，测试连接通过
3. 渠道页新建企微机器人渠道：**用真实测试群 webhook**（或 WireMock 演示环境），健康检查通过
4. 简单模式：场景"日报发群"→ 3 步 → 试运行（看到渲染预览）→ 发到测试群（收到 `[测试]` 消息）→ 上线
5. 未试运行门槛验证：另建一个任务不试运行直接上线 → 被拒并提示
6. 手动触发（改 bizDate）→ 执行记录出现 MANUAL 成功记录，群收到消息
7. 失败定位：把 SQL 改成语法错误保存新版本 → 手动触发 → 执行详情出现 SQL-001 错误卡与修复建议
8. 审计验证：`SELECT scene, COUNT(*) FROM hp_sql_audit GROUP BY scene` 出现 PREVIEW/TRIAL/EXEC/VALIDATION_FAILED 四类
9. 幂等验证：对同一执行重跑（把 push 记录保留、exec 置回 PENDING）→ 群不重复收消息，push 记录 status 仍 SUCCESS 且 sent_at 不变

**峰值压测建模（M1 验收第 5 条）**：
- `scripts/seed-load-test.sql`：造 50 个任务 × 同一 cron；用 `INSERT ... SELECT` 直接生成 50 条 PENDING 执行（fire_time=NOW()），跳过 Quartz
- 启动 2 个节点（compose scale=2 或双进程不同 `hermes.node-id`），worker concurrency=4
- 观测 SQL：`SELECT MAX(TIMESTAMPDIFF(SECOND, fire_time, updated_at)) FROM hp_task_exec WHERE ...`（完成 P95/P100）
- 将实测数字与推算模型（50 任务 × 平均查询 2s + 渲染 0.2s + 推送 0.5s / 8 并发 ≈ 17s，无渲染重负载）写入 `docs/m1-acceptance.md` 的压测小节，并给出 M3 转图交付后的复测计划

- [x] **Step 1: 编写 compose/Dockerfile/验收文档/压测脚本**（compose/Dockerfile 为服务器部署交付物；开发机无 Docker 时仅做 YAML 静态检查，无法本机验证的项在验收记录中标注"待服务器环境验证"）
- [x] **Step 2: 本地模式执行 E2E 步骤 1-9 并把结果记入 docs/m1-acceptance.md**
- [x] **Step 3: 执行压测建模，记录数字**
- [x] **Step 4: 全量回归** `.\mvnw.cmd test`（所有任务测试一次跑通）
- [x] **Step 5: Commit** `git add deploy docs scripts; git commit -m "test(m1): docker-compose部署+E2E验收记录+峰值压测建模报告"`

---

## M1 验收标准 → 任务映射

| PRD M1 验收标准 | 覆盖任务 |
|---|---|
| 配一个任务每天定时把昨日汇总发到测试群 | 13(Quartz) + 17(流水线) + 23(向导) + 25(E2E#4) |
| 未试运行任务无法上线 | 12(publish 门槛) + 23(前端提示) + 25(E2E#5) |
| 失败可在日志定位原因 | 3(错误码) + 17(分类) + 19(日志API) + 24(错误卡) + 25(E2E#7) |
| 审计表四类记录可查 | 9(审计) + 10/17(埋点) + 25(E2E#8) |
| 峰值压测建模报告 | 25 |

## 自检记录（writing-plans self-review）

- 规格覆盖：PRD M1 列名的功能全部有任务对应（数据源 FR-DS-01~04→T4/5/8；SQL FR-SQL-01~04→T6/7/8/10；渲染 FR-RD-01/02/10→T14；企微 FR-CH-01~06→T15/16；调度 FR-EXE-01~05/10→T11/13/17/18/19；审计 FR-SEC-01/03→T2/9；登录 FR-SEC-02(M1)→T20；向导 FR-TSK-01~04→T12/17/23；FR-OPS-05→T23；错误码 FR-OPS-02(M1 机制)→T3/21）。M1 明确不含：模板库/Excel/版本diff页/存储抽象（M2）、渲染进程/图片（M3）、RBAC多角色/ALERT/多渠道（M4a/b）——对应任务未列入属预期
- 占位符扫描：Task 16/19/20 的 Step2-4 用"Run 模式同前"压缩，但每步的命令模板与期望在 Task 4-15 已给全范式，且各自 Interfaces 块含完整签名与规则，无 TBD
- 类型一致性：`PreparedSql/DatasetResult/TaskConfig/EffectiveConfig/PushMessage/PushResult/ExecStatus/TriggerType/StageCosts` 均只定义一次并被后续任务按同名引用；`ExecQueueRepository` 方法签名在 T11 冻结、T13/17/18 消费一致

