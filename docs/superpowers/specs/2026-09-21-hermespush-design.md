# HermesPush 智能数据推送平台 — 需求规格说明书

> 日期：2026-09-21
> 版本：v1.1（对标 EasySQLMail 功能面修订：渠道扩展 / PDF 产物 / ALERT 任务 / FANOUT 个性化分发 / 配置审计）
> 状态：需求澄清、开源对标、EasySQLMail 功能对照完成，待终审
> 本阶段只交付需求文档，不进行代码开发。

---

## 一、背景与目标

业务方每天重复同一套动作：连库、跑 SQL、导数、套 Excel 模板、截图、发到企业微信群。一天几十份报表全靠人工，错一步重来，人休假就断档。

HermesPush 把这条链路变成配置：**数据源 → SQL → 模板填充 → 多格式渲染 → 定时多渠道分发**。业务人员自己配置日报周报，无需开发介入；开发只在新增数据源、新增渠道类型时参与。

功能面对标商业产品 EasySQLMail（成都国南新锐，2014 年起迭代 20+ 版本，闭源），逐项对照见 9.4 节。

### 成功标准

1. 业务人员能在 10 分钟内独立配好一个"每天 9 点把昨日销售明细发到群里"的任务，不写一行 Java。
2. 任务失败有人知道（告警），且能查清为什么失败（执行日志 + 参数 + SQL 原文 + 产物）。
3. 三个月后想知道"上个月 15 日那份报表是怎么生成的"，能完整复现。

### 非目标（明确不做）

| 不做 | 原因 |
|---|---|
| BI 图表 / 自助分析 | 是推送平台，不是 BI。图表靠 Excel 模板自身或 HTML 模板实现 |
| **交互式查询界面 / 自助取数**（EasySQLMail 场景5） | 做全了就是 Redash/datart 的领域，与推送平台定位冲突。轻量取数已被"SQL 预览 + 手动触发改参"覆盖 |
| 数据血缘、元数据管理 | 超出范围 |
| 实时 / 流式推送 | 定时批处理 + ALERT 周期评估够用 |
| 字段级敏感数据脱敏 | v1 不做，成本高误判多。靠只读账号 + 数据源级授权控制 |
| 跨数据集级联传参 | v1 不做。需要关联的场景用 SQL 自己 join |
| 多租户隔离 | 单组织内部平台 |
| 多任务工作流编排（EasySQLMail 的告警升级链、HTTP 命令完整版） | 太重。简化等价物见"远期演进"（SC-9 升级策略、GENERIC_WEBHOOK 渠道） |

---

## 二、范围拆解：五个子系统

全部在 v1 范围内，第八节的分期是**交付顺序，不是范围裁剪**。

```
┌─────────────────────────────────────────────────────────┐
│  S5  接入与治理：Web 前端 / RBAC / 审计 / 告警           │
├─────────────────────────────────────────────────────────┤
│  S1 查询引擎   │  S2 渲染引擎   │  S3 推送引擎          │
│  多数据源路由   │  模板→产物     │  渠道路由/限流/重试   │
│  SQL 安全校验   │  四格式渲染    │  五渠道类型           │
├─────────────────────────────────────────────────────────┤
│  S4  调度与基础设施：Quartz / 执行队列 / 存储 / 版本     │
│      任务类型：REPORT 定时报表 │ ALERT 数据监控         │
└─────────────────────────────────────────────────────────┘
```

---

## 三、领域模型

### 3.1 核心概念：产物（Artifact）模型（已确认）

一个任务下挂 N 个数据集、N 个产物、N 个渠道。产物引用数据集，渠道引用产物。

```
Task（推送任务，task_type = REPORT | ALERT）
├─ Datasets[]     数据集 = 数据源 + SQL + 参数定义
│    ds1  主库.销售明细SQL
│    ds2  报表库.汇总指标SQL
│
├─ Artifacts[]    产物 = 渲染器 + 模板 + 渲染配置
│    A1  MARKDOWN  md模板       ← ds1, ds2
│    A2  EXCEL     xlsx模板     ← ds1
│    A3  IMAGE     source=A2         （LibreOffice / Aspose 转图）
│    A4  IMAGE     source=html模板   ← ds1（Chromium 截图，默认路线）
│    A5  PDF       source=html模板   ← ds1（Playwright print，M3）
│
├─ Channels[]     渠道绑定 = 渠道 + 产物列表 + 消息类型
│    销售群(企微)  ← [A1(markdown), A4(image)]
│    管理群(钉钉)  ← [A1, A2(file)]
│    管理层(邮件)  ← [A5(pdf 附件)]
│
└─ Fanout?（M5，可选） 分发清单 = 静态列表 | 清单SQL
     每行 → 一个子执行（参数组 + 接收目标），一人一报
```

选择理由：同一份数据同时发"摘要 + 附件 + 图片"是最常见诉求，一任务一模板模型下要建三个任务、SQL 跑三遍。产物模型同时把转图三方案的模板类型冲突关在 IMAGE 产物内部。

对标佐证：datart 一个任务只能发一种内容类型（图片或 Excel），正是这个模型要避免的局限。

### 3.2 任务类型（M4 引入 ALERT）

| 类型 | 语义 | 触发 |
|---|---|---|
| `REPORT` | 定时报表推送（M1 起的主链路） | cron 到点必发（受空结果策略约束） |
| `ALERT` | 数据监控：周期执行监控 SQL，评估条件，**仅状态跃迁时通知** | cron 周期评估，OK→TRIGGERED / TRIGGERED→OK 才发 |

### 3.3 数据集归属

**数据集属于任务（task-scoped），不做全局共享数据集库。** 共享会引入"改一个 SQL 影响哪些任务"的影响面分析问题，且破坏任务配置快照的自包含性。跨任务复用靠"复制任务"实现。

### 3.4 版本模型（已确认）

两级版本，覆盖"模板版本 + SQL 版本 + 任务可固版"三个诉求：

| 版本对象 | 内容 | 语义 |
|---|---|---|
| `template_version` | 模板二进制文件（xlsx / md / html） | 每次上传生成新版本号，不可变，可下载、可回滚 |
| `task_version` | 任务配置 JSON 快照：数据集（含 SQL 原文）、参数定义、产物定义、渠道绑定、分发清单、告警条件、**引用的模板版本号** | 每次保存生成新版本，不可变 |

- 任务有 `current_version_id`（最新）和可选的 `pinned_version_id`（固版）。固版后编辑不影响运行。
- 执行记录指向具体 `task_version_id`。**任何一次历史执行都能完整复现**——这也是 SQL 版本管理的落地方式。
- 产物文件按 `任务/日期/执行批次` 归档，带保留期策略。

