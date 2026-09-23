package com.hermes.push.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** M1 实现：数据全量物化在内存 List 中（上限内）。M2 再引入 ResultSet 直通迭代器，接口不变。 */
public class MaterializedDatasetResult implements DatasetResult {
  private final List<ColumnMeta> columns;
  private final List<Map<String, Object>> rows;
  private final boolean truncated;

  public MaterializedDatasetResult(List<ColumnMeta> columns, List<Map<String, Object>> rows, boolean truncated) {
    this.columns = columns;
    this.rows = rows;
    this.truncated = truncated;
  }

  @Override
  public List<ColumnMeta> columns() {
    return columns;
  }

  @Override
  public int totalRows() {
    return rows.size();
  }

  @Override
  public boolean truncated() {
    return truncated;
  }

  @Override
  public void forEachRow(Consumer<Map<String, Object>> consumer) {
    rows.forEach(consumer);
  }

  @Override
  public List<Map<String, Object>> toList(int max) {
    if (max <= 0 || max >= rows.size()) return rows;
    return new ArrayList<>(rows.subList(0, max));
  }
}
