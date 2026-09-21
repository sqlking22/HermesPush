# HermesPush 智能数据推送平台 — 需求规格说明书

> 日期：2026-09-21
> 状态：需求澄清与对标调研完成，待评审
> 本阶段只交付需求文档，不进行代码开发。

---

## 一、背景与目标

业务方每天重复同一套动作：连库、跑 SQL、导数、套 Excel 模板、截图、发到企业微信群。一天几十份报表全靠人工，错一步重来，人休假就断档。

HermesPush 把这条链路变成配置：**数据源 → SQL → 模板填充 → 多格式渲染 → 定时多渠道分发**。业务人员自己配置日报周报，无需开发介入；开发只在新增数据源、新增渠道类型时参与。

### 成功标准

1. 业务人员能在 10 分钟内独立配好一个"每天 9 点把昨日销售明细发到群里"的任务，不写一行 Java。
2. 任务失败有人知道（告警），且能查清为什么失败（执行日志 + 参数 + SQL 原文 + 产物）。
3. 三个月后想知道"上个月 15 日那份报表是怎么生成的"，能完整复现。

### 非目标（明确不做）

| 不做 | 原因 |
|---|---|
| BI 图表 / 自助分析 | 是推送平台，不是 BI。图表靠 Excel 模板自身或 HTML 模板实现 |
| 数据血缘、元数据管理 | 超出范围 |
| 实时 / 流式推送 | 定时批处理够用 |
| 字段级敏感数据脱敏 | v1 不做，成本高误判多。靠只读账号 + 数据源级授权控制 |
| 跨数据集级联传参 | v1 不做。需要关联的场景用 SQL 自己 join |
| 多租户隔离 | 单组织内部平台 |

---

## 二、范围拆解：五个子系统

全部在 v1 范围内，第八节的分期是**交付顺序，不是范围裁剪**。

```
┌─────────────────────────────────────────────────────────┐
│  S5  接入与治理：Web 前端 / RBAC / 审计 / 告警           │
├─────────────────────────────────────────────────────────┤
│  S1 查询引擎   │  S2 渲染引擎   │  S3 推送引擎          │
│  多数据源路由   │  模板→产物     │  渠道路由/限流/重试   │
│  SQL 安全校验   │  三格式渲染    │                       │
├─────────────────────────────────────────────────────────┤
│  S4  调度与基础设施：Quartz / 执行队列 / 存储 / 版本     │
└─────────────────────────────────────────────────────────┘
```

---

## 三、领域模型

### 3.1 核心概念：产物（Artifact）模型（已确认）

一个任务下挂 N 个数据集、N 个产物、N 个渠道。产物引用数据集，渠道引用产物。

```
Task（推送任务）
├─ Datasets[]     数据集 = 数据源 + SQL + 参数定义
│    ds1  主库.销售明细SQL
│    ds2  报表库.汇总指标SQL
│
├─ Artifacts[]    产物 = 渲染器 + 模板 + 渲染配置
│    A1  MARKDOWN  md模板       ← ds1, ds2
│    A2  EXCEL     xlsx模板     ← ds1
│    A3  IMAGE     source=A2         （LibreOffice / Aspose 转图）
│    A4  IMAGE     source=html模板   ← ds1（Chromium 截图，默认路线）
│
└─ Channels[]     渠道绑定 = 渠道 + 产物列表 + 消息类型
     销售群   ← [A1(markdown), A4(image)]
     管理群   ← [A1(markdown), A2(file)]
```

选择理由：同一份数据同时发"摘要 + 附件 + 图片"是最常见诉求，一任务一模板模型下要建三个任务、SQL 跑三遍。产物模型同时把转图三方案的模板类型冲突关在 IMAGE 产物内部。

对标佐证：datart 一个任务只能发一种内容类型（图片或 Excel），正是这个模型要避免的局限。

### 3.2 数据集归属

**数据集属于任务（task-scoped），不做全局共享数据集库。** 共享会引入"改一个 SQL 影响哪些任务"的影响面分析问题，且破坏任务配置快照的自包含性。跨任务复用靠"复制任务"实现。

### 3.3 版本模型（已确认）

两级版本，覆盖"模板版本 + SQL 版本 + 任务可固版"三个诉求：

| 版本对象 | 内容 | 语义 |
|---|---|---|
| `template_version` | 模板二进制文件（xlsx / md / html） | 每次上传生成新版本号，不可变，可下载、可回滚 |
| `task_version` | 任务配置 JSON 快照：数据集（含 SQL 原文）、参数定义、产物定义、渠道绑定、**引用的模板版本号** | 每次保存生成新版本，不可变 |

- 任务有 `current_version_id`（最新）和可选的 `pinned_version_id`（固版）。固版后编辑不影响运行。
- 执行记录指向具体 `task_version_id`。**任何一次历史执行都能完整复现**——这也是 SQL 版本管理的落地方式。
- 产物文件按 `任务/日期/执行批次` 归档，带保留期策略。