不把 SQL 单独版本化的原因：SQL 版本 × 模板版本会产生组合爆炸，回滚时说不清"回到哪个组合"。配置快照把它们绑定成一个可回滚的原子单位。

### 3.5 数据表清单

| 表 | 说明 | 关键字段 |
|---|---|---|
| `hp_datasource` | 数据源 | type, jdbc_url, username, `password_cipher`, status, max_rows, query_timeout |
| `hp_datasource_grant` | 数据源授权 | datasource_id, principal_type(USER/ROLE), principal_id |
| `hp_task` | 任务 | name, **task_type(REPORT/ALERT)**, owner_id, cron, status, current_version_id, pinned_version_id, timeout_sec |
| `hp_task_version` | 任务配置快照 | task_id, version_no, config_json, created_by, remark |
| `hp_template` | 模板元信息 | name, type(EXCEL/MARKDOWN/HTML), latest_version_no |
| `hp_template_version` | 模板版本 | template_id, version_no, storage_uri, checksum, size, created_by |
| `hp_channel` | 推送渠道 | type(WEWORK_BOT/DINGTALK_BOT/FEISHU_BOT/EMAIL_SMTP/GENERIC_WEBHOOK), `config_cipher`, rate_limit, status, deleted（逻辑删除，评审修订） |
| `hp_whitelist` | 白名单（评审修订新增） | type(WEBHOOK_HOST/EMAIL_DOMAIN/EMAIL_ADDRESS), value, status, created_by；保存与发送双重校验，删除前校验渠道引用 |
| `hp_task_exec` | 执行记录 | task_id, task_version_id, trigger_type, status, fire_time, biz_date, params_json, retry_count, next_retry_at, node_id, heartbeat_at, cost_ms, error_code, error_msg, **idempotency_key, priority**（评审修订），**parent_exec_id, fanout_key**（M5）。调度防重唯一索引 `(task_id, fire_time, trigger_type, idempotency_key)`；API/手动触发幂等唯一约束 `(task_id, idempotency_key)`；FANOUT 子执行独立唯一键 `(parent_exec_id, fanout_key)` |
| `hp_task_exec_artifact` | 产物记录 | exec_id, artifact_key, type, storage_uri, rows, bytes, render_provider, cost_ms |
| `hp_task_exec_push` | 渠道推送记录 | exec_id, channel_id, artifact_key, msg_type, status, retry_count, error（幂等唯一键为四元组 `(exec_id, channel_id, artifact_key, msg_type)`，评审修订：支持同产物降级改发等多消息类型场景；重试复用原 exec_id，已成功组合天然跳过） |
| `hp_sql_audit` | SQL 审计 | exec_id, operator_id, datasource_id, sql_text, params_json, rows, cost_ms, scene(PREVIEW/EXEC), client_ip |
| `hp_alert_state` | ALERT 任务状态（M4） | task_id, task_version_id, state(OK/TRIGGERED), last_value, consecutive_count, last_transition_at（评审修订：计数与状态更新走乐观锁条件更新；任务版本变更时重置防抖计数） |
| `hp_media_cache` | 企微/飞书素材缓存 | channel_id, file_md5, media_id, expire_at |
| `hp_oplog` | 配置变更审计（M4） | entity_type, entity_id, operator_id, action, diff_json, client_ip, created_at |
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
3. **运行时人工入参**：手动触发时弹窗填写，**可覆盖 `bizDate`**；FANOUT 场景下由分发清单行自动注入（SC-10）

第 3 点的 `bizDate` 覆盖是补数重跑的关键——否则重跑历史日期拿到的是今天的数据。对标佐证：Metabase 订阅支持发送时固定/覆盖过滤器参数，是同类产品的成熟语义。

**SQL-3 安全校验**　Druid SQL Parser 强制：单语句、必须是 `SELECT`、禁止 DDL/DML/多语句、禁止注释绕过。校验失败直接拒绝保存。

**SQL-4 执行保护**　查询超时（`statement.queryTimeout`，数据源级默认 60s）、行数上限（数据源级默认 50000 行，以 `ResultSet.maxRows` + `fetchSize` 流式读取控制，不靠拼 LIMIT）、超限行为可配（截断并告警 / 直接失败）。

**SQL-5 在线编辑与预览**　Monaco 编辑器，SQL 高亮。试运行返回前 **200 行** + 字段结构（字段名、类型），用于配置产物时的字段绑定提示。预览也进 SQL 审计日志（`scene=PREVIEW`）。

**SQL-6 结果集模型**　统一为 `DatasetResult { List<ColumnMeta> columns; List<Map<String,Object>> rows; int totalRows; boolean truncated; }`。所有渲染器只认这个结构，不感知数据库差异。

### S2 渲染引擎

**RD-1 渲染器接口**

```java
public interface ArtifactRenderer {
    ArtifactType type();   // MARKDOWN / EXCEL / IMAGE / PDF
    RenderResult render(RenderContext ctx);   // ctx: datasets, params, template, config
}
```

**RD-2 TEXT / MARKDOWN 渲染**　Freemarker 模板。可访问所有数据集：单值 `${ds1.rows[0].amount}`、循环 `<#list ds2.rows as r>`。提供格式化辅助函数（千分位、百分比、日期）。

**RD-2b 模板沙箱（评审修订，阻塞级安全项，对应 PRD FR-RD-10，P0/M1）**　用户可上传的 Freemarker 模板（Markdown/HTML/Webhook 请求体）默认允许 `?new` 构造任意 Java 对象与 `?api` 方法调用，等同 RCE。强制配置：TemplateClassResolver = ALLOWS_NOTHING_RESOLVER、api_builtin_enabled = false、禁用 ObjectConstructor/Execute/JythonRuntime 等危险内建；模板上传与导入时静态扫描危险指令，命中拒绝入库；渲染超时保护默认 30s。所有用户模板渲染路径不得绕过沙箱。

长度约束：企微 text 上限 2048 字节、markdown 上限 4096 字节；钉钉/飞书各有上限（实现时以各家官方文档复核，阈值配置化）。超长时按配置策略处理：截断加省略提示 / 自动降级为文件推送 / 失败。

**RD-3 EXCEL 渲染**　EasyExcel 模板填充。绑定规范（待 spike 细化的技术点，建议约定）：

- 单值：`{ds1.totalAmount}` → 取该数据集第一行对应字段
- 列表：`{.ds1.custName}` 配合 `FillWrapper("ds1", rows)`，多列表需 `FillConfig.forceNewRow`
- 几万行场景：分批 `fill()`，不一次性构建全量对象，避免 OOM

**RD-4 IMAGE 渲染——两条 source 路径，默认 HTML_SHOT（已确认）**

