package com.hermes.push.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TaskControllerTest extends AbstractIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper om;

  static class NoopSyncPort implements ScheduleSyncPort {
    final AtomicInteger onPublishCnt = new AtomicInteger();
    final AtomicInteger onOfflineCnt = new AtomicInteger();
    final AtomicInteger onCronChangeCnt = new AtomicInteger();
    @Override public void onPublish(Long taskId, String cron) { onPublishCnt.incrementAndGet(); }
    @Override public void onOffline(Long taskId) { onOfflineCnt.incrementAndGet(); }
    @Override public void onCronChange(Long taskId, String cron) { onCronChangeCnt.incrementAndGet(); }
    void reset() { onPublishCnt.set(0); onOfflineCnt.set(0); onCronChangeCnt.set(0); }
  }

  @TestConfiguration
  static class TestSyncConfig {
    @Bean @Primary
    NoopSyncPort testSyncPort() { return new NoopSyncPort(); }
  }

  String sampleConfig(String cron) {
    return "{" +
        "\"datasets\":[{\"key\":\"ds1\",\"datasourceId\":1,\"sql\":\"SELECT 1 AS n\",\"params\":[]}]," +
        "\"artifacts\":[{\"key\":\"a1\",\"type\":\"MARKDOWN\",\"inlineTemplate\":\"结果: ${ds1.rows[0].n}\",\"overflowStrategy\":\"TRUNCATE\",\"maxBytes\":4096}]," +
        "\"channelBindings\":[{\"channelId\":1,\"artifactKeys\":[\"a1\"],\"msgType\":\"markdown\"}]," +
        "\"schedule\":{\"cron\":\"" + cron + "\",\"bizOffsetDays\":-1,\"timeoutMinutes\":10,\"maxRetry\":3,\"jitterEnabled\":false}" +
        "}";
  }

  @Test
  void createListPublishFlow() throws Exception {
    // 1. create
    String body = "{\"name\":\"日报A\",\"taskKey\":\"daily-a\",\"config\":" + sampleConfig("0 0 9 * * ?") + ",\"remark\":\"init\"}";
    mvc.perform(post("/api/tasks")
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.taskId").exists());

    // 2. list 查到
    mvc.perform(get("/api/tasks").param("page", "1").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.records.length()").value(1))
        .andExpect(jsonPath("$.data.records[0].name").value("日报A"))
        .andExpect(jsonPath("$.data.records[0].status").value("DRAFT"))
        .andExpect(jsonPath("$.data.records[0].currentVersionNo").value(1));

    // 3. 插 TRIAL SUCCESS 记录
    Long taskId = jdbc.queryForObject("SELECT id FROM hp_task WHERE task_key='daily-a'", Long.class);
    Long versionId = jdbc.queryForObject("SELECT current_version_id FROM hp_task WHERE id=?", Long.class, taskId);
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))",
        taskId, versionId);

    // 4. publish 成功 → status=ONLINE
    mvc.perform(post("/api/tasks/" + taskId + "/publish"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    String status = jdbc.queryForObject("SELECT status FROM hp_task WHERE id=?", String.class, taskId);
    assertThat(status).isEqualTo("ONLINE");
  }

  @Test
  void publishWithoutTrialShowsGateError() throws Exception {
    String body = "{\"name\":\"日报B\",\"taskKey\":\"daily-b\",\"config\":" + sampleConfig("0 0 9 * * ?") + "}";
    mvc.perform(post("/api/tasks")
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk());
    Long taskId = jdbc.queryForObject("SELECT id FROM hp_task WHERE task_key='daily-b'", Long.class);

    // 未试运行 → publish 被拒
    mvc.perform(post("/api/tasks/" + taskId + "/publish"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.data.detail").value(org.hamcrest.Matchers.containsString("试运行")));
  }

  @Test
  void listNextFireComputed() throws Exception {
    String body = "{\"name\":\"日报C\",\"taskKey\":\"daily-c\",\"config\":" + sampleConfig("0 0 9 * * ?") + "}";
    mvc.perform(post("/api/tasks")
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk());
    Long taskId = jdbc.queryForObject("SELECT id FROM hp_task WHERE task_key='daily-c'", Long.class);
    Long versionId = jdbc.queryForObject("SELECT current_version_id FROM hp_task WHERE id=?", Long.class, taskId);
    jdbc.update("INSERT INTO hp_task_exec(task_id,task_version_id,trigger_type,priority,status,fire_time) VALUES(?,?, 'TRIAL',70,'SUCCESS',NOW(3))",
        taskId, versionId);
    jdbc.update("UPDATE hp_task SET status='ONLINE' WHERE id=?", taskId);

    String resp = mvc.perform(get("/api/tasks").param("page", "1").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.records[0].nextFire").exists())
        .andExpect(jsonPath("$.data.records[0].nextFire").isNotEmpty())
        .andReturn().getResponse().getContentAsString();

    Map json = om.readValue(resp, Map.class);
    String nf = (String) ((Map) ((List) ((Map) json.get("data")).get("records")).get(0)).get("nextFire");
    assertThat(nf).isNotNull();
    LocalDateTime next = LocalDateTime.parse(nf);
    assertThat(next).isAfter(LocalDateTime.now());
  }
}
