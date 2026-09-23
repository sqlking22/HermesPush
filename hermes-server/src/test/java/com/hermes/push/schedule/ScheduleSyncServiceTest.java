package com.hermes.push.schedule;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.task.TaskConfig;
import com.hermes.push.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

@TestPropertySource(properties = "spring.quartz.auto-startup=true")
class ScheduleSyncServiceTest extends AbstractIntegrationTest {
  @Autowired TaskService tasks; @Autowired ScheduleSyncService sync; @Autowired JdbcTemplate jdbc;

  TaskConfig cfg(String cron) {
    return new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", 1L, "SELECT 1 AS n", List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", "n=${ds1.rows[0].n}", "TRUNCATE", 4096)),
      List.of(new TaskConfig.ChannelBinding(1L, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef(cron, -1, 10, 3, false));
  }

  @Test void publishSchedulesQuartzTrigger() {
    Long id = tasks.create("qz1", "qz1-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    var eff = tasks.loadEffectiveConfig(id);
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))", id, eff.taskVersionId());
    tasks.publish(id);
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE TRIGGER_NAME=?", Integer.class, "hermes-task-" + id);
    assertThat(n).isEqualTo(1);
    tasks.offline(id);
    n = jdbc.queryForObject("SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE TRIGGER_NAME=?", Integer.class, "hermes-task-" + id);
    assertThat(n).isZero();
  }
  @Test void manualJobFireInsertsPendingExec() {
    Long id = tasks.create("qz2", "qz2-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    sync.onPublish(id, "0 0 9 * * ?");
    sync.triggerNow(id); // 测试辅助：scheduler.triggerJob
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec WHERE task_id=? AND trigger_type='CRON'", Integer.class, id);
      assertThat(n).isEqualTo(1);
    });
  }
  @Test void jitterDeterministicPerDay() {
    JitterCalculator j = new JitterCalculator();
    int a = j.offsetSeconds(7L, LocalDate.of(2026,9,22));
    int b = j.offsetSeconds(7L, LocalDate.of(2026,9,22));
    int c = j.offsetSeconds(8L, LocalDate.of(2026,9,22));
    assertThat(a).isEqualTo(b).isBetween(0, 299);
    // 不同任务大概率不同（sha256），允许碰撞，仅验证范围
    assertThat(c).isBetween(0, 299);
  }
}
