package com.hermes.push.dataset;

import java.util.List;
import java.util.Map;

public record PreviewVO(
    List<ColumnMeta> columns,
    List<Map<String, Object>> rows,
    int totalRows,
    boolean truncated,
    long costMs) {}