不把 SQL 单独版本化的原因：SQL 版本 × 模板版本会产生组合爆炸，回滚时说不清"回到哪个组合"。配置快照把它们绑定成一个可回滚的原子单位。

### 3.4 数据表清单

| 表 | 说明 | 关键字段 |
|---|---|---|
| `hp_datasource` | 数据源 | type, jdbc_url, username, `password_cipher`, status, max_rows, query_timeout |
| `hp_datasource_grant` | 数据源授权 | datasource_id, principal_type(USER/ROLE), principal_id |
| `hp_task` | 任务 | name, owner_id, cron, status, current_version_id, pinned_version_id, timeout_sec |
| `hp_task_version` | 任务配置快照 | task_id, version_no, config_json, created_by, remark |
| `hp_template` | 模板元信息 | name, type(EXCEL/MARKDOWN/HTML), latest_version_no |
| `hp_template_version` | 模板版本 | template_id, version_no, storage_uri, checksum, size, created_by |
| `hp_channel` | 推送渠道 | type(WEWORK_BOT/...), `webhook_cipher`, `secret_cipher`, rate_limit, status |
| `hp_task_exec` | 执行记录 | task_id, task_version_id, trigger_type, status, fire_time, biz_date, params_json, retry_count, next_retry_at, node_id, heartbeat_at, cost_ms, error_code, error_msg |
| `hp_task_exec_artifact` | 产物记录 | exec_id, artifact_key, type, storage_uri, rows, bytes, render_provider, cost_ms |
| `hp_task_exec_push` | 渠道推送记录 | exec_id, channel_id, artifact_key, msg_type, status, retry_count, error（唯一键 `(exec_id, channel_id, artifact_key)` 保证幂等） |
| `hp_sql_audit` | SQL 审计 | exec_id, operator_id, datasource_id, sql_text, params_json, rows, cost_ms, scene(PREVIEW/EXEC), client_ip |
| `hp_media_cache` | 企微 media_id 缓存 | channel_id, file_md5, media_id, expire_at |
| `hp_alert_record` | 告警去重 | alert_key, last_sent_at |
| `hp_user` / `hp_role` / `hp_user_role` | RBAC | — |
| `QRTZ_*` | Quartz 集群表 | 官方建表脚本 |

---

## 四、功能需求

### S1 数据源与查询引擎

**DS-1 数据源管理**　支持 MySQL / PostgreSQL / Oracle。界面新增、编辑、连通性测试、启用停用。密码 AES-GCM 加密存储，接口返回永不回显明文。连接池按数据源独立（HikariCP），池参数可配。

**DS-2 部署约束（硬性）**　数据源配置必须使用**只读账号**。这是防误删库的真正防线，SQL 解析校验只是第二道。部署手册中列为前置检查项。

**SQL-1 参数语法**　两种，语义不同：

| 语法 | 行为 | 用途 | 默认 |
|---|---|---|---|
| `#{param}` | PreparedStatement 占位符绑定 | 值参数（日期、ID、阈值） | 默认、推荐 |
| `${param}` | 文本直接替换 | 表名、IN 列表、动态列——无法绑定的场景 | 需在参数定义上显式勾选"允许文本替换"，且值必须通过白名单/正则校验 |

**SQL-2 参数来源**　三类，优先级由低到高覆盖：

1. **动态时间变量**（内置）。语法 `#{name[±N(d|w|M|y)][:pattern]}`：
   - `runDate` = 触发时刻日期
   - `bizDate` = 业务日期 = `runDate + offset`，offset 任务级可配，**默认 -1d**
   - 示例：`#{bizDate}` → `2026-09-20`；`#{bizDate-1d:yyyyMMdd}` → `20260919`；`#{bizDate:yyyy-MM-01}` → 月初
2. **任务级静态参数**：`deptId=100`、`threshold=1000`
3. **运行时人工入参**：手动触发时弹窗填写，**可覆盖 `bizDate`**

第 3 点的 `bizDate` 覆盖是补数重跑的关键——否则重跑历史日期拿到的是今天的数据。对标佐证：Metabase 订阅支持发送时固定/覆盖过滤器参数，是同类产品的成熟语义。

**SQL-3 安全校验**　Druid SQL Parser 强制：单语句、必须是 `SELECT`、禁止 DDL/DML/多语句、禁止注释绕过。校验失败直接拒绝保存。

**SQL-4 执行保护**　查询超时（`statement.queryTimeout`，数据源级默认 60s）、行数上限（数据源级默认 50000 行，以 `ResultSet.maxRows` + `fetchSize` 流式读取控制，不靠拼 LIMIT）、超限行为可配（截断并告警 / 直接失败）。

**SQL-5 在线编辑与预览**　Monaco 编辑器，SQL 高亮。试运行返回前 **200 行** + 字段结构（字段名、类型），用于配置产物时的字段绑定提示。预览也进 SQL 审计日志（`scene=PREVIEW`）。

