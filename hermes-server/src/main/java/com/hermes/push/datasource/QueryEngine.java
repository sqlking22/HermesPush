package com.hermes.push.datasource;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.dataset.ColumnMeta;
import com.hermes.push.dataset.DatasetResult;
import com.hermes.push.dataset.MaterializedDatasetResult;
import com.hermes.push.dataset.PreparedSql;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QueryEngine {
  private final HikariPoolRegistry pools;

  public QueryEngine(HikariPoolRegistry pools) {
    this.pools = pools;
  }

  public DatasetResult execute(Datasource ds, PreparedSql sql, int rowLimitOverride, Long execId) {
    int limit = rowLimitOverride > 0 ? rowLimitOverride : ds.getMaxRows();
    HikariDataSource pool = pools.getOrCreate(ds);
    try (Connection c = pool.getConnection();
         PreparedStatement ps = c.prepareStatement(sql.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
      ps.setQueryTimeout(ds.getQueryTimeoutSec());
      ps.setFetchSize(1000);
      ps.setMaxRows(limit + 1); // 多取一行判定截断
      for (int i = 0; i < sql.bindValues().size(); i++) {
        ps.setObject(i + 1, sql.bindValues().get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        ResultSetMetaData md = rs.getMetaData();
        List<ColumnMeta> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
          cols.add(new ColumnMeta(md.getColumnLabel(i), md.getColumnTypeName(i)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
          if (rows.size() >= limit) {
            truncated = true;
            break;
          }
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= cols.size(); i++) {
            row.put(cols.get(i - 1).name(), mapValue(rs, i));
          }
          rows.add(row);
        }
        if (truncated && "FAIL".equals(ds.getOverflowPolicy())) {
          throw new BizException(ErrorCode.SQL_003, "行数超限(" + limit + ")且策略为 FAIL");
        }
        return new MaterializedDatasetResult(cols, rows, truncated);
      }
    } catch (BizException e) {
      throw e;
    } catch (SQLTimeoutException e) {
      throw new BizException(ErrorCode.DS_002, "查询超时(" + ds.getQueryTimeoutSec() + "s)");
    } catch (SQLException e) {
      // MySQL 驱动的查询超时可能包装为普通 SQLException（含 "Statement cancelled due to timeout"）
      String msg = e.getMessage() == null ? "" : e.getMessage();
      if (msg.contains("timeout") || msg.contains("Timeout")) {
        throw new BizException(ErrorCode.DS_002, "查询超时(" + ds.getQueryTimeoutSec() + "s)");
      }
      throw new BizException(ErrorCode.DS_002, e.getMessage());
    }
  }

  private Object mapValue(ResultSet rs, int i) throws SQLException {
    Object v = rs.getObject(i);
    if (v == null) return null;
    if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    if (v instanceof LocalDateTime ldt) return ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    if (v instanceof LocalDate ld) return ld.toString();
    return v;
  }
}
