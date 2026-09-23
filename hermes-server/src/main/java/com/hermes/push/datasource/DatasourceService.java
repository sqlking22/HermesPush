package com.hermes.push.datasource;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.security.AesGcmCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
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
    Datasource d = require(id);
    if (!enable) {
      Integer refs = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT t.id) FROM hp_task t JOIN hp_task_version v ON v.id = t.current_version_id " +
        "JOIN JSON_TABLE(v.config_json, '$.datasets[*]' COLUMNS (datasourceId BIGINT PATH '$.datasourceId')) ds " +
        "WHERE t.status='ONLINE' AND ds.datasourceId = ?",
        Integer.class, id);
      if (refs != null && refs > 0)
        throw new BizException(ErrorCode.SYS_002, "存在 " + refs + " 个上线任务引用该数据源，请先下线相关任务");
    }
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
