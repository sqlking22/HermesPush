package com.hermes.push.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExecLogControllerTest extends AbstractIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper om;

  @Test
  void listWithFilters() throws Exception {
    // 造任务 + 版本
    jdbc.update("INSERT INTO hp_task(id,name,task_key,task_type,status,cron_expr,owner) VALUES (101,'任务A','t-a','REPORT','ONLINE','0 0 9 * * ?','admin')");
    jdbc.update("INSERT INTO hp_task(id,name,task_key,task_type,status,cron_expr,owner) VALUES (102,'任务B','t-b','REPORT','ONLINE','0 0 10 * * ?','admin')");
    jdbc.update("INSERT INTO hp_task_version(id,task_id,version_no,config_json) VALUES (1,101,1,'{}')");
    jdbc.update("INSERT INTO hp_task_version(id,task_id,version_no,config_json) VALUES (2,102,3,'{}')");

    // 3 条 exec，不同 status/task
    jdbc.update("INSERT INTO hp_task_exec(id,task_id,task_version_id,trigger_type,priority,status,fire_time,biz_date,rows_total,cost_ms,error_code,node_id) " +
        "VALUES (201,101,1,'CRON',40,'SUCCESS','2025-09-21 09:00:00','2025-09-21',100,500,NULL,'node-1')");
    jdbc.update("INSERT INTO hp_task_exec(id,task_id,task_version_id,trigger_type,priority,status,fire_time,biz_date,rows_total,cost_ms,error_code,node_id) " +
        "VALUES (202,101,1,'MANUAL',60,'FAILED','2025-09-21 10:00:00','2025-09-21',NULL,800,'PUSH-011','node-1')");
    jdbc.update("INSERT INTO hp_task_exec(id,task_id,task_version_id,trigger_type,priority,status,fire_time,biz_date,rows_total,cost_ms,error_code,node_id) " +
        "VALUES (203,102,2,'CRON',40,'RUNNING','2025-09-21 11:00:00','2025-09-21',NULL,NULL,NULL,'node-2')");

    // 按 status=SUCCESS 过滤
    mvc.perform(get("/api/execs").param("status", "SUCCESS").param("page", "1").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.records.length()").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(201))
        .andExpect(jsonPath("$.data.records[0].taskName").value("任务A"))
        .andExpect(jsonPath("$.data.records[0].ver").value(1))
        .andExpect(jsonPath("$.data.total").value(1));

    // 按 taskId=101 过滤
    mvc.perform(get("/api/execs").param("taskId", "101").param("page", "1").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records.length()").value(2));

    // 按 triggerType 过滤
    mvc.perform(get("/api/execs").param("triggerType", "MANUAL").param("page", "1").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records.length()").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(202));
  }

  @Test
  void detailShape() throws Exception {
    // 任务 + 渠道 + 版本
    jdbc.update("INSERT INTO hp_task(id,name,task_key,task_type,status,cron_expr,owner) VALUES (101,'任务A','t-a','REPORT','ONLINE','0 0 9 * * ?','admin')");
    jdbc.update("INSERT INTO hp_task_version(id,task_id,version_no,config_json) VALUES (1,101,1,'{}')");
    jdbc.update("INSERT INTO hp_channel(id,name,type,config_cipher,test_flag,status,deleted) VALUES (501,'企业微信A','WECOM_BOT','x',0,'ACTIVE',0)");

    // exec + stage_costs_json
    jdbc.update("INSERT INTO hp_task_exec(id,task_id,task_version_id,trigger_type,priority,status,fire_time,biz_date,rows_total,cost_ms,error_code,node_id,stage_costs_json) " +
        "VALUES (301,101,1,'CRON',40,'SUCCESS','2025-09-21 09:00:00','2025-09-21',100,500,NULL,'node-1','{\"queueMs\":100,\"queryMs\":200,\"renderMs\":300,\"pushMs\":400}')");

    // artifact
    jdbc.update("INSERT INTO hp_task_exec_artifact(id,exec_id,artifact_key,type,render_provider,rows_count,bytes,content,storage_uri,cost_ms,error_code,error_msg) " +
        "VALUES (401,301,'main','MARKDOWN','FREEMARKER',100,2048,'hello world',NULL,300,NULL,NULL)");

    // push
    jdbc.update("INSERT INTO hp_task_exec_push(id,exec_id,channel_id,artifact_key,msg_type,status,retry_count,error_code,error_msg,sent_at) " +
        "VALUES (601,301,501,'main','markdown','SUCCESS',0,NULL,NULL,'2025-09-21 09:00:05')");

    mvc.perform(get("/api/execs/301"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.exec.id").value(301))
        .andExpect(jsonPath("$.data.exec.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.artifacts.length()").value(1))
        .andExpect(jsonPath("$.data.artifacts[0].artifactKey").value("main"))
        .andExpect(jsonPath("$.data.artifacts[0].type").value("MARKDOWN"))
        .andExpect(jsonPath("$.data.artifacts[0].renderProvider").value("FREEMARKER"))
        .andExpect(jsonPath("$.data.artifacts[0].rowsCount").value(100))
        .andExpect(jsonPath("$.data.artifacts[0].bytes").value(2048))
        .andExpect(jsonPath("$.data.artifacts[0].content").value("hello world"))
        .andExpect(jsonPath("$.data.artifacts[0].costMs").value(300))
        .andExpect(jsonPath("$.data.pushes.length()").value(1))
        .andExpect(jsonPath("$.data.pushes[0].channelId").value(501))
        .andExpect(jsonPath("$.data.pushes[0].channelName").value("企业微信A"))
        .andExpect(jsonPath("$.data.pushes[0].artifactKey").value("main"))
        .andExpect(jsonPath("$.data.pushes[0].msgType").value("markdown"))
        .andExpect(jsonPath("$.data.pushes[0].status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.pushes[0].retryCount").value(0))
        .andExpect(jsonPath("$.data.pushes[0].sentAt").exists())
        .andExpect(jsonPath("$.data.stageCosts.queueMs").value(100))
        .andExpect(jsonPath("$.data.stageCosts.queryMs").value(200))
        .andExpect(jsonPath("$.data.stageCosts.renderMs").value(300))
        .andExpect(jsonPath("$.data.stageCosts.pushMs").value(400))
        .andExpect(jsonPath("$.data.sqlVisible").value(true));

    // 不存在的 exec → SYS_003
    mvc.perform(get("/api/execs/9999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.data.errorCode").value("SYS-003"));
  }

  @Test
  void todaySummaryShape() throws Exception {
    jdbc.update("INSERT INTO hp_task(id,name,task_key,task_type,status,cron_expr,owner) VALUES (101,'任务A','t-a','REPORT','ONLINE','0 0 9 * * ?','admin')");
    jdbc.update("INSERT INTO hp_task_version(id,task_id,version_no,config_json) VALUES (1,101,1,'{}')");

    // 今日 SUCCESS
    jdbc.update("INSERT INTO hp_task_exec(id,task_id,task_version_id,trigger_type,priority,status,fire_time,biz_date,rows_total,cost_ms,node_id) " +
        "VALUES (701,101,1,'CRON',40,'SUCCESS',CURDATE(),CURDATE(),100,500,'node-1')");
    // 今日 FAILED（用不同 fire_time 避免 uk_fire 冲突）
    jdbc.update("INSERT INTO hp_task_exec(id,task_id,task_version_id,trigger_type,priority,status,fire_time,biz_date,cost_ms,error_code,node_id) " +
        "VALUES (702,101,1,'CRON',40,'FAILED',DATE_ADD(CURDATE(), INTERVAL 1 HOUR),CURDATE(),800,'PUSH-011','node-1')");

    mvc.perform(get("/api/execs/today-summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.todayTotal").value(2))
        .andExpect(jsonPath("$.data.todaySuccess").value(1))
        .andExpect(jsonPath("$.data.todayFailed").value(1))
        .andExpect(jsonPath("$.data.todayRunning").value(0))
        .andExpect(jsonPath("$.data.nextTriggers").isArray())
        .andExpect(jsonPath("$.data.nextTriggers.length()").value(0));
  }
}
