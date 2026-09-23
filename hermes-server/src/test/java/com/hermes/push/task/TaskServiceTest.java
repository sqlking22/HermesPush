package com.hermes.push.task;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.dataset.ParamType;
import com.hermes.push.dataset.ParameterDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TaskServiceTest extends AbstractIntegrationTest {
  @Autowired TaskService svc; @Autowired JdbcTemplate jdbc;

  TaskConfig cfg(String cron) {
    return new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", 1L, "SELECT 1 AS n", List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", "结果: ${ds1.rows[0].n}", "TRUNCATE", 4096)),
      List.of(new TaskConfig.ChannelBinding(1L, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef(cron, -1, 10, 3, false));
  }

  @Test void createMakesVersion1() {
    Long id = svc.create("日报", "daily-" + System.nanoTime(), cfg("0 0 9 * * ?"), "init", "admin");
    var eff = svc.loadEffectiveConfig(id);
    assertThat(eff.config().datasets()).hasSize(1);
    assertThat(eff.taskVersionId()).isNotNull();
  }
  @Test void publishWithoutTrialRejected() {
    Long id = svc.create("日报2", "daily2-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    assertThatThrownBy(() -> svc.publish(id)).isInstanceOf(BizException.class).hasMessageContaining("试运行");
  }
  @Test void publishWithTrialSucceeds() {
    Long id = svc.create("日报3", "daily3-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    var eff = svc.loadEffectiveConfig(id);
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))", id, eff.taskVersionId());
    svc.publish(id);
    assertThat(svc.loadEffectiveConfig(id)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT status FROM hp_task WHERE id=?", String.class, id)).isEqualTo("ONLINE");
  }
  @Test void optimisticLockConflict() {
    Long id = svc.create("日报4", "daily4-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    svc.saveVersion(id, cfg("0 0 10 * * ?"), "v2", 0, "admin");           // lock 0->1
    assertThatThrownBy(() -> svc.saveVersion(id, cfg("0 0 11 * * ?"), "v3", 0, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("SYS-004");
  }
  @Test void paramCollidingWithBuiltinRejected() {
    var bad = new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", 1L, "SELECT #{bizDate}",
          List.of(new ParameterDefinition("bizDate", ParamType.DATE, false, null, false, null)))),
      cfg("0 0 9 * * ?").artifacts(), cfg("0 0 9 * * ?").channelBindings(), cfg("0 0 9 * * ?").schedule());
    assertThatThrownBy(() -> svc.create("坏任务", "bad-" + System.nanoTime(), bad, null, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("内置时间变量");
  }
}
