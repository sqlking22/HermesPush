package com.hermes.push.task;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.dataset.ParamType;
import com.hermes.push.dataset.ParameterDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TaskServiceTest extends AbstractIntegrationTest {
  @Autowired TaskService svc; @Autowired JdbcTemplate jdbc;
  @Autowired CountingSyncPort syncPort;

  static class CountingSyncPort implements ScheduleSyncPort {
    final AtomicInteger onPublishCnt = new AtomicInteger();
    final AtomicInteger onOfflineCnt = new AtomicInteger();
    final AtomicInteger onCronChangeCnt = new AtomicInteger();
    @Override public void onPublish(Long taskId, String cron) { onPublishCnt.incrementAndGet(); }
    @Override public void onOffline(Long taskId) { onOfflineCnt.incrementAndGet(); }
    @Override public void onCronChange(Long taskId, String cron) { onCronChangeCnt.incrementAndGet(); }
    void reset() { onPublishCnt.set(0); onOfflineCnt.set(0); onCronChangeCnt.set(0); }
    int cronChangeCount() { return onCronChangeCnt.get(); }
  }

  @TestConfiguration
  static class TestSyncConfig {
    @Bean @Primary
    CountingSyncPort testSyncPort() {
      return new CountingSyncPort();
    }
  }

  @BeforeEach
  void resetCounters() {
    syncPort.reset();
  }

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
  @Test void saveVersionUsesFreshStatusForCronSync() {
    Long id = svc.create("日报5", "daily5-" + System.nanoTime(), cfg("0 0 9 * * ?"), null, "admin");
    var eff = svc.loadEffectiveConfig(id);
    // DRAFT 状态下 saveVersion，onCronChange 不应调用
    svc.saveVersion(id, cfg("0 0 10 * * ?"), "v2", 0, "admin");
    assertThat(syncPort.cronChangeCount()).isEqualTo(0);
    // 为 v1 和 v2 各插一条 TRIAL SUCCESS 记录（publish 用的是 pinned??current 版本）
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))", id, eff.taskVersionId());
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))", id, svc.loadEffectiveConfig(id).taskVersionId());
    svc.publish(id);
    // ONLINE 状态下 saveVersion，onCronChange 应调用（当前为 v2 lock=1，saveVersion 后 lock=2）
    svc.saveVersion(id, cfg("0 0 11 * * ?"), "v3", 1, "admin");
    assertThat(syncPort.cronChangeCount()).isEqualTo(1);
    // offline 后再 saveVersion，onCronChange 不应再调用
    svc.offline(id);
    int before = syncPort.cronChangeCount();
    svc.saveVersion(id, cfg("0 0 12 * * ?"), "v4", 2, "admin");
    assertThat(syncPort.cronChangeCount()).isEqualTo(before);
  }
}