**SQL-6 结果集模型**　统一为 `DatasetResult { List<ColumnMeta> columns; List<Map<String,Object>> rows; int totalRows; boolean truncated; }`。所有渲染器只认这个结构，不感知数据库差异。

### S2 渲染引擎

**RD-1 渲染器接口**

```java
public interface ArtifactRenderer {
    ArtifactType type();
    RenderResult render(RenderContext ctx);   // ctx: datasets, params, template, config
}
```

**RD-2 TEXT / MARKDOWN 渲染**　Freemarker 模板。可访问所有数据集：单值 `${ds1.rows[0].amount}`、循环 `<#list ds2.rows as r>`。提供格式化辅助函数（千分位、百分比、日期）。

长度约束：企微 text 上限 2048 字节、markdown 上限 4096 字节（实现时以企微官方文档复核）。超长时按配置策略处理：截断加省略提示 / 自动降级为文件推送 / 失败。

**RD-3 EXCEL 渲染**　EasyExcel 模板填充。绑定规范（待细化的技术点，建议约定）：

- 单值：`{ds1.totalAmount}` → 取该数据集第一行对应字段
- 列表：`{.ds1.custName}` 配合 `FillWrapper("ds1", rows)`，多列表需 `FillConfig.forceNewRow`
- 几万行场景：分批 `fill()`，不一次性构建全量对象，避免 OOM

**RD-4 IMAGE 渲染——两条 source 路径，默认 HTML_SHOT（已确认）**

| Provider | source | 模板 | 定位 |
|---|---|---|---|
| `HTML_SHOT`（**默认**） | 引用数据集 | Freemarker **HTML 模板** | 业界主流路线（Superset/datart/Metabase 均为此路线），样式 100% 可控 |
| `LIBREOFFICE` | 引用一个 EXCEL 产物 | .xlsx | 与 Aspose 纯配置开关互换，零额外模板，还原度需 spike 验证 |
| `ASPOSE` | 引用一个 EXCEL 产物 | .xlsx | 同上，需商业 License，无 License 时不注册、界面置灰 |

设计上：`IMAGE` 产物有 `source` 字段（`ARTIFACT_REF` / `HTML_TEMPLATE`）。`ARTIFACT_REF` 路径下的 provider 由全局配置 + 产物级覆盖决定。

为降低 HTML 模板的维护门槛：**提供"根据查询结果自动生成默认 HTML 表格模板"能力**（基于列元数据生成带样式的表格，用户可再调整 CSS），使没有专属设计需求的任务零模板成本出图。

**RD-5 渲染效果对比页**　选一个任务 + 一个业务日期，同时用三种 provider 渲染，结果并排展示、可下载。服务于"看哪种效果好"的评估目的，避免上线后逐个改配置试。

**RD-6 图片后处理**　长图自动切分（按最大高度切片，可配）、压缩至企微 2MB 限制内（逐步降质或降分辨率）、DPI 可配。HTML_SHOT 路线下响应式宽度/长图切分天然支持（CSS + 视口控制）。

**RD-7 转图行数上限（重要约束）**　"几万行"和"转成图片发群"物理上不相容——会生成几米长的图。IMAGE 产物必须有独立行数上限，**默认 200 行**，超限策略可配：

- `TRUNCATE_TOP_N`：只渲染前 N 行，图上加"仅显示前 N 行，完整数据见附件"
- `DEGRADE_TO_FILE`：该渠道自动改发 Excel 文件
- `FAIL`：直接失败

对标佐证：Metabase 对 inline 结果和附件分别设行数上限，是同类产品验证过的做法。

**RD-8 进程隔离**　LibreOffice 和 Chromium 都是外部进程：每进程独立 `-env:UserInstallation`（LibreOffice 并发共用 profile 必崩）、进程超时强杀（默认 120s）、节点级并发信号量（默认 2）、进程池复用避免反复冷启动、服务器**必装中文字体**否则出方块。

对标佐证：datart 不捆绑 Chrome、需服务器自行安装截图环境，是社区部署问题高发区——部署文档必须把浏览器/字体/Office 环境列为强制检查项。renderer 接口保持干净，未来可拆独立渲染服务（Superset/datart 均将截图放独立 worker/服务）。

### S3 推送引擎

**PS-1 渠道抽象**

```java
public interface PushChannel {
    ChannelType type();
    void send(PushContext ctx);        // 含产物列表、消息类型
    ValidateResult validate(ChannelConfig cfg);
}
```

v1 实现企业微信群机器人。预留钉钉、飞书、邮件 SMTP 扩展点，v1 不实现（扩展时可参考 group-robot / message-spring-boot-starter 的消息模型，但限流、media_id 缓存、部分成功等精细控制需自研）。

