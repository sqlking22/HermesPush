package com.hermes.push;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class HermesApplicationTest {
  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayMigratesAllM1Tables() {
    for (String t : new String[]{"hp_datasource","hp_channel","hp_whitelist","hp_task",
        "hp_task_version","hp_task_exec","hp_task_exec_push","hp_task_exec_artifact",
        "hp_sql_audit","hp_user","QRTZ_JOB_DETAILS"}) {
      Integer n = jdbc.queryForObject(
          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='hermes_test' AND table_name=?",
          Integer.class, t);
      assertThat(n).as("table %s exists", t).isEqualTo(1);
    }
  }

  @Test
  void execQueueUniqueIndexWorks() {
    jdbc.update("DELETE FROM hp_task_exec");
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES (1,1,'CRON',40,'PENDING','2026-01-01 09:00:00.000')");
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES (1,1,'CRON',40,'PENDING','2026-01-02 09:00:00.000')");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec", Integer.class)).isEqualTo(2);
    // 同 (task,fire_time,trigger_type,idem='') 重复插入必须被 uk_fire 拒绝
    assertThrows(Exception.class, () -> jdbc.update(
        "INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES (1,1,'CRON',40,'PENDING','2026-01-01 09:00:00.000')"));
    // 生成列工作正常
    String eff = jdbc.queryForObject("SELECT idem_key_eff FROM hp_task_exec WHERE fire_time='2026-01-01 09:00:00.000'", String.class);
    assertThat(eff).isEmpty();
  }
}
