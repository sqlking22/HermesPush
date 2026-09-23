package com.hermes.push.dataset;

import com.hermes.push.audit.SqlAuditScene;
import com.hermes.push.audit.SqlAuditService;
import com.hermes.push.common.BizException;
import com.hermes.push.datasource.Datasource;
import com.hermes.push.datasource.DatasourceService;
import com.hermes.push.datasource.QueryEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PreviewService {
  private final DatasourceService dsSvc;
  private final DruidSqlValidator validator;
  private final ParamResolver params;
  private final QueryEngine engine;
  private final SqlAuditService audit;
  private final JdbcTemplate jdbc;

  public PreviewVO preview(Long dsId, PreviewRequest req, String operator, String ip) {
    Datasource ds = dsSvc.getEnabled(dsId);
    try {
      validator.validate(req.sql(), ds.getType());
    } catch (BizException e) {
      audit.recordValidationFailed(dsId, req.sql(), operator, ip,
          e.getErrorCode().getCode() + " " + e.getDetail());
      throw e;
    }
    LocalDate runDate = jdbc.queryForObject("SELECT CURDATE()", LocalDate.class);
    PreparedSql p = params.prepare(req.sql(), List.of(), Map.of(),
        req.params() == null ? Map.of() : req.params(), runDate, -1);
    long t0 = System.nanoTime();
    DatasetResult r = engine.execute(ds, p, 200, null);
    long cost = (System.nanoTime() - t0) / 1_000_000;
    audit.record(SqlAuditScene.PREVIEW, dsId, req.sql(), p.auditParams(),
        r.totalRows(), cost, operator, null, ip);
    return new PreviewVO(r.columns(), r.toList(200), r.totalRows(), r.truncated(), cost);
  }
}