**PS-2 企微机器人**　支持 text / markdown / image / file 四种消息。官方限制（已核实，[开发者文档](https://developer.work.weixin.qq.com/document/path/91770)）：

- image：base64 + md5，图片 ≤ 2MB，JPG/PNG
- file：先 `upload_media` 换 `media_id`，**media_id 有效期 3 天**，按 `(channel, file_md5)` 缓存复用；文件 ≤ 20MB
- 频率：每机器人 **20 条/分钟**

**PS-3 分布式限流**　Redisson 令牌桶，key 按 webhook 摘要（同一机器人可能被多个渠道配置引用，必须按 webhook 而非 channel_id 限流）。超限排队等待而非直接失败。

**PS-4 Webhook 域名白名单（安全，已确认）**　渠道 webhook 的 host 必须在白名单内（如 `qyapi.weixin.qq.com`）。防有人配一个自己控制的地址把数据导出去。白名单只有 ADMIN 能改。企微官方也提醒勿将 webhook 地址泄漏到公开场所——渠道配置接口对非 ADMIN 角色脱敏展示 webhook。

**PS-5 渠道级部分成功（已确认）**　一个任务推多个群时，逐渠道记录成败，执行状态可为 `PARTIAL_SUCCESS`。重试只重试失败渠道，靠 `hp_task_exec_push` 唯一键 `(exec_id, channel_id, artifact_key)` 保证幂等，成功的直接跳过不重发。

对标佐证：Metabase v0.50.x 曾出现一份订阅重复发 3 次的 bug（#45622），幂等键是必要防线。

### S4 调度与执行

**SC-1 调度与执行解耦（已确认）**

```
Quartz（集群 JDBC 模式）
    │  只做一件事：按 cron 插入一条 PENDING 执行记录
    │  幂等：唯一索引 (task_id, fire_time) 防集群重复触发
    ▼
hp_task_exec 队列
    │
    ▼
Worker 池（各节点独立，SELECT ... FOR UPDATE SKIP LOCKED 领取）
    查询 → 渲染 → 转图 → 推送 → 归档 → 清理临时文件
```

好处：转图动辄十几秒不会占满 Quartz 线程池；**定时触发、手动触发、失败重试三条路径统一**成"往队列里写一条记录"。上百任务量级不需要 MQ。

对标佐证：Superset 用 Celery Beat（调度）+ Celery Worker（执行）+ Redis（broker）的同构分离架构支撑生产规模；Metabase 多个 Quartz 调度状态错乱 issue（#50063、#10088）反证"Quartz 只管投递、状态机在业务表"更稳。

**SC-2 Cron 管理**　标准 Cron 表达式，保存时校验，展示**未来 5 次触发时间**。支持上线、下线、暂停、手动立即触发（可改参数）。

**SC-3 执行状态机**

```
PENDING ──► RUNNING ──┬──► SUCCESS
   ▲                  ├──► PARTIAL_SUCCESS   （部分渠道失败且重试耗尽）
   │                  ├──► FAILED
   │                  └──► TIMEOUT
   └──── RETRY_WAIT ◄──┘   （可重试错误且未超次数）
                            CANCELLED（人工取消）
```

**SC-4 重试策略（已确认）**　错误分类决定是否重试：

| 类别 | 例子 | 重试 |
|---|---|---|
| RETRYABLE | 数据源连接失败、查询超时、企微 5xx、限流、media 上传失败、转图进程超时 | 是 |
| NON_RETRYABLE | SQL 语法错误、模板解析失败、必填参数缺失、webhook 失效(40001)、图片超限 | 否 |

默认 3 次，指数退避 30s / 2min / 8min，次数与间隔任务级可配。

**SC-5 空结果集策略（已确认）**　任务级配置：`SKIP`（不推送，记为成功并标注）/ `PUSH_PLACEHOLDER`（推送"今日无数据"提示）/ `FAIL`。判定口径：默认"所有数据集均为空"才算空，任务可指定以哪几个数据集为判定依据。

对标佐证：Metabase 订阅的"Don't send if there's no results"开关，同类产品高频真实诉求。

**SC-6 失败告警（已确认）**　系统级告警渠道（独立配置，通常指向运维群）。告警事件：重试耗尽、连续失败 N 次、任务超期未触发、转图进程超时、存储上传失败。

告警不进重试队列，且必须**去重静默**：同一 `(task, error_code)` 在 30 分钟内只发一次（`hp_alert_record`），否则一个挂掉的数据源会刷爆运维群。对标佐证：Superset Alert 的 grace period + 基于日志的去重。

**SC-7 集群健壮性**　Worker 领取任务后写 `node_id` + 定期更新 `heartbeat_at`。巡检任务扫描"RUNNING 但心跳超时（默认 10min）"的记录，置为 FAILED 并触发重试——处理节点崩溃导致的僵死执行。本地临时文件由巡检任务按 mtime 清理残留。

**SC-8 执行日志**　记录：触发方式、任务版本、业务日期、实际参数、SQL 原文、各数据集行数与耗时、各产物（类型、provider、大小、行数、存储 URI、耗时）、各渠道推送状态与错误、总耗时。产物可在界面直接下载。

### S5 存储与版本

**ST-1 存储抽象**

```java
public interface FileStorage {
    StorageType type();
    String put(String path, InputStream in, long size);   // 返回逻辑 URI
    InputStream get(String uri);
    boolean delete(String uri);
    boolean exists(String uri);
    Optional<String> presignedUrl(String uri, Duration ttl);  // 可选能力
}
```

**ST-2 逻辑 URI**　入库存 `hp://{storageId}/{path}` 形式的逻辑 URI，不存物理绝对路径。否则换 OSS Bucket 或 HDFS NameNode 迁移后历史记录全部失效。

**ST-3 三种后端**

| 后端 | 实现 | 备注 |
|---|---|---|
| LOCAL | 本地磁盘 | 集群下仅适合开发环境，界面警示 |
| S3 | AWS S3 SDK v2 | **阿里云 OSS 与 MinIO 统一走 S3 兼容协议**，一份实现覆盖两个诉求 |
| HDFS | Hadoop Client | **独立可选模块**，按条件加载 |

HDFS 单独成模块的原因：Hadoop client 带来百 MB 级传递依赖和 Kerberos 认证复杂度，不应污染所有部署场景。

**ST-4 路径规范**
```
templates/{templateId}/v{versionNo}/{filename}
artifacts/{yyyy}/{MM}/{dd}/{taskId}/{execId}/{artifactKey}.{ext}
```

**ST-5 产物保留期（已确认）**　按存储类型和任务分别可配保留天数（默认 90 天）。定时清理任务删除过期产物文件，同时保留执行日志元数据（标记文件已清理）。模板版本永不自动删除。

**ST-6 版本操作**　模板：上传新版本、查看版本列表、下载任意版本、回滚（回滚 = 以历史版本内容创建新版本，不修改历史）。任务：版本列表、版本间配置 diff、回滚、固版/解除固版。

### S6 接入层与治理

**SEC-1 凭据加密**　数据源密码、webhook、加签密钥一律 AES-GCM 加密存库。**主密钥不入代码库、不入数据库**，通过环境变量或启动参数注入（生产建议接 KMS）。主密钥托管方案是开放问题 #1。

**SEC-2 数据源级授权（已确认）**　用户只能在被授权的数据源上建数据集。授权表支持按用户和按角色授予。

**SEC-3 SQL 审计（已确认）**　记录操作人、时间、数据源、SQL 原文、参数、返回行数、耗时、场景、客户端 IP。**不记录结果集内容本身**（避免审计表成为新的数据泄露面）。审计日志只增不改，ADMIN 可查。

**SEC-4 RBAC**　Sa-Token。角色：

| 角色 | 权限 |
|---|---|
| ADMIN | 全部，含数据源管理、渠道管理、Webhook 白名单、用户授权、审计查询 |
| DEVELOPER | 在授权数据源上建/改任务、写 SQL、管模板、手动触发自己的任务 |
| VIEWER | 只读查看任务与执行日志，不能看 SQL 原文与数据源配置 |

**UI-1 前端页面**　Vue 3 + Element Plus + Monaco Editor：

- 数据源管理（含连通性测试）
- **任务编排向导（4 步：数据集 → 产物 → 渠道 → 调度）**——核心页面，业务人员主入口，易用性决定平台成败
- 模板库（上传、版本列表、下载、预览）
- 渠道管理
- 执行日志（列表 + 详情 + 产物下载 + 重跑）
- 渲染效果对比页（RD-5）
- 系统设置（白名单、存储、告警、用户授权）

---

## 五、非功能需求

| 维度 | 要求 |
|---|---|
| 数据规模 | 单次查询最大 50000 行（可配）；任务数上百 |
| 内存 | 几万行场景走流式读取 + 分批写 Excel，禁止全量对象堆积 |
| 渲染耗时 | Markdown < 1s；Excel 几万行 < 30s；转图 < 60s（含进程启动） |
| 调度精度 | Cron 触发延迟 < 30s |
| 部署形态 | 多节点集群，Quartz JDBC 集群模式 + Redis(Redisson) |
| 可用性 | 单节点故障不丢任务（心跳巡检 + 队列重新领取） |
| 幂等 | 同一 `(exec_id, channel_id, artifact_key)` 不重复推送 |
| 可观测 | 执行日志 + 关键指标（成功率、耗时分布、渠道失败率） |

---

## 六、技术选型

| 模块 | 选型 | 说明 |
|---|---|---|
| 基础框架 | Spring Boot 3.x + JDK 21 | 虚拟线程适合 worker 池 IO 密集场景 |
| 持久层 | MyBatis-Plus + MySQL 8.0 | MySQL 8.0 是硬要求（`SKIP LOCKED`） |
| 调度 | Quartz JDBC 集群 | **仅负责产生执行记录**，不执行业务逻辑 |
| 执行队列 | DB 队列 + `FOR UPDATE SKIP LOCKED` | 不引 MQ（对标：Superset 用 Redis broker，我们量级更小，DB 队列够用且少一个依赖） |
| SQL 解析 | Druid SQL Parser | 第二道防线，**只读账号才是第一道** |
| Excel | EasyExcel | 多数据集填充绑定规范待 spike 细化 |
| 转图 | Playwright（HTML_SHOT，**默认**）/ LibreOffice / Aspose | 三实现并存，策略可插拔；Aspose 无 License 不注册 |
| 模板引擎 | Freemarker | Markdown 和 HTML 模板 |
| 对象存储 | AWS S3 SDK v2 | 一份实现覆盖 OSS 和 MinIO |
| HDFS | Hadoop Client | 独立可选模块 |
| 权限 | Sa-Token + AES-GCM | 主密钥托管待定（开放问题 #1） |
| 缓存限流 | Redis + Redisson | 限流 key 按 webhook 摘要而非 channel_id |
| 前端 | Vue 3 + Element Plus + Monaco | — |

---

## 七、核心流程：定时推送图片（含失败重试）

```
① Quartz 触发（集群仅一节点生效）
   └─ 解析 bizDate → 写 hp_task_exec(PENDING)，唯一索引防重

② Worker 领取（FOR UPDATE SKIP LOCKED）
   └─ 置 RUNNING，写 node_id，启动心跳

③ 加载任务版本快照（pinned 优先，否则 current）

④ QueryEngine：逐数据集执行
   └─ 解析时间变量 → #{} 绑定 / ${} 白名单校验 → 流式读取（限行数、限超时）
   └─ 写 hp_sql_audit

⑤ 空结果判定 → SKIP / PLACEHOLDER / FAIL

⑥ RenderEngine：按依赖顺序渲染产物
   └─ A1 MARKDOWN  Freemarker → 长度检查
   └─ A2 EXCEL     下载模板版本 → EasyExcel 分批填充 → 临时 xlsx
   └─ A4 IMAGE     HTML_SHOT：Freemarker HTML → Playwright 截图
                   → 行数超限降级 → 长图切分 → 压缩至 2MB 内

⑦ PushEngine：逐渠道逐产物
   └─ 查 hp_task_exec_push 幂等跳过 → Redisson 令牌桶
   └─ file 类型先查 media_id 缓存，未命中则 upload_media
   └─ 逐条记录成败

⑧ 归档：产物上传存储，写逻辑 URI

⑨ 收尾：汇总状态（SUCCESS/PARTIAL/FAILED）→ 清理本地临时文件
   └─ 失败且可重试 → RETRY_WAIT + next_retry_at
   └─ 重试耗尽 → 告警（去重静默）
```

---

## 八、分期交付计划

### M1　端到端最短闭环
数据源管理（含加密）· 数据集 + 时间变量 + SQL 校验 + 预览 · Markdown 产物 · 企微 text/markdown 推送 + 限流 · Quartz + 执行队列 + Worker · 执行日志 · **Webhook 白名单** · 最简前端

> 加密和白名单放 M1：事后给已有明文数据补加密很麻烦。

**验收**：配一个任务，每天定时把昨日汇总发到测试群，失败可在日志里查明原因。

### M2　Excel 与版本管理
FileStorage 抽象 + LOCAL + S3(OSS/MinIO) · 模板库与模板版本 · EasyExcel 模板填充（含几万行分批）· 任务配置快照版本 + 固版 + diff + 回滚 · 企微 file 推送 + media_id 缓存 · 产物归档与保留期清理

**验收**：Excel 报表定时生成并作为附件推送；改模板后可回滚到上一版本。

### M3　转图
IMAGE 产物模型 · **HTML_SHOT provider（默认，含自动生成表格模板）** · LibreOffice provider（进程池 + 独立 profile + 超时）· Aspose provider（可选加载）· 长图切分与压缩 · **行数上限与降级策略** · **渲染效果对比页**

**验收**：同一任务三种 provider 都能出图，对比页可并排评估，超行数自动降级。

### M4　治理
Sa-Token RBAC · 数据源级授权 · SQL 审计查询页 · 失败告警 + 去重静默 · 心跳巡检与僵死执行恢复 · 运行时人工入参与补数重跑 · 完整 Vue 前端（任务编排向导）

**验收**：非管理员只能操作被授权数据源；任务失败运维群收到一条（不是十条）告警；能补跑上月 15 日的报表。

### M5　扩展
HDFS 存储模块 · 指标与监控面板 · 更多渠道（钉钉/飞书/邮件，按需）· Alert 型任务（阈值触发，对标 Superset Alerts，按需）

---

## 九、开源项目对标分析

调研 6 个重叠度最高的项目，结论：**没有一个开源项目同时覆盖"SQL→模板→多格式→企微群推送"全链路**（最接近的是商业产品 EasySQLMail），但各家的分块实现都可借鉴，且验证了本方案多个关键设计决策。

### 9.1 对标矩阵

| 项目 | 语言/栈 | 定时调度 | 查询→渲染 | 转图方式 | 企微支持 | 版本管理 | 存储抽象 |
|---|---|---|---|---|---|---|---|
| **datart**（宜信） | Java/Spring Boot + Quartz | ✅ Quartz cron | 可视化作品（非裸 SQL 模板） | **独立截图服务**：PhantomJS/Chrome headless 渲染分享链接 | ✅ 群机器人 webhook | ❌ | ❌ |
| **Apache Superset** | Python/Celery | ✅ Beat 调度 + Worker 执行分离 | 图表/仪表盘 + Alert 阈值 SQL | Playwright 截图 → PNG/PDF | ❌（Slack/Email） | ❌ | 截图存 cache |
| **Metabase** | Clojure + Quartz | ✅ 应用内 Quartz（`send_pulses`） | 问题/仪表盘订阅，可带参数 | 静态渲染卡片为图片，63 版加 PDF | ❌（Email/Slack/Webhook） | ❌ | 附件临时文件 |
| **DataEase**（飞致云） | Java | ✅ 定时报告 | 仪表板报告 | 截图 | ✅ 但走**企业应用**（corpId/secret）非群机器人，且是 X-Pack 收费功能 | ❌ | ❌ |
| **JimuReport**（积木） | Java | ✅ 定时导出（1.9.1+） | 报表设计器 | 导出 Excel/PDF/Word/图片 | ❌ 仅邮件 | ❌ | ❌ |
| **EasySQLMail**（商业） | Java | ✅ | **SQL → Excel 模板**（与本项目最像） | **Excel 转 PNG** | ✅ 群机器人 + 钉钉 + 邮件 | 未知 | 未知 |

### 9.2 可借鉴点

**datart —— 技术栈最接近**
- 任务模型 `Schedule`（cron）→ Quartz → 生成内容（图片或 Excel）→ 渠道发送，与我们 Task/Artifact/Channel 同构但更扁平：一个任务只发一种内容——正是产物模型要避免的局限。
- 图片路线是"渲染网页再截图"（对分享链接跑 headless 浏览器，可配截图宽度）。不捆绑 Chrome，服务器自行安装，是社区部署问题高发区。
- Excel 路线只导出原始查询数据，无模板填充——EasyExcel 模板填充是我们超出它的能力。
- 借鉴：企微 webhook 渠道实现、截图宽度/超时参数化、截图独立服务化。

**Apache Superset —— 架构模式标杆**
- 调度（Celery Beat）与执行（Worker）彻底分离 + Redis broker，与我们"Quartz 写 PENDING + Worker 池领取"同构。
- Alert（阈值触发）与 Report（纯定时）分型——我们 v1 只做 Report 型，Alert 型列入 M5 演进。
- Alert 的 grace period + 基于日志去重，验证 SC-6 告警静默设计。
- 截图从 Selenium 迁移到 Playwright；输出 PNG/PDF/链接，"发不了图就发链接"的降级思路可借鉴。

**Metabase —— 订阅产品语义最完整**
- 订阅 = 仪表盘 + 多渠道 + 每渠道独立收件人/格式；接收者无需平台账号——对应"推群、群成员无感知平台"。
- "Don't send if there's no results" 开关 → 验证 SC-5。
- 订阅级参数/过滤器覆盖 → 验证 SQL-2 运行时入参。
- inline 结果 / CSV / XLSX 各有行数上限 → 验证 RD-7。
- 反面教材：v0.50.x 重复发送 bug（#45622）→ 验证 PS-5 幂等键必要性。
- Quartz 故障 issue（#50063、#10088）多为调度状态错乱 → 支持"Quartz 只管投递、状态机在业务表"。

**DataEase / JimuReport —— 反衬空白点**
- DataEase 定时报告收费（X-Pack），企微走重量级企业应用；JimuReport 定时导出只到邮件。
- **"群机器人 webhook 推 Excel/图片"在开源界基本没人做好——本项目的差异化价值所在。**

**EasySQLMail（商业）—— 需求验证**
- 形态与本项目几乎一致（SQL 定时 → Excel 模板 → PNG → 企微/钉钉/邮件）。商业闭源说明需求真实、开源缺位。

**组件级参考**
- [group-robot](https://github.com/ymlluo/group-robot)、[message-spring-boot-starter](https://github.com/wb04307201/message-spring-boot-starter)：M5 扩展钉钉/飞书时参考消息模型；v1 企微自研（限流、media_id 缓存、部分成功等精细控制通用 SDK 覆盖不了）。

### 9.3 对标得出的设计决策

1. **IMAGE 默认 provider = `HTML_SHOT`（已确认）**。业界（Superset/datart/Metabase）全部走"网页截图"路线，无成熟开源项目做 xlsx→image。HTML_SHOT 样式完全可控、长图切分自然；配套的"自动生成默认 HTML 表格模板"能力（RD-4）消解其额外模板成本。LIBREOFFICE/ASPOSE 作为"文件与图同源"的补充路线保留。
2. **转图服务接口保持可独立部署**。datart 独立截图服务、Superset 独立 worker 队列均如此。v1 进程内实现，接口干净到未来可拆 renderer 服务。
3. **企微官方限制已核实**：20 条/分钟、media_id 3 天、text/markdown/image/file/news（[官方文档](https://developer.work.weixin.qq.com/document/path/91770)），与 PS-2/PS-3 一致。

---

## 十、风险与待确认事项

### 开放问题（需业务方确认）

| # | 问题 | 影响 |
|---|---|---|
| 1 | **AES-GCM 主密钥放哪**：环境变量 / 启动参数 / KMS？ | 阻塞 M1 |
| 2 | **Aspose 是否已有商业 License**？没有则 M3 只交付 HTML_SHOT + LibreOffice | 影响 M3 范围 |
| 3 | **HDFS 是否启用 Kerberos**？启用则需 keytab 管理方案 | 影响 M5 |
| 4 | 部署方式：物理机 / K8s 容器？容器化需把 Chromium、中文字体、LibreOffice 打进镜像（体积约 1GB+），或转图走独立 renderer 服务 | 影响 M3 交付形态 |

~~5. IMAGE 默认 provider~~ → **已确认：HTML_SHOT**（见 9.3）。

### 技术风险

| 风险 | 缓解 |
|---|---|
| LibreOffice 转换样式还原度不足（复杂样式、条件格式、图表可能偏差） | 已降为补充路线（默认 HTML_SHOT）；M3 早期 spike 验证；对比页三方案评估；保留降级为文件推送 |
| LibreOffice 并发崩溃 | 每进程独立 `UserInstallation` + 信号量限并发 + 超时强杀 |
| Chromium/Playwright 内存占用与并发 | 浏览器进程池 + 并发信号量 + 单页超时；对标 Superset 的 screenshot worker 配置 |
| 中文字体缺失出方块 | 部署清单强制检查项（三方案共同风险） |
| 容器镜像体积大、冷启动慢 | 转图能力拆独立 renderer 服务（演进路径，v1 不做） |
| EasyExcel 多数据集填充语法限制 | M2 早期 spike 验证多 list + FillWrapper 组合；必要时约束"一个 sheet 一个列表" |
| 企微 API 行为与文档不符 | 限流阈值配置化，不硬编码 |
| 数据外泄（平台本质是"任意 SQL + 结果外发"） | 只读账号 + 数据源级授权 + Webhook 白名单 + SQL 审计，四层叠加 |

---

## 十一、下一步

1. 业务方评审本文档（重点：第三节领域模型对照 2–3 个真实报表场景走查；第八节 M1 验收标准）
2. 回答第十节 4 个开放问题（#1 阻塞 M1）
3. 评审通过后进入 **M1 实施计划**编写（writing-plans），拆成可 TDD 执行的任务序列
4. M1 前建议两个技术验证 spike：Playwright 截图服务在目标部署环境的可行性、EasyExcel 多数据集填充语法

---

## 附：参考来源

- [datart 定时任务官方文档](https://running-elephant.github.io/datart-docs/docs/schedule.html) · [datart GitHub](https://github.com/running-elephant/datart)
- [Apache Superset Alerts and Reports](https://superset.apache.org/admin-docs/configuration/alerts-reports/) · [Superset Architecture](https://superset.apache.org/admin-docs/installation/architecture/)
- [Metabase Dashboard Subscriptions](https://www.metabase.com/docs/latest/dashboards/subscriptions) · [Metabase pulse 源码 send_pulses.clj](https://github.com/metabase/metabase/blob/master/src/metabase/task/send_pulses.clj) · [重复发送 bug #45622](https://github.com/metabase/metabase/issues/45622)
- [DataEase 定时报告（X-Pack）](https://dataease.cn/docs/v2/xpack/sys_management_report/) · [DataEase 平台对接](https://dataease.cn/docs/v2/xpack/platform_abutment/)
- [JimuReport 报表定时导出](https://help.jimureport.com/exportJob)
- [企业微信群机器人消息推送官方文档](https://developer.work.weixin.qq.com/document/path/91770)
- [EasyExcel](https://github.com/alibaba/easyexcel) · [EasyReport](https://github.com/xianrendzw/EasyReport) · [group-robot](https://github.com/ymlluo/group-robot) · [message-spring-boot-starter](https://github.com/wb04307201/message-spring-boot-starter)
