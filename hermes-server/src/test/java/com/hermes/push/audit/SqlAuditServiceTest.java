package com.hermes.push.audit;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SqlAuditServiceTest extends AbstractIntegrationTest {
  @Autowired SqlAuditService audit;
  @Autowired JdbcTemplate jdbc;

  @Test void recordExecScene() {
    audit.record(SqlAuditScene.EXEC, 1L, "SELECT 1", Map.of("bizDate","2026-09-21"), 1, 12L, "exec", 100L, "10.0.0.9");
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='EXEC'", Integer.class);
    assertThat(n).isGreaterThanOrEqualTo(1);
  }
  @Test void sensitiveParamMasked() {
    Map<String,String> p = new HashMap<>(); p.put("phoneNo","13800001111"); p.put("bizDate","2026-09-21");
    audit.record(SqlAuditScene.PREVIEW, 1L, "SELECT 1", p, 1, 1L, "admin", null, "127.0.0.1");
    String json = jdbc.queryForObject("SELECT params_json FROM hp_sql_audit WHERE scene='PREVIEW' ORDER BY id DESC LIMIT 1", String.class);
    assertThat(json).doesNotContain("13800001111").contains("***").contains("2026-09-21");
  }
  @Test void validationFailedRecorded() {
    audit.recordValidationFailed(1L, "DROP TABLE x", "admin", "127.0.0.1", "SQL-002 只允许单条 SELECT");
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='VALIDATION_FAILED'", Integer.class);
    assertThat(n).isEqualTo(1);
  }
  @Test void oversizedSqlTruncatedNotLost() {
    String bigSql = "SELECT '" + "x".repeat(70000) + "'";
    audit.record(SqlAuditScene.EXEC, 1L, bigSql, Map.of(), 0, 5L, "admin", 200L, "10.0.0.1");
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='EXEC'", Integer.class);
    assertThat(n).isGreaterThanOrEqualTo(1);
    String stored = jdbc.queryForObject("SELECT sql_text FROM hp_sql_audit WHERE scene='EXEC' ORDER BY id DESC LIMIT 1", String.class);
    assertThat(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65535);
    assertThat(stored).contains("[TRUNCATED").endsWith("]");
  }
}
