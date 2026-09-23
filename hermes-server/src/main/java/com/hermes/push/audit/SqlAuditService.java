package com.hermes.push.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlAuditService {

  private static final Set<String> SENSITIVE_PREFIXES = Set.of("pwd", "secret", "idcard", "phone");

  private final SqlAuditMapper mapper;
  private final ObjectMapper objectMapper;

  public void record(SqlAuditScene scene, Long datasourceId, String sqlText,
                     Map<String, String> params, Integer rows, Long costMs,
                     String operator, Long execId, String clientIp) {
    try {
      SqlAudit audit = new SqlAudit();
      audit.setScene(scene.name());
      audit.setDatasourceId(datasourceId);
      audit.setSqlText(sqlText);
      audit.setParamsJson(serializeWithMask(params));
      audit.setRowsReturned(rows);
      audit.setCostMs(costMs);
      audit.setOperator(operator);
      audit.setExecId(execId);
      audit.setClientIp(clientIp);
      mapper.insert(audit);
    } catch (Exception e) {
      log.error("SQL audit record failed, scene={}, datasourceId={}", scene, datasourceId, e);
    }
  }

  public void recordValidationFailed(Long datasourceId, String sqlText,
                                     String operator, String clientIp, String reason) {
    try {
      Map<String, String> params = new HashMap<>();
      params.put("reason", reason);
      record(SqlAuditScene.VALIDATION_FAILED, datasourceId, sqlText, params, null, null, operator, null, clientIp);
    } catch (Exception e) {
      log.error("SQL audit recordValidationFailed failed, datasourceId={}", datasourceId, e);
    }
  }

  private String serializeWithMask(Map<String, String> params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    Map<String, String> masked = new HashMap<>(params.size());
    for (Map.Entry<String, String> e : params.entrySet()) {
      String key = e.getKey();
      masked.put(key, isSensitive(key) ? "***" : e.getValue());
    }
    try {
      return objectMapper.writeValueAsString(masked);
    } catch (JsonProcessingException ex) {
      throw new RuntimeException("failed to serialize params_json", ex);
    }
  }

  private boolean isSensitive(String key) {
    if (key == null) return false;
    String lower = key.toLowerCase(Locale.ROOT);
    for (String prefix : SENSITIVE_PREFIXES) {
      if (lower.startsWith(prefix)) return true;
    }
    return false;
  }
}
