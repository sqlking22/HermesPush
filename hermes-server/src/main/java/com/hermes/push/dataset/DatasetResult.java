package com.hermes.push.dataset;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface DatasetResult {
  List<ColumnMeta> columns();
  int totalRows();
  boolean truncated();
  void forEachRow(Consumer<Map<String, Object>> consumer);
  List<Map<String, Object>> toList(int max);
}