| Provider | source | 模板 | 定位 |
|---|---|---|---|
| `HTML_SHOT`（**默认**） | 引用数据集 | Freemarker **HTML 模板** | 业界主流路线（Superset/datart/Metabase/Redash 均为此路线），样式 100% 可控 |
| `LIBREOFFICE` | 引用一个 EXCEL 产物 | .xlsx | 与 Aspose 纯配置开关互换，零额外模板，还原度需 spike 验证 |
| `ASPOSE` | 引用一个 EXCEL 产物 | .xlsx | 同上，需商业 License，无 License 时不注册、界面置灰 |

设计上：`IMAGE` 产物有 `source` 字段（`ARTIFACT_REF` / `HTML_TEMPLATE`）。`ARTIFACT_REF` 路径下的 provider 由全局配置 + 产物级覆盖决定。

为降低 HTML 模板的维护门槛：**提供"根据查询结果自动生成默认 HTML 表格模板"能力**（基于列元数据生成带样式的表格，用户可再调整 CSS），使没有专属设计需求的任务零模板成本出图。

**RD-5 渲染效果对比页**　选一个任务 + 一个业务日期，同时用三种 provider 渲染，结果并排展示、可下载。服务于"看哪种效果好"的评估目的。

**RD-6 图片后处理**　长图自动切分（按最大高度切片，可配）、压缩至渠道限制内（企微 2MB；逐步降质或降分辨率）、DPI 可配。HTML_SHOT 路线下响应式宽度/长图切分天然支持（CSS + 视口控制）。

**RD-7 转图行数上限（重要约束）**　"几万行"和"转成图片发群"物理上不相容。IMAGE 产物必须有独立行数上限，**默认 200 行**，超限策略可配：

- `TRUNCATE_TOP_N`：只渲染前 N 行，图上加"仅显示前 N 行，完整数据见附件"
- `DEGRADE_TO_FILE`：该渠道自动改发 Excel 文件
- `FAIL`：直接失败

对标佐证：Metabase 对 inline 结果和附件分别设行数上限。

**RD-8 进程隔离**　LibreOffice 和 Chromium 都是外部进程：每进程独立 `-env:UserInstallation`（LibreOffice 并发共用 profile 必崩）、进程超时强杀（默认 120s）、节点级并发信号量（默认 2）、进程池复用避免冷启动、服务器**必装中文字体**。renderer 接口保持干净，未来可拆独立渲染服务（Superset/datart 均将截图放独立 worker/服务）。

**RD-9 PDF 渲染（M3，对标 EasySQLMail PDF 输出）**　`ArtifactType.PDF`：

| Provider | source | 说明 |
|---|---|---|
| `HTML_PRINT`（默认） | HTML 模板 | Playwright `page.pdf()`，与 HTML_SHOT 同栈零新增依赖，支持页眉页脚/分页 |
| `LIBREOFFICE` | EXCEL 产物 | xlsx → pdf，文件与 PDF 同源 |

PDF 加密/禁打印/禁复制列远期（见"远期演进"）。

**RD-10 图片水印（M3，对标 EasySQLMail 水印报表）**　HTML_SHOT 产物的模板级配置：水印文字支持变量（接收人、日期、任务名、分发清单行字段），CSS 斜排半透明实现，防二次传播时可溯源。EXCEL/LIBREOFFICE 路线的水印列远期。

### S3 推送引擎

**PS-1 渠道抽象与渠道类型（已确认扩展）**

```java
public interface PushChannel {
    ChannelType type();
    void send(PushContext ctx);        // 含产物列表、消息类型
    ValidateResult validate(ChannelConfig cfg);
}
```

| 渠道类型 | 里程碑 | 说明 |
|---|---|---|
| `WEWORK_BOT` | M1 | 企业微信群机器人 webhook，text/markdown/image/file |
| `DINGTALK_BOT` | M4 | 钉钉群机器人 webhook，与企微同构（text/markdown/image/file via 媒体上传），timestamp+sign 加签 |
| `FEISHU_BOT` | M4 | 飞书群机器人 webhook，文本/富文本/图片(image_key)/文件，可选加签 |
| `EMAIL_SMTP` | M4 | 邮件：收件人列表、HTML 正文（可绑 MARKDOWN/HTML 产物）、附件（Excel/PDF/图片产物） |
| `GENERIC_WEBHOOK` | M5 | 把渲染结果 POST 到任意 HTTP 端点，请求体 Freemarker 模板可配——EasySQLMail"HTTP 命令"的简化版，方便对接自建系统 |

三家群机器人共享同一套限流/重试/白名单/消息组装框架，差异仅在消息体格式与加签算法——渠道 SPI 的价值所在。对标：DolphinScheduler 告警插件化（Email/钉钉/企微/飞书/HTTP/Script）。

