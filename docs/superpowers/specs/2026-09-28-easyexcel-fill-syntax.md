# EasyExcel（FastExcel）模板填充语法冻结规范与编写指南

- **日期**：2026-09-28
- **来源**：M2 前置 spike（见 `2026-09-28-easyexcel-fill-syntax-spike.md` 计划 + `spike/easyexcel/` POC）
- **状态**：**已冻结**。FR-RD-03 实现按本文执行。

## 一、结论速览

| 形态 | 占位符写法 | 机制 |
|---|---|---|
| 单列表（一个 sheet 一个列表） | `{.字段名}` | FastExcel 原生：`doFill(List)` |
| 多列表（同 sheet 多个列表） | `{数据集名.字段名}` | `FillWrapper("数据集名", List)` + `forceNewRow(true)` |
| 单值（标量，取首行） | `{数据集名.字段名}` | **平台在填充分前预替换**，不进 FastExcel |

核心发现：**点号形式 `{x.y}` 在 FastExcel 中固定为"列表别名"语义**，不能直接作标量。因此单值与多列表虽同形，靠**任务配置里数据集绑定类型**（列表/单值）区分，单值一律预替换。

## 二、库选型结论

- `com.alibaba:easyexcel` 已于 2025-09 被阿里归档（最后版本 4.0.3 / 2024-09，不跟进 JDK 21、POI 5.x）。
- 冻结使用其后继 **`cn.idev.excel:fastexcel`（版本 1.3.0）**：API 与 EasyExcel 兼容（主类 `cn.idev.excel.FastExcel`，fill API 在 `cn.idev.excel.write.metadata.fill.*`），JDK 21 实测通过（本 spike 以 `--release 21` 编译运行）。
- 远期演进：FastExcel 已捐 Apache 为 **Apache Fesod（incubating）**；孵化期结束、坐标稳定后评估升级，语法不受影响。

## 三、冻结的占位符语法规范

### 3.1 列表填充（单列表）

```text
模板单元格： {.字段名}
```

- 配 `FastExcel.write(out).withTemplate(tpl).sheet().doFill(List<Map<String,Object>>)`。
- **List 元素用 Map**（key = 字段名），直接对映 SQL 结果集（列名→值），无需为每个模板建 POJO。
- 占位符上方的表头行照常写在模板里，填充时原样保留；列表从占位符行起向下展开。

### 3.2 多列表填充（同 sheet）

```text
模板单元格： {数据集名.字段名}
```

- 每个数据集一个 `FillWrapper("数据集名", List)`，`FillConfig.builder().forceNewRow(true)`。
- 多个列表依次同 sheet 填充，或用 `WriteDirectionEnum.HORIZONTAL` 横向填充。

### 3.3 单值填充（标量）

```text
模板单元格： {数据集名.字段名}      ← 注意：与多列表同形
```

- **由平台的"数据绑定/渲染"层在填充分前解析并预替换**为具体字符串（取该数据集第一行对应字段），随后模板进入 FastExcel 时已无此占位符。
- **绝对禁止**把点号单值直接交给 FastExcel 的 `doFill(Map)`（spike 实测：被当作列表别名，产物单元格为空，见 3.4）。
- 若不需要数据集限定（如任务级参数、当前日期），可用非点号 `{字段名}` 走 FastExcel 原生标量 `doFill(Map)`。

### 3.4 无歧义保证（冻结约束）

1. 每个数据集在一次任务渲染中，**只绑定为"列表"或"单值"一种**；绑定在任务配置里显式声明。
2. 单值占位符**必须在 FastExcel 填充分之前完成预替换**，替换后不残留点号 `{...}`。
3. 多列表 `{数据集.字段}` 与单值 `{数据集.字段}` 的同形冲突，靠 1+2 消除，不引入新语法。

## 四、验证证据（spike 实测）

| # | 验证点 | 结果 | 证据 |
|---|---|---|---|
| 0 | 库坐标/版本/JDK21 | 通过 | `cn.idev.excel:fastexcel:1.3.0` 正常解析，release 21 编译运行 |
| 1 | 单列表 `{.字段}` | 通过 | 表头+3 行数据精确落位 |
| 2 | 多列表 `{别名.字段}` + forceNewRow | 通过 | A/B 两列表同 sheet，4 行正确 |
| 3a | 单值 非点号 `{字段}` | 通过 | `{title}`→"昨日汇总" |
| 3b | 单值 点号 `{数据集.字段}` 直接 map-fill | **失败** | 产物 `[[]]`（单元格空），印证"点号=列表别名" |
| 4 | 5 万行 × 20 列分批填充 | 通过 | 3.5s / 堆增量 154MB（目标 30s / 1GB） |
| 5 | 产物回读正确性 | 通过 | 表头+100 行精确落位 |

POC 位置：`spike/easyexcel/`（独立 Maven 模块，`mvnw -f spike/easyexcel/pom.xml test` 复现，不进主 build，交付后可删）。

## 五、模板编写指南

### 5.1 单列表明细模板

```text
A1: 姓名        B1: 金额
A2: {.name}    B2: {.amount}
```

- 第一行写表头（字面量），第二行写 `.字段` 占位符。
- 只需给一列数据：用 List<Map>，key 与 `.字段` 同名。

### 5.2 多列表模板（同 sheet 两段报表）

```text
A1: {a.name}   B1: {a.sales}
A2: {b.name}   B2: {b.sales}
```

- 两个数据集分别 `FillWrapper("a", …)`、`FillWrapper("b", …)`，均 `forceNewRow(true)`。

### 5.3 单值（标题 / 生成时间 / 汇总数）

- 用 `{数据集名.字段名}` 或非点号 `{字段名}`，交给**平台预替换**（不要在模板里期望 FastExcel 处理点号单值）。
- 典型：`{summary.total}`、`{meta.generatedAt}` → 渲染层填入"总金额 1234"、"2026-09-28"。

### 5.4 大行数（>1 万行）

- **禁止全量物化**：用迭代器分批，每批 ≤5000 行 `List<Map>`，循环 `writer.fill(batch, sheet)` 追加。
- 已实测 5 万行 × 20 列：3.5s、堆增量 ~154MB。生产在 8 核环境更从容。

## 六、性能记录

| 场景 | 实测 | 基准（FR-RD-03） | 环境 |
|---|---|---|---|
| 5 万行 × 20 列模板填充 | 3.5s | ≤30s | 4 核 16G（本机；基准写 8 核，缩小口径） |
| 堆内存峰值增量 | ~154MB | ≤1GB | 同上 |

## 七、生产衔接注意点

1. **结果集 → List<Map>** 是自然映射（M1 已约定"读满物化 List"，M2 Excel 渲染可直接消费；几万行场景在 `DatasetResult` 实现类上扩展迭代器分批，不改接口）。
2. **模板存储**：Excel 模板（.xlsx）上传到 FileStorage，渲染时下载到本地临时文件再 `withTemplate`（FastExcel 需本地 File 路径）。
3. **单值预替换**必须先行于列表填充，避免残留点号占位符被误判为列表别名。
4. **数字精度/格式**：填充值类型与 Excel 单元格格式由模板自行控制（数字单元格、日期格式等），本 spike 未展开，FR-RD-03 实现时以真实模板复测。
5. **日志噪声**：FastExcel 依赖 log4j-api 但无 provider，运行时报 "Log4j API could not find a logging provider"，生产需引入 log4j-to-slf4j 或排除。