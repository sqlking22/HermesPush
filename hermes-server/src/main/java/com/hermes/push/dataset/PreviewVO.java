package com.hermes.push.dataset;

import java.util.List;
import java.util.Map;

/**
 * 数据预览响应。
 *
 * @param totalRows 本次实际返回行数（受 200 上限截断，等于 rows.size()），不是满足条件的总行数；
 *                  truncated=true 表示命中上限。M2 若需真实总量另行引入 COUNT 预估或改名 returnedRows。
 */
public record PreviewVO(
    List<ColumnMeta> columns,
    List<Map<String, Object>> rows,
    int totalRows,
    boolean truncated,
    long costMs) {}