**PS-2 企微机器人**　支持 text / markdown / image / file 四种消息。官方限制（已核实，[开发者文档](https://developer.work.weixin.qq.com/document/path/91770)）：

- image：base64 + md5，图片 ≤ 2MB，JPG/PNG
- file：先 `upload_media` 换 `media_id`，**media_id 有效期 3 天**，按 `(channel, file_md5)` 缓存复用；文件 ≤ 20MB
- 频率：每机器人 **20 条/分钟**
- `mentioned_list` 支持 @ 群成员（FANOUT 场景使用）

**PS-2b 钉钉/飞书机器人（M4）**　各自的消息类型映射、加签算法（钉钉 HmacSHA256 timestamp+sign；飞书可选签名）、频率限制与素材上传机制**实现时以官方文档复核**，限流阈值全部配置化不硬编码。飞书图片需先上传获取 image_key，复用 `hp_media_cache` 机制。

**PS-3 分布式限流**　Redisson 令牌桶，key 按 webhook 摘要（同一机器人可能被多个渠道配置引用，必须按 webhook 而非 channel_id 限流）。超限排队等待而非直接失败。各渠道默认阈值独立配置（企微 20/min；钉钉 20/min；飞书以官方为准）。

**PS-4 Webhook 域名白名单（安全，已确认）**　渠道 webhook 的 host 必须在白名单内（如 `qyapi.weixin.qq.com`、`oapi.dingtalk.com`、`open.feishu.cn`）。防有人配一个自己控制的地址把数据导出去。白名单只有 ADMIN 能改。渠道配置接口对非 ADMIN 角色脱敏展示 webhook。

**PS-5 渠道级部分成功（已确认）**　一个任务推多个渠道时，逐渠道记录成败，执行状态可为 `PARTIAL_SUCCESS`。重试只重试失败渠道，靠 `hp_task_exec_push` 四元组唯一键 `(exec_id, channel_id, artifact_key, msg_type)` 保证幂等（评审修订：含消息类型维度），成功的直接跳过不重发。重试复用原执行 ID（评审修订：换新 ID 会使幂等键失效导致重发）。对标佐证：Metabase v0.50.x 重复发送 bug（#45622）。

**PS-6 邮件渠道（M4，对标 EasySQLMail 主渠道）**

- SMTP 主机/端口/账号/密码（SSL/TLS），凭据 AES-GCM 加密
- 收件人/抄送列表；**收件人域名/地址白名单**（对标 EasySQLMail"只允许发给地址簿内地址"），ADMIN 维护
- 正文：HTML（可直接绑 MARKDOWN/HTML 产物渲染结果）；附件：Excel/PDF/图片产物，从存储拉取，附件总大小上限可配（默认 20MB）
- 发送失败分类进 SC-4 重试体系（SMTP 连接失败可重试，地址被拒不可重试）

**PS-7 GENERIC_WEBHOOK 渠道（M5）**　POST JSON（结构模板可配：产物元信息 + 存储下载 URL 或内联文本），支持自定义 Header、Basic Auth、超时与重试。目标 URL 同样受白名单管控（默认拒绝公网任意地址，需 ADMIN 添加）。

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
    REPORT: 查询 → 渲染 → 推送 → 归档 → 清理
    ALERT:  查询 → 条件评估 → 状态跃迁判定 → (跃迁时)通知
```

好处：转图动辄十几秒不会占满 Quartz 线程池；**定时触发、手动触发、失败重试三条路径统一**成"往队列里写一条记录"。上百任务量级不需要 MQ。

对标佐证：Superset Celery Beat/Worker 分离；Redash scheduler 每 30s 扫描到期查询投递 Celery 队列，同构模式。

**SC-2 Cron 管理**　标准 Cron 表达式，保存时校验，展示**未来 5 次触发时间**。支持上线、下线、暂停、手动立即触发（可改参数）。调度粒度覆盖分钟/小时/天/星期/月（对标 EasySQLMail 执行计划）。

**SC-3 执行状态机**

```
PENDING ──► RUNNING ──┬──► SUCCESS
   ▲                  ├──► PARTIAL_SUCCESS   （部分渠道失败且重试耗尽）
   │                  ├──► FAILED
   │                  └──► TIMEOUT
   └──── RETRY_WAIT ◄──┘   （可重试错误且未超次数）
                            CANCELLED（人工取消）

M5 审核流（报表审核任务专用）：
RUNNING ──► WAIT_APPROVAL ──► (批准) ──► 推送 ──► SUCCESS
                    └──────► (驳回) ──► CANCELLED
```

**SC-4 重试策略（已确认）**　错误分类决定是否重试：

| 类别 | 例子 | 重试 |
|---|---|---|
| RETRYABLE | 数据源连接失败、查询超时、机器人渠道 5xx、限流、素材上传失败、SMTP 连接失败、转图进程超时 | 是 |
| NON_RETRYABLE | SQL 语法错误、模板解析失败、必填参数缺失、webhook 失效(40001)、图片超限、邮件地址被拒 | 否 |

默认 3 次，指数退避 30s / 2min / 8min，次数与间隔任务级可配。重试复用原执行记录（同一 exec_id，retry_count 自增，RETRY_WAIT 到期由巡检置回 PENDING 重新领取），next_retry_at 以数据库时间计算（评审修订：防节点时钟漂移）。

**SC-5 空结果集策略（已确认）**　任务级配置：`SKIP`（不推送，记为成功并标注）/ `PUSH_PLACEHOLDER`（推送"今日无数据"提示）/ `FAIL`。判定口径：默认"所有数据集均为空"才算空，任务可指定以哪几个数据集为判定依据。对标：Metabase"Don't send if there's no results"。

**SC-6 失败告警（已确认）**　系统级告警渠道（独立配置，通常指向运维群/运维邮箱）。告警事件：重试耗尽、连续失败 N 次、任务超期未触发、转图进程超时、存储上传失败。

告警不进重试队列，且必须**去重静默**：同一 `(task, error_code)` 在 30 分钟内只发一次（`hp_alert_record`）。对标：Superset Alert grace period + 日志去重。

**SC-7 集群健壮性**　Worker 领取任务后写 `node_id` + 每 30s 更新 `heartbeat_at`，心跳超时阈值 2min（评审修订：原 10min 恢复过慢）。竞态防护（评审修订）：巡检重置使用条件更新 `UPDATE ... SET status='FAILED' WHERE id=? AND status='RUNNING' AND heartbeat_at<?` 并校验影响行数；Worker 每次心跳后自检执行仍为 RUNNING，发现被重置立即放弃式退出，杜绝双 Worker 并发处理同一执行。本地临时文件由巡检任务按 mtime 清理残留。

**SC-8 执行日志**　记录：触发方式、任务版本、业务日期、实际参数、SQL 原文、各数据集行数与耗时、各产物（类型、provider、大小、行数、存储 URI、耗时）、各渠道推送状态与错误、总耗时。产物可在界面直接下载。FANOUT 场景下父执行汇总 N 个子执行状态。

**SC-9 ALERT 任务类型（M4，对标 EasySQLMail 数据监控 + Redash/Superset Alerts）**

- **监控定义**：单数据集监控 SQL（返回单值或单列）+ 条件表达式（`> >= < <= == !=` 阈值，阈值支持参数与时间变量）
- **评估周期**：cron（如每 5 分钟）；每次评估执行 SQL、比较条件、更新 `hp_alert_state`
- **通知语义（Redash 状态跃迁模型）**：仅 `OK→TRIGGERED`（告警）和 `TRIGGERED→OK`（恢复）时发送通知，持续 TRIGGERED 期间不重复轰炸；消息带触发值、阈值、时间、任务链接
- **grace period**：连续 N 次评估满足条件才跃迁为 TRIGGERED（防抖，N 默认 1）
- **通知渠道**：复用 PushEngine 全部渠道（文本/markdown 消息）
- **静默期**：复用 SC-6 去重机制兜底
- 告警升级（触发后 N 分钟未恢复→追加通知其他渠道/人）列远期演进

**SC-10 FANOUT 个性化分发（M5，对标 EasySQLMail 个性化报表分发；开源界无先例）**

同一任务按"分发清单"循环执行，每个接收目标收到用自己参数生成的专属报表（如：成都经理只收成都数据）。

- **分发清单来源**（二选一）：
  - 静态清单：界面维护 `接收目标 + 参数组` 列表
  - 清单 SQL：一条 SQL 每行 = 一个接收目标 + 参数组（如 `SELECT branch_name, manager_email, dept_id FROM ...`），支持时间变量
- **执行模型**：父执行查一次清单 → 扇出 N 个子执行（`parent_exec_id` + `fanout_key`=行标识）；每子执行独立渲染/推送/重试/幂等/日志，互不影响（一个分公司失败不拖累其他）
- **渠道语义**：邮件点对点（每子执行收件人=清单行地址）；企微群 @ 成员（`mentioned_list`=清单行手机号/userid）；FANOUT 任务的水印变量可引用清单行字段（"仅供 XX 分公司使用"）
- **保护**：子执行数量上限（默认 200，防误配全表循环）；子执行并发度可配；清单 SQL 同样走 SQL-3 校验与审计
- **版本**：分发清单进任务配置快照，历史执行可复现

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

**ST-2 逻辑 URI**　入库存 `hp://{storageId}/{path}` 形式的逻辑 URI，不存物理绝对路径。

**ST-3 三种后端**

| 后端 | 实现 | 备注 |
|---|---|---|
| LOCAL | 本地磁盘 | 集群下仅适合开发环境，界面警示 |
| S3 | AWS S3 SDK v2 | **阿里云 OSS 与 MinIO 统一走 S3 兼容协议** |
| HDFS | Hadoop Client | **独立可选模块**（M5），按条件加载 |

**ST-4 路径规范**
```
templates/{templateId}/v{versionNo}/{filename}
artifacts/{yyyy}/{MM}/{dd}/{taskId}/{execId}/{artifactKey}.{ext}
```

**ST-5 产物保留期（已确认）**　按存储类型和任务分别可配保留天数（默认 90 天）。定时清理删除过期产物文件，保留执行日志元数据（标记文件已清理）。模板版本永不自动删除。

**ST-6 版本操作**　模板：上传新版本、版本列表、下载任意版本、回滚（= 以历史版本内容创建新版本）。任务：版本列表、版本间配置 diff、回滚、固版/解除固版。

### S6 接入层与治理

**SEC-1 凭据加密**　数据源密码、各渠道 webhook/密钥/SMTP 密码一律 AES-GCM 加密存库。**主密钥不入代码库、不入数据库**，环境变量或启动参数注入（生产建议 KMS）。开放问题 #1。对标：EasySQLMail"多层 AES 加密存储"、Redash webhook secret 加密。

**SEC-2 数据源级授权（已确认）**　用户只能在被授权的数据源上建数据集。授权支持按用户和按角色。

**SEC-3 SQL 审计（已确认）**　记录操作人、时间、数据源、SQL 原文、参数、返回行数、耗时、场景、客户端 IP。**不记录结果集内容本身**。只增不改，ADMIN 可查。

**SEC-4 RBAC**　Sa-Token。角色：

| 角色 | 权限 |
|---|---|
| ADMIN | 全部，含数据源管理、渠道管理、白名单（webhook/邮件收件人）、用户授权、审计查询 |
| DEVELOPER | 在授权数据源上建/改任务、写 SQL、管模板、手动触发自己的任务 |
| VIEWER | 只读查看任务与执行日志，不能看 SQL 原文与数据源配置 |

**SEC-5 配置变更审计（M4，对标 EasySQLMail"完整审计合规追踪"）**　`hp_oplog` 记录所有配置实体（数据源/任务/模板/渠道/用户/白名单）的增删改：操作人、时间、动作、字段级 diff JSON、客户端 IP。只增不改。

**SEC-6 账户与访问安全（M4，对标 EasySQLMail 安全篇）**　登录失败次数限制与账户锁定（Sa-Token 配套）、登录日志、敏感操作（改密码/改白名单）邮件通知管理员、会话超时。IP 白名单与 HTTPS（含双向认证）由部署层（网关/Nginx）实现，列入部署手册而非应用代码。

**UI-1 前端页面**　Vue 3 + Element Plus + Monaco Editor：

- 数据源管理（含连通性测试）
- **任务编排向导（REPORT 4 步：数据集 → 产物 → 渠道 → 调度；ALERT 3 步：监控 SQL+条件 → 渠道 → 周期）**——核心页面，业务人员主入口
- 模板库（上传、版本列表、下载、预览）
- 渠道管理（五类渠道）
- 执行日志（列表 + 详情 + 产物下载 + 重跑；FANOUT 父子执行视图）
- ALERT 状态面板（当前状态、跃迁历史、手动测试触发）
- 渲染效果对比页（RD-5）
- 审计中心（SQL 审计 + 配置变更审计）
- 系统设置（白名单、存储、告警、用户授权）
- 报表审核工作台（M5，WAIT_APPROVAL 队列：预览产物 → 批准/驳回）

---

## 五、非功能需求

| 维度 | 要求 |
|---|---|
| 数据规模 | 单次查询最大 50000 行（可配）；任务数上百；FANOUT 子执行 ≤ 200/次 |
| 内存 | 几万行场景走流式读取 + 分批写 Excel，禁止全量对象堆积 |
| 渲染耗时 | Markdown < 1s；Excel 几万行 < 30s；转图/PDF < 60s（含进程启动） |
| 调度精度 | Cron 触发延迟 < 30s；ALERT 评估周期最小 1 分钟 |
| 部署形态 | 多节点集群，Quartz JDBC 集群模式 + Redis(Redisson) |
| 可用性 | 单节点故障不丢任务（心跳巡检 + 队列重新领取） |
| 幂等 | 同一 `(exec_id, channel_id, artifact_key)` 不重复推送；ALERT 仅状态跃迁通知 |
| 可观测 | 执行日志 + 关键指标（成功率、耗时分布、渠道失败率、ALERT 跃迁记录） |

---

## 六、技术选型

| 模块 | 选型 | 说明 |
|---|---|---|
| 基础框架 | Spring Boot 3.x + JDK 21 | 虚拟线程适合 worker 池 IO 密集场景 |
| 持久层 | MyBatis-Plus + MySQL 8.0 | MySQL 8.0 硬要求（`SKIP LOCKED`） |
| 调度 | Quartz JDBC 集群 | **仅负责产生执行记录**，不执行业务逻辑 |
| 执行队列 | DB 队列 + `FOR UPDATE SKIP LOCKED` | 不引 MQ（Redash 用 Celery+Redis，我们量级更小，DB 队列少一个依赖） |
| SQL 解析 | Druid SQL Parser | 第二道防线，**只读账号才是第一道** |
| Excel | EasyExcel | 多数据集填充绑定规范待 spike 细化 |
| 转图/PDF | Playwright（HTML_SHOT + HTML_PRINT，**默认**）/ LibreOffice / Aspose | 策略可插拔；Aspose 无 License 不注册 |
| 模板引擎 | Freemarker | Markdown / HTML / GENERIC_WEBHOOK 请求体模板 |
| 邮件 | Spring Mail (JavaMailSender) | SMTP + 附件 |
| 对象存储 | AWS S3 SDK v2 | 一份实现覆盖 OSS 和 MinIO |
| HDFS | Hadoop Client | 独立可选模块 |
| 权限 | Sa-Token + AES-GCM | 账户锁定/登录日志用 Sa-Token 配套能力 |
| 缓存限流 | Redis + Redisson | 限流 key 按 webhook 摘要而非 channel_id |
| 前端 | Vue 3 + Element Plus + Monaco | — |

---

## 七、核心流程

### 7.1 REPORT：定时推送图片（含失败重试）

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
   └─ A4 IMAGE     HTML_SHOT：Freemarker HTML(+水印) → Playwright 截图
                   → 行数超限降级 → 长图切分 → 压缩至渠道限制内
   └─ A5 PDF       HTML_PRINT：同 HTML → Playwright print

⑦ PushEngine：逐渠道逐产物
   └─ 查 hp_task_exec_push 幂等跳过 → Redisson 令牌桶（按 webhook）
   └─ file/image_key 类型先查素材缓存，未命中则上传
   └─ 邮件渠道：拉产物做附件 + HTML 正文，校验收件人白名单
   └─ 逐条记录成败

⑧ 归档：产物上传存储，写逻辑 URI

⑨ 收尾：汇总状态（SUCCESS/PARTIAL/FAILED）→ 清理本地临时文件
   └─ 失败且可重试 → RETRY_WAIT + next_retry_at
   └─ 重试耗尽 → 告警（去重静默）
```

### 7.2 ALERT：数据监控（M4）

```
① Quartz 按评估周期写 PENDING（task_type=ALERT）
② Worker 领取 → 执行监控 SQL（走同一套校验/审计/保护）
③ 条件评估：value 与阈值比较 → 本次判定 hit / miss
④ grace period：连续 N 次 hit 才认定 TRIGGERED
⑤ 读 hp_alert_state：
   └─ 状态未变 → 更新 last_value，结束（不通知）
   └─ OK→TRIGGERED → 发告警通知（触发值/阈值/时间/任务链接）
   └─ TRIGGERED→OK → 发恢复通知
⑥ 更新 hp_alert_state + 写执行日志
```

### 7.3 FANOUT：个性化分发（M5）

```
① 父执行触发 → 加载任务快照 → 执行清单 SQL（或读静态清单）
② 校验清单行数 ≤ 上限(200) → 扇出 N 个子执行（PENDING，parent_exec_id）
③ 子执行被 Worker 正常领取（与独立任务同队列，天然并行/重试/幂等）
   └─ 清单行字段注入为该子执行的运行时参数
   └─ 渲染（水印可引用行字段）→ 推送（邮件点对点 / 群 @ 成员）
④ 父执行汇总子执行状态：全成 SUCCESS / 部分 PARTIAL_SUCCESS / 全败 FAILED
```

---

## 八、分期交付计划

### M1　端到端最短闭环
数据源管理（含加密）· 数据集 + 时间变量 + SQL 校验 + 预览 · Markdown 产物 · 企微 text/markdown 推送 + 限流 · Quartz + 执行队列 + Worker · 执行日志 · **Webhook 白名单** · 最简前端

> 加密和白名单放 M1：事后给已有明文数据补加密很麻烦。

**验收**：配一个任务，每天定时把昨日汇总发到测试群，失败可在日志里查明原因。（对应 EasySQLMail 场景1"自动日报"的企微子集）

### M2　Excel 与版本管理
FileStorage 抽象 + LOCAL + S3(OSS/MinIO) · 模板库与模板版本 · EasyExcel 模板填充（含几万行分批）· 任务配置快照版本 + 固版 + diff + 回滚 · 企微 file 推送 + media_id 缓存 · 产物归档与保留期清理

**验收**：Excel 报表定时生成并作为附件推送；改模板后可回滚到上一版本。（EasySQLMail 场景1 完整覆盖）

### M3　转图 + PDF + 水印
IMAGE 产物模型 · **HTML_SHOT provider（默认，含自动生成表格模板）** · LibreOffice provider · Aspose provider（可选加载）· **PDF 产物（HTML_PRINT / LIBREOFFICE）** · **图片水印** · 长图切分与压缩 · **行数上限与降级策略** · 渲染效果对比页

**验收**：同一任务三种 provider 都能出图，对比页并排评估，超行数自动降级，PDF 可作邮件附件备用。（EasySQLMail"多呈现方式：HTML/Excel/PDF/图片"全覆盖）

### M4　治理 + 多渠道 + 监控告警
Sa-Token RBAC · 数据源级授权 · SQL 审计查询页 · **配置变更审计 hp_oplog** · **账户安全（锁定/登录日志/事件通知）** · 失败告警 + 去重静默 · 心跳巡检与僵死执行恢复 · 运行时人工入参与补数重跑 · **钉钉/飞书/邮件渠道（含收件人白名单）** · **ALERT 任务类型** · 完整 Vue 前端（任务编排向导）

**验收**：非管理员只能操作被授权数据源；任务失败运维群收到一条（不是十条）告警；能补跑上月 15 日的报表；库存低于阈值 5 分钟内群里收到告警、恢复后收到恢复通知；同一报表可同时发企微群+钉钉群+邮件。（EasySQLMail 场景2"多端送达"、场景3"数据监控"覆盖）

### M5　个性化分发 + 审核 + 扩展
**FANOUT 个性化分发** · **报表审核（WAIT_APPROVAL + 审核工作台）** · **GENERIC_WEBHOOK 渠道** · HDFS 存储模块 · 指标与监控面板

**验收**：配置分发清单后，20 个分公司经理各自收到只含本分公司数据的专属报表（邮件点对点+水印），单个分公司失败不影响其余；重要报表可设"先审核后推送"。（EasySQLMail 场景4"个性化分发"、报表审核功能覆盖）

### 远期演进（不进 M1–M5，对应 EasySQLMail"未来发展方向"）
告警升级链（N 分钟未恢复升级通知）· 任务步骤编排（HTTP 命令完整版，含企微审批流对接）· PDF 加密/禁打印 · 插件化架构 · AI 辅助（SQL 生成/数据解读）· Excel 路线水印 · 更多数据库类型（SQL Server 等，EasySQLMail 已支持，视需求提级）

---

## 九、开源项目对标分析

调研结论：**没有一个开源项目同时覆盖"SQL→模板→多格式→多渠道推送"全链路**（最接近的是商业产品 EasySQLMail），但各家的分块实现都可借鉴，且验证了本方案多个关键设计决策。

### 9.1 对标矩阵

| 项目 | 语言/栈 | 定时调度 | 查询→渲染 | 转图/PDF | 渠道 | 监控告警 | 版本管理 |
|---|---|---|---|---|---|---|---|
| **datart**（宜信） | Java/Spring Boot + Quartz | ✅ | 可视化作品 | 独立截图服务（PhantomJS/Chrome headless 渲染分享链接） | 邮件、企微群机器人 | ❌ | ❌ |
| **Apache Superset** | Python/Celery | ✅ Beat 调度 + Worker 执行分离 | 图表/仪表盘 | Playwright → PNG/PDF | Slack/Email | ✅ 阈值+grace period+去重 | ❌ |
| **Metabase** | Clojure + Quartz | ✅ 应用内 Quartz（`send_pulses`） | 订阅可带参数 | 静态渲染卡片，63 版加 PDF | Email/Slack/Webhook | ✅ Alerts | ❌ |
| **Redash** | Python/Celery | ✅ scheduler 30s 扫描 + Celery 队列 | SQL 查询/仪表盘 | 图表快照 | Email/Slack/Webhook/PagerDuty 等 Destinations | ✅ **状态跃迁才通知**（本方案 ALERT 语义来源） | ❌ |
| **DolphinScheduler**（Apache） | Java | ✅ 工作流调度 | SQL 任务节点 + 跨节点传参 | ❌ | **告警插件化：Email/钉钉/企微/飞书/HTTP/Script**（本方案渠道 SPI 参照） | 工作流级 | ❌ |
| **DataEase**（飞致云） | Java | ✅ 定时报告 | 仪表板报告 | 截图 | 邮件/企微/钉钉/飞书（**X-Pack 收费**，企微走企业应用） | ❌ | ❌ |
| **JimuReport**（积木） | Java | ✅ 定时导出 | 报表设计器 | Excel/PDF/Word/图片 | 仅邮件 | ❌ | ❌ |
| **Cc_ETL** | Java/XXL-Job | ✅ 可视化编排 | SQL 任务 | ❌ | 邮件/Webhook | ❌ | ❌ |
| **EasySQLMail**（商业，对标基准） | Java | ✅ 分钟~月 | **SQL → Excel 模板** | **Excel→PNG/PDF** | **邮件/企微/钉钉/飞书** | ✅ 含升级链 | 配置审计 |

### 9.2 可借鉴点

**datart —— 技术栈最接近**　Quartz + 企微 webhook + 图片/Excel 两种内容类型；图片走"渲染网页再截图"（不捆绑 Chrome，部署问题高发区）；Excel 只导原始数据无模板填充。一任务一内容的局限正是产物模型要避免的。

**Apache Superset —— 架构模式标杆**　调度（Beat）与执行（Worker）彻底分离；Alert 的 grace period + 日志去重验证 SC-6/SC-9；截图从 Selenium 迁到 Playwright；"发不了图就发链接"的降级思路。

**Metabase —— 订阅产品语义最完整**　"Don't send if there's no results"→ SC-5；订阅级参数覆盖 → SQL-2；附件行数上限 → RD-7；重复发送 bug #45622 → PS-5 幂等键；Quartz 故障 issue 反证"Quartz 只管投递、状态机在业务表"。

**Redash —— ALERT 语义来源**　Alert = 定时查询的某列 vs 阈值，**仅状态跃迁（OK→TRIGGERED→OK）时通知**，最干净地避免重复轰炸；Alert Destinations 插件化（Email/Slack/Webhook/PagerDuty）；scheduler 每 30s 扫描 + Celery 队列投递，与 SC-1 同构；webhook secret 加密存储 → SEC-1。

**DolphinScheduler —— 渠道 SPI 参照**　告警模块独立服务 + `AlertPluginManager` 插件化，内置 Email/钉钉/企微/飞书/HTTP/Script 六渠道；钉钉/飞书均为 webhook+加签模式，与 PS-1"三家群机器人共享框架"的设计一致；HTTP 任务节点是 GENERIC_WEBHOOK 的完整形态（我们做简化版）。

**DataEase / JimuReport —— 反衬空白点**　DataEase 定时报告收费且企微走重量级企业应用；JimuReport 只到邮件。"群机器人 webhook 推 Excel/图片"开源界基本没人做好——本项目的差异化价值。

**EasySQLMail（商业）—— 需求验证与功能基准**　形态与本项目高度一致且更宽（个性化分发、报表审核、HTTP 命令）。商业闭源说明需求真实、开源缺位。逐项对照见 9.4。

**组件级参考**　[message-spring-boot-starter](https://github.com/wb04307201/message-spring-boot-starter)（四渠道+邮件 starter）、[group-robot](https://github.com/ymlluo/group-robot)：M4 扩展钉钉/飞书时参考消息模型；核心渠道自研（限流、素材缓存、部分成功等精细控制通用 SDK 覆盖不了）。

### 9.3 对标得出的设计决策

1. **IMAGE 默认 provider = `HTML_SHOT`（已确认）**。业界全部走"网页截图"路线，无成熟开源项目做 xlsx→image。配套"自动生成默认 HTML 表格模板"（RD-4）消解额外模板成本。LIBREOFFICE/ASPOSE 作为"文件与图同源"补充路线。
2. **转图/PDF 服务接口保持可独立部署**（datart 独立截图服务、Superset 独立 worker）。
3. **ALERT 采用 Redash 状态跃迁模型**（SC-9），而非 Superset 的"每次满足都告警+静默期"——前者语义更干净，用户不会漏掉恢复通知。
4. **渠道 SPI 参照 DolphinScheduler 告警插件架构**（PS-1），五渠道类型分里程碑交付。
5. **企微官方限制已核实**：20 条/分钟、media_id 3 天、text/markdown/image/file/news（[官方文档](https://developer.work.weixin.qq.com/document/path/91770)）。

### 9.4 EasySQLMail 功能对照表（需求来源追溯）

| EasySQLMail 能力（PPT） | 本平台对应 | 里程碑 |
|---|---|---|
| 多渠道：邮件/企微/钉钉/飞书 | PS-1/PS-2/PS-2b/PS-6 五渠道类型 | M1(企微) / M4(其余) |
| 多格式：HTML/Excel/PDF/图片 | RD-2(Markdown/HTML) / RD-3 / RD-9 / RD-4 | M1–M3 |
| SQL 数据源对接现有系统 | DS-1、SQL-1~6 | M1 |
| 定时：分钟/小时/天/星期/月 | SC-1、SC-2 | M1 |
| 数据监控告警（条件触发） | SC-9 ALERT 任务类型 | M4 |
| 告警升级链（1 小时未解除升级） | 远期演进（简化版：SC-9 升级策略） | 远期 |
| 个性化报表分发（一人一报） | SC-10 FANOUT | M5 |
| 交互式查询界面（自助取数） | **明确不做**（非目标，避免滑向 BI） | — |
| Web API / HTTP 命令 | PS-7 GENERIC_WEBHOOK（简化版）；完整步骤编排远期 | M5 / 远期 |
| 报表审核（人工确认后推送） | SC-3 WAIT_APPROVAL + 审核工作台 | M5 |
| 安全：AES 加密存储 | SEC-1 | M1 |
| 安全：推送范围可控（地址簿白名单） | PS-4 webhook 白名单 + PS-6 收件人白名单 | M1 / M4 |
| 安全：水印图片报表 | RD-10 | M3 |
| 安全：PDF 密码/禁打印复制 | 远期演进 | 远期 |
| 安全：只发部分数据（汇总） | SQL 本身可实现，无需专门功能 | — |
| 安全：IP 白名单/HTTPS 双向/账户锁定/验证码/事件通知 | SEC-6（应用层）+ 部署手册（网络层） | M4 |
| 配置修改完整审计 | SEC-3 SQL 审计 + SEC-5 配置变更审计 | M1 / M4 |
| 多用户 B/S、任务集中管理、岗位交接 | SEC-4 RBAC + 任务版本快照（3.4） | M1–M4 |
| 未来：工作流自动化/插件化/AI 工具箱 | 远期演进章节 | 远期 |

---

## 十、风险与待确认事项

### 开放问题（需业务方确认）

| # | 问题 | 影响 |
|---|---|---|
| 1 | **AES-GCM 主密钥放哪**：环境变量 / 启动参数 / KMS？ | 阻塞 M1 |
| 2 | **Aspose 是否已有商业 License**？没有则 M3 只交付 HTML_SHOT + LibreOffice | 影响 M3 范围 |
| 3 | **HDFS 是否启用 Kerberos**？启用则需 keytab 管理方案 | 影响 M5 |
| 4 | 部署方式：物理机 / K8s 容器？容器化需把 Chromium、中文字体、LibreOffice 打进镜像（体积约 1GB+），或转图走独立 renderer 服务 | 影响 M3 交付形态 |

~~5. IMAGE 默认 provider~~ → 已确认 HTML_SHOT（9.3）。
~~6. 渠道/ALERT/FANOUT/PDF/水印/审核范围~~ → 已确认（三、四、八节）。

### 技术风险

| 风险 | 缓解 |
|---|---|
| LibreOffice 转换样式还原度不足 | 已降为补充路线（默认 HTML_SHOT）；M3 早期 spike；对比页评估；保留降级为文件推送 |
| LibreOffice 并发崩溃 | 每进程独立 `UserInstallation` + 信号量限并发 + 超时强杀 |
| Chromium/Playwright 内存占用与并发 | 浏览器进程池 + 并发信号量 + 单页超时 |
| 中文字体缺失出方块 | 部署清单强制检查项（转图/PDF 共同风险） |
| 容器镜像体积大、冷启动慢 | 转图能力拆独立 renderer 服务（演进路径，v1 不做） |
| EasyExcel 多数据集填充语法限制 | M2 早期 spike；必要时约束"一个 sheet 一个列表" |
| 三家机器人 API 行为/限额与文档不符 | 限流阈值与消息格式适配层配置化，不硬编码 |
| 数据外泄（平台本质是"任意 SQL + 结果外发"） | 只读账号 + 数据源级授权 + webhook/收件人白名单 + SQL 审计 + 配置审计，五层叠加 |
| FANOUT 子执行风暴（清单 SQL 误配返回大结果集） | 子执行上限 200 + 清单行数预检 + 并发度限制 |
| ALERT 高频评估压垮数据源 | 评估 SQL 同样受超时/行数保护；最小周期 1 分钟；监控 SQL 建议走从库 |

---

## 十一、下一步

1. 业务方终审本文档（重点：9.4 对照表确认 EasySQLMail 场景全覆盖或明确取舍；第三节领域模型对照 2–3 个真实报表走查）
2. 回答第十节 4 个开放问题（#1 阻塞 M1）
3. 终审通过后进入 **M1 实施计划**编写（writing-plans），拆成可 TDD 执行的任务序列
4. M1 前建议两个技术验证 spike：Playwright 截图/PDF 在目标部署环境的可行性、EasyExcel 多数据集填充语法

---

## 附：参考来源

- [datart 定时任务官方文档](https://running-elephant.github.io/datart-docs/docs/schedule.html) · [datart GitHub](https://github.com/running-elephant/datart)
- [Apache Superset Alerts and Reports](https://superset.apache.org/admin-docs/configuration/alerts-reports/) · [Superset Architecture](https://superset.apache.org/admin-docs/installation/architecture/)
- [Metabase Dashboard Subscriptions](https://www.metabase.com/docs/latest/dashboards/subscriptions) · [send_pulses.clj](https://github.com/metabase/metabase/blob/master/src/metabase/task/send_pulses.clj) · [重复发送 bug #45622](https://github.com/metabase/metabase/issues/45622)
- [Redash Alerts 文档](https://redash.io/help/user-guide/alerts/setting-up-an-alert/) · [Redash Alert Destinations](https://redash.io/help/user-guide/alerts/creating-new-alert-destination/) · [Redash 调度机制](https://redash.io/help/user-guide/querying/scheduling-a-query/)
- [DolphinScheduler 告警插件玩法](https://www.cnblogs.com/DolphinScheduler/p/20054284) · [飞书告警配置](https://www.bookstack.cn/read/dolphinscheduler-3.1.9-zh/docs-zh-guide-alert-feishu.md) · [Alert 模块源码解析](https://segmentfault.com/a/1190000046599478)
- [DataEase 定时报告（X-Pack）](https://dataease.cn/docs/v2/xpack/sys_management_report/) · [JimuReport 报表定时导出](https://help.jimureport.com/exportJob) · [DataGear](https://github.com/datageartech/datagear) · [Cc_ETL](https://gitee.com/xzjsccz/Cc_ETL)
- [企业微信群机器人官方文档](https://developer.work.weixin.qq.com/document/path/91770)
- [EasySQLMail 官网](https://www.easysqlmail.com/)（商业对标基准，功能清单提取自 `docs/EasySQLMAIL软件简介.pptx`）
- [EasyExcel](https://github.com/alibaba/easyexcel) · [message-spring-boot-starter](https://github.com/wb04307201/message-spring-boot-starter) · [group-robot](https://github.com/ymlluo/group-robot)