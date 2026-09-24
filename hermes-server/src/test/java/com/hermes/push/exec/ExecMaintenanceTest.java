package com.hermes.push.exec;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "hermes.maintenance.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExecMaintenanceTest extends AbstractIntegrationTest {

  @Autowired ExecMaintenance maintenance;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() {
    jdbc.update("DELETE FROM hp_task_exec");
  }

  @Test void promoteDueRetry() {
    jdbc.update(
        "INSERT INTO hp_task_exec (task_id, task_version_id, trigger_type, priority, status, fire_time, biz_date, params_json, idempotency_key, next_retry_at, retry_count, max_retry) "
        + "VALUES (1, 1, 'CRON', 50, 'RETRY_WAIT', NOW(3), ?, '{}', 'promote-1', NOW(3)-INTERVAL 1 SECOND, 1, 3)",
        LocalDate.now());
    maintenance.promoteRetries();
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM hp_task_exec WHERE idempotency_key='promote-1'");
    assertThat(row.get("status")).isEqualTo("PENDING");
  }

  @Test void lostRunningWithRetryBudgetRecovered() {
    jdbc.update(
        "INSERT INTO hp_task_exec (task_id, task_version_id, trigger_type, priority, status, fire_time, biz_date, params_json, idempotency_key, heartbeat_at, node_id, retry_count, max_retry) "
        + "VALUES (1, 1, 'CRON', 50, 'RUNNING', NOW(3), ?, '{}', 'lost-1', NOW(3)-INTERVAL 3 MINUTE, 'dead-node', 0, 3)",
        LocalDate.now());
    int affected = maintenance.recoverLostRunning();
    assertThat(affected).isEqualTo(1);
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM hp_task_exec WHERE idempotency_key='lost-1'");
    assertThat(row.get("status")).isEqualTo("RETRY_WAIT");
    assertThat(row.get("error_code")).isEqualTo("NODE_LOST");
    assertThat(row.get("retry_count")).isEqualTo(1);
    assertThat(row.get("node_id")).isNull();
    assertThat(row.get("heartbeat_at")).isNull();
    assertThat(row.get("next_retry_at")).isNotNull();
  }

  @Test void lostRunningExhaustedFailed() {
    // 新鲜 RUNNING 不会被误伤
    jdbc.update(
        "INSERT INTO hp_task_exec (task_id, task_version_id, trigger_type, priority, status, fire_time, biz_date, params_json, idempotency_key, heartbeat_at, node_id, retry_count, max_retry) "
        + "VALUES (1, 1, 'CRON', 50, 'RUNNING', NOW(3), ?, '{}', 'fresh-1', NOW(3), 'alive-node', 0, 3)",
        LocalDate.now());
    // 心跳失联且重试耗尽
    jdbc.update(
        "INSERT INTO hp_task_exec (task_id, task_version_id, trigger_type, priority, status, fire_time, biz_date, params_json, idempotency_key, heartbeat_at, node_id, retry_count, max_retry) "
        + "VALUES (1, 1, 'CRON', 50, 'RUNNING', NOW(3), ?, '{}', 'exhaust-1', NOW(3)-INTERVAL 3 MINUTE, 'dead-node', 3, 3)",
        LocalDate.now());
    int affected = maintenance.recoverLostRunning();
    assertThat(affected).isEqualTo(1);
    // 新鲜的保留
    Map<String, Object> fresh = jdbc.queryForMap("SELECT * FROM hp_task_exec WHERE idempotency_key='fresh-1'");
    assertThat(fresh.get("status")).isEqualTo("RUNNING");
    // 耗尽的失败
    Map<String, Object> exhaust = jdbc.queryForMap("SELECT * FROM hp_task_exec WHERE idempotency_key='exhaust-1'");
    assertThat(exhaust.get("status")).isEqualTo("FAILED");
    assertThat(exhaust.get("error_code")).isEqualTo("NODE_LOST");
    assertThat(exhaust.get("cost_ms")).isNotNull();
  }
}
