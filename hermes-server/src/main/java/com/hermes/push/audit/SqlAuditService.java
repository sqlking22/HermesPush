package com.hermes.push.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlAuditService {

  private static final Set<String> SENSITIVE_PREFIXES = Set.of("pwd", "secret", "idcard", "phone");
  /** TEXT 列上限 65535 字节，留约 5KB 安全余量按 60000 字节截断 */
  private static final int SQL_TEXT_MAX_BYTES = 60000;

  private final SqlAuditMapper mapper;
  private final ObjectMapper objectMapper;

  public void record(SqlAuditScene scene, Long datasourceId, String sqlText,
                     Map<String, String> params, Integer rows, Long costMs,
                     String operator, Long execId, String clientIp) {
    try {
      SqlAudit audit = new SqlAudit();
      audit.setScene(scene.name());
      audit.setDatasourceId(datasourceId);
      audit.setSqlText(truncateSqlText(sqlText));
      audit.setParamsJson(serializeWithMask(params));
      audit.setRowsReturned(rows);
      audit.setCostMs(costMs);
      audit.setOperator(operator);
      audit.setExecId(execId);
      audit.setClientIp(clientIp);
      mapper.insert(audit);
    } catch (Exception e) {
      log.error("SQL audit record failed, scene={}, datasourceId={}, operator={}, execId={}",
          scene, datasourceId, operator, execId, e);
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

  /**
   * 按 UTF-8 字节数截断 sqlText，保证写入 TEXT 列（65535 字节上限）不溢出。
   * 截断后追加标记 "...[TRUNCATED, original N chars]"，标记本身计入长度预算。
   */
  private String truncateSqlText(String sqlText) {
    if (sqlText == null) return null;
    byte[] bytes = sqlText.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= SQL_TEXT_MAX_BYTES) {
      return sqlText;
    }
    int originalLen = sqlText.length();
    String markerPrefix = "...[TRUNCATED, original ";
    String markerSuffix = " chars]";
    // 预留标记长度（数字部分按 10 位估算，足够覆盖 Integer.MAX_VALUE）
    int reserve = markerPrefix.length() + 10 + markerSuffix.length();
    int budget = SQL_TEXT_MAX_BYTES - reserve;
    if (budget <= 0) budget = SQL_TEXT_MAX_BYTES / 2;

    // 从 budget 位置向前回溯找到完整 UTF-8 字符边界
    int cutPos = budget;
    while (cutPos > 0 && (bytes[cutPos] & 0xC0) == 0x80) {
      cutPos--;
    }
    String truncated = new String(bytes, 0, cutPos, StandardCharsets.UTF_8);
    String marker = markerPrefix + originalLen + markerSuffix;
    return truncated + marker;
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
