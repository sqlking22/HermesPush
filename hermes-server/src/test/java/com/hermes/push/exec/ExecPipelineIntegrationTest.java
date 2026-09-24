package com.hermes.push.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.channel.ChannelService;
import com.hermes.push.datasource.DatasourceSaveRequest;
import com.hermes.push.datasource.DatasourceService;
import com.hermes.push.datasource.HikariPoolRegistry;
import com.hermes.push.task.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class ExecPipelineIntegrationTest extends AbstractIntegrationTest {
  static WireMockServer wm;
  @Autowired ExecPipeline pipeline; @Autowired ExecQueueRepository queue;
  @Autowired TaskService tasks; @Autowired DatasourceService dsSvc; @Autowired ChannelService chSvc;
  @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;
  @Autowired HikariPoolRegistry poolRegistry;

  @BeforeAll static void startWm() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
  @BeforeAll static void clearPools(@Autowired HikariPoolRegistry registry) { registry.clearAll(); }
  @AfterAll static void stopWm() { wm.stop(); }
  @BeforeEach void reset() { wm.resetAll(); wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    jdbc.update("DELETE FROM hp_whitelist WHERE value='localhost'");
    jdbc.update("INSERT INTO hp_whitelist(type,value,created_by) VALUES('WEBHOOK_HOST','localhost','test')"); }

  // 记录最近一次 setup 创建的 dsId，用于清理连接池（避免全量测试时连接耗尽）
  Long lastDsId;
  @org.junit.jupiter.api.AfterEach void cleanupPool() {
    if (lastDsId != null) { poolRegistry.evict(lastDsId); lastDsId = null; }
  }

  record Ctx(Long dsId, Long channelId, Long taskId, Long versionId) {}
  Ctx setup(String sql, String template) throws Exception {
    jdbc.execute("CREATE TABLE IF NOT EXISTS sales(dt DATE, branch VARCHAR(32), amount DECIMAL(12,2))");
    jdbc.execute("INSERT IGNORE INTO sales VALUES ('2026-09-21','成都',312004.00),('2026-09-21','绵阳',208771.00)");
    Long dsId = dsSvc.save(new DatasourceSaveRequest("e2e-" + System.nanoTime(), "MYSQL", TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, true, null, 30, 1, null), "admin");
    lastDsId = dsId;
    Long chId = chSvc.save("测试群" + System.nanoTime(), "WEWORK_BOT",
        "{\"webhook\":\"http://localhost:" + wm.port() + "/cgi-bin/webhook/send?key=e2e\"}", 20, 5, true, "admin");
    var cfg = new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", dsId, sql, List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", template, "TRUNCATE", 4096)),
      List.of(new TaskConfig.ChannelBinding(chId, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef("0 0 9 * * ?", -1, 10, 3, false));
    Long taskId = tasks.create("E2E日报", "e2e-" + System.nanoTime(), cfg, null, "admin");
    return new Ctx(dsId, chId, taskId, tasks.loadEffectiveConfig(taskId).taskVersionId());
  }
  Long enqueue(Ctx c, TriggerType trig, String paramsJson, LocalDate bizDate) {
    return queue.insertPending(c.taskId, c.versionId, trig, 70,
        java.time.LocalDateTime.now(), bizDate, paramsJson, "k-" + System.nanoTime());
  }
  void runOne() {
    Long id = queue.claim("node-test").orElseThrow();
    pipeline.run(queue.getById(id));
  }

  @Test void trialRendersWithoutPush() throws Exception {
    Ctx c = setup("SELECT branch, amount FROM sales WHERE dt = #{bizDate}",
        "**日报 ${bizDate}**\n<#list ds1.rows as r>- ${r.branch}: ${fmtNumber(r.amount)}\n</#list>");
    Long execId = enqueue(c, TriggerType.TRIAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    TaskExec e = queue.getById(execId);
    assertThat(e.getStatus()).isEqualTo("SUCCESS");
    String content = jdbc.queryForObject("SELECT content FROM hp_task_exec_artifact WHERE exec_id=?", String.class, execId);
    assertThat(content).contains("成都: 312,004");
    wm.verify(0, postRequestedFor(anyUrl())); // 试运行不推送
    Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='TRIAL'", Integer.class);
    assertThat(audits).isGreaterThanOrEqualTo(1);
  }
  @Test void manualTriggerPushesToWecom() throws Exception {
    Ctx c = setup("SELECT branch, amount FROM sales WHERE dt = #{bizDate}",
        "<#list ds1.rows as r>${r.branch} ${fmtNumber(r.amount)}\n</#list>");
    Long execId = enqueue(c, TriggerType.MANUAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    assertThat(queue.getById(execId).getStatus()).isEqualTo("SUCCESS");
    wm.verify(1, postRequestedFor(urlPathEqualTo("/cgi-bin/webhook/send"))
        .withRequestBody(matchingJsonPath("$.msgtype", equalTo("markdown"))
        .and(matchingJsonPath("$.markdown.content", containing("成都 312,004")))));
    Integer pushes = jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec_push WHERE exec_id=? AND status='SUCCESS'", Integer.class, execId);
    assertThat(pushes).isEqualTo(1);
  }
  @Test void retryReusesExecAndSkipsSucceededPush() throws Exception {
    Ctx c = setup("SELECT branch FROM sales WHERE dt = #{bizDate}", "<#list ds1.rows as r>${r.branch}\n</#list>");
    Long execId = enqueue(c, TriggerType.MANUAL, "{}", LocalDate.of(2026, 9, 21));
    wm.resetAll(); wm.stubFor(post(anyUrl()).willReturn(serverError())); // 第一次推送 5xx
    runOne();
    TaskExec e = queue.getById(execId);
    assertThat(e.getStatus()).isEqualTo("RETRY_WAIT");
    assertThat(e.getRetryCount()).isEqualTo(1);
    assertThat(e.getErrorCode()).isEqualTo("PUSH-011");
    // 恢复 stub，模拟巡检 promote 后重跑同一 execId
    wm.resetAll(); wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    jdbc.update("UPDATE hp_task_exec SET status='PENDING', node_id=NULL WHERE id=?", execId);
    runOne();
    assertThat(queue.getById(execId).getStatus()).isEqualTo("SUCCESS");
    assertThat(queue.getById(execId).getId()).isEqualTo(execId); // 同一执行记录（评审修订 B1）
    wm.verify(1, postRequestedFor(anyUrl())); // 仅失败渠道重发一次
  }
  @Test void badSqlFailsNonRetryableWithAudit() throws Exception {
    Ctx c = setup("SELCT bad syntax", "x");
    Long execId = enqueue(c, TriggerType.MANUAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    TaskExec e = queue.getById(execId);
    assertThat(e.getStatus()).isEqualTo("FAILED");
    assertThat(e.getErrorCode()).startsWith("SQL-");
    assertThat(e.getRetryCount()).isZero(); // 不可重试
    Integer vf = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='VALIDATION_FAILED'", Integer.class);
    assertThat(vf).isGreaterThanOrEqualTo(1);
  }
  @Test void oversizedMarkdownTruncatedInPipeline() throws Exception {
    // 构造超长模板 + maxBytes=200/TRUNCATE，验证流水线 enforceBytes 生效
    StringBuilder longTpl = new StringBuilder("# 日报\n");
    for (int i = 0; i < 50; i++) longTpl.append("${bizDate} 第").append(i).append("行内容填充\n");
    String sql = "SELECT branch, amount FROM sales WHERE dt = #{bizDate}";
    jdbc.execute("CREATE TABLE IF NOT EXISTS sales(dt DATE, branch VARCHAR(32), amount DECIMAL(12,2))");
    jdbc.execute("INSERT IGNORE INTO sales VALUES ('2026-09-21','成都',312004.00),('2026-09-21','绵阳',208771.00)");
    Long dsId = dsSvc.save(new DatasourceSaveRequest("e2e-" + System.nanoTime(), "MYSQL", TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, true, null, 30, 1, null), "admin");
    lastDsId = dsId;
    Long chId = chSvc.save("测试群" + System.nanoTime(), "WEWORK_BOT",
        "{\"webhook\":\"http://localhost:" + wm.port() + "/cgi-bin/webhook/send?key=e2e\"}", 20, 5, true, "admin");
    var cfg = new TaskConfig(
      List.of(new TaskConfig.DatasetDef("ds1", dsId, sql, List.of())),
      List.of(new TaskConfig.ArtifactDef("a1", "MARKDOWN", longTpl.toString(), "TRUNCATE", 200)),
      List.of(new TaskConfig.ChannelBinding(chId, List.of("a1"), "markdown")),
      new TaskConfig.ScheduleDef("0 0 9 * * ?", -1, 10, 3, false));
    Long taskId = tasks.create("E2E日报", "e2e-" + System.nanoTime(), cfg, null, "admin");
    Long versionId = tasks.loadEffectiveConfig(taskId).taskVersionId();
    Ctx c = new Ctx(dsId, chId, taskId, versionId);
    Long execId = enqueue(c, TriggerType.TRIAL, "{}", LocalDate.of(2026, 9, 21));
    runOne();
    assertThat(queue.getById(execId).getStatus()).isEqualTo("SUCCESS");
    String content = jdbc.queryForObject("SELECT content FROM hp_task_exec_artifact WHERE exec_id=?", String.class, execId);
    assertThat(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
    assertThat(content).endsWith("…（内容超长已截断）");
  }
}
