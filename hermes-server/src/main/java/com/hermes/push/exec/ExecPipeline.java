package com.hermes.push.exec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.audit.SqlAuditScene;
import com.hermes.push.audit.SqlAuditService;
import com.hermes.push.channel.ChannelService;
import com.hermes.push.channel.DecryptedChannel;
import com.hermes.push.channel.PushChannelRegistry;
import com.hermes.push.channel.PushMessage;
import com.hermes.push.channel.PushResult;
import com.hermes.push.common.BizException;
import com.hermes.push.dataset.DatasetResult;
import com.hermes.push.dataset.DruidSqlValidator;
import com.hermes.push.dataset.ParamResolver;
import com.hermes.push.dataset.PreparedSql;
import com.hermes.push.datasource.Datasource;
import com.hermes.push.datasource.DatasourceService;
import com.hermes.push.datasource.QueryEngine;
import com.hermes.push.render.MarkdownRenderer;
import com.hermes.push.render.RenderedArtifact;
import com.hermes.push.task.TaskConfig;
import com.hermes.push.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行流水线：查询 → 渲染 → 推送 → 重试/终态。
 * <p>9 步顺序冻结，重试复用同一 execId（幂等四元组防重发）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecPipeline {

  private final TaskService tasks;
  private final DatasourceService dsSvc;
  private final DruidSqlValidator validator;
  private final ParamResolver params;
  private final QueryEngine engine;
  private final SqlAuditService audit;
  private final MarkdownRenderer renderer;
  private final ExecQueueRepository queue;
  private final ExecPushRepository pushRepo;
  private final ExecArtifactRepository artifactRepo;
  private final ChannelService channels;
  private final PushChannelRegistry registry;
  private final JdbcTemplate jdbc;
  private final ObjectMapper om;

  @Value("${hermes.render.timeout-sec:30}")
  private int renderTimeoutSec;

  public void run(TaskExec exec) {
    StageCosts sc = new StageCosts();
    long t0 = System.nanoTime();
    try {
      // queueMs: fire_time → claim 的数据库时间差
      Long queueMs = jdbc.queryForObject(
          "SELECT TIMESTAMPDIFF(MICROSECOND, fire_time, NOW(3))/1000 FROM hp_task_exec WHERE id=?",
          Long.class, exec.getId());
      sc = new StageCosts(queueMs != null ? queueMs : 0, 0, 0, 0);

      // 1. loadEffectiveConfig，校验 task_version_id 一致（TRIAL/TEST 用 current 版本，不强制一致）
      TaskService.EffectiveConfig eff = tasks.loadEffectiveConfig(exec.getTaskId());
      if (exec.getTriggerType() != null
          && !TriggerType.TRIAL.name().equals(exec.getTriggerType())
          && !TriggerType.TEST.name().equals(exec.getTriggerType())) {
        if (!eff.taskVersionId().equals(exec.getTaskVersionId())) {
          throw new BizException(com.hermes.push.common.ErrorCode.SYS_003,
              "执行版本 v" + exec.getTaskVersionId() + " 与当前版本 v" + eff.taskVersionId() + " 不一致");
        }
      }

      // 2. 解析 bizDate 与 runtime params
      LocalDate runDate = jdbc.queryForObject("SELECT CURDATE()", LocalDate.class);
      Map<String, String> runtime = readParams(exec.getParamsJson());
      TaskConfig.ScheduleDef schedule = eff.config().schedule();
      int offset = schedule.bizOffsetDays() != null ? schedule.bizOffsetDays() : 0;
      if (exec.getBizDate() != null) {
        offset = (int) (exec.getBizDate().toEpochDay() - runDate.toEpochDay());
      }
      // 将 bizDate 以字符串形式注入 runtime params，供渲染器 bizDate 变量使用
      runtime.putIfAbsent("bizDate", runDate.plusDays(offset).toString());

      // 阶段1：查询
      long q0 = System.nanoTime();
      Map<String, DatasetResult> results = new LinkedHashMap<>();
      for (var ds : eff.config().datasets()) {
        if (Thread.interrupted()) { Thread.currentThread().interrupt(); return; }
        Datasource datasource = dsSvc.getEnabled(ds.datasourceId());
        try {
          validator.validate(ds.sql(), datasource.getType());
        } catch (BizException e) {
          audit.recordValidationFailed(ds.datasourceId(), ds.sql(),
              "exec:" + exec.getId(), null,
              e.getErrorCode().getCode() + " " + e.getDetail());
          throw e;
        }
        PreparedSql p = params.prepare(ds.sql(), ds.params(), Map.of(), runtime, runDate, offset);
        DatasetResult r = engine.execute(datasource, p, 0, exec.getId());
        SqlAuditScene scene = TriggerType.TRIAL.name().equals(exec.getTriggerType())
            ? SqlAuditScene.TRIAL : SqlAuditScene.EXEC;
        audit.record(scene, ds.datasourceId(), ds.sql(), p.auditParams(),
            r.totalRows(), null, "exec:" + exec.getId(), exec.getId(), null);
        results.put(ds.key(), r);
      }
      long queryMs = (System.nanoTime() - q0) / 1_000_000;
      sc = new StageCosts(sc.queueMs(), queryMs, 0, 0);

      if (Thread.interrupted()) { Thread.currentThread().interrupt(); return; }

      // 阶段2：渲染
      long r0 = System.nanoTime();
      Map<String, RenderedArtifact> rendered = new LinkedHashMap<>();
      for (var a : eff.config().artifacts()) {
        if (Thread.interrupted()) { Thread.currentThread().interrupt(); return; }
        long a0 = System.nanoTime();
        try {
          RenderedArtifact ra = renderer.render(a, results, runtime, renderTimeoutSec);
          // enforceBytes：maxBytes 默认 4096（企微 markdown 限制），用处理后的 content 贯穿 artifact 与推送
          int maxBytes = a.maxBytes() != null ? a.maxBytes() : 4096;
          String enforcedContent = renderer.enforceBytes(ra.content(), maxBytes, a.overflowStrategy());
          int enforcedBytes = enforcedContent.getBytes(StandardCharsets.UTF_8).length;
          RenderedArtifact finalRa = new RenderedArtifact(ra.key(), ra.type(), enforcedContent, enforcedBytes);
          rendered.put(a.key(), finalRa);
          long costMs = (System.nanoTime() - a0) / 1_000_000;
          artifactRepo.upsertInline(exec.getId(), a.key(), a.type(),
              enforcedContent, rowsOf(results), enforcedBytes, costMs);
        } catch (Exception e) {
          log.warn("artifact render failed exec={} key={}", exec.getId(), a.key(), e);
          artifactRepo.upsertError(exec.getId(), a.key(), a.type(),
              e instanceof BizException be ? be.getErrorCode().getCode() : "SYS-003",
              e.getMessage());
        }
      }
      long renderMs = (System.nanoTime() - r0) / 1_000_000;
      sc = new StageCosts(sc.queueMs(), sc.queryMs(), renderMs, 0);

      if (Thread.interrupted()) { Thread.currentThread().interrupt(); return; }

      // 阶段3：推送（TRIAL 跳过）
      if (!TriggerType.TRIAL.name().equals(exec.getTriggerType())) {
        long p0 = System.nanoTime();
        boolean anySuccess = false, anyFail = false;
        boolean anyRetryable = false;
        String firstErr = null, firstCode = null;
        boolean isTest = TriggerType.TEST.name().equals(exec.getTriggerType());
        Long testChannelId = runtime.containsKey("__testChannelId")
            ? Long.valueOf(runtime.get("__testChannelId")) : null;

        for (var b : eff.config().channelBindings()) {
          if (Thread.interrupted()) { Thread.currentThread().interrupt(); return; }
          // TEST 触发：仅推送 __testChannelId 指定的绑定
          if (isTest && testChannelId != null && !b.channelId().equals(testChannelId)) continue;

          for (String ak : b.artifactKeys()) {
            if (Thread.interrupted()) { Thread.currentThread().interrupt(); return; }
            if (pushRepo.existsSuccess(exec.getId(), b.channelId(), ak, b.msgType())) {
              anySuccess = true;
              continue;
            }
            RenderedArtifact ra = rendered.get(ak);
            if (ra == null) {
              anyFail = true;
              if (firstCode == null) { firstCode = "SYS-003"; firstErr = "产物 " + ak + " 渲染失败，跳过"; }
              continue;
            }
            String content = isTest ? "[测试] " + ra.content() : ra.content();
            try {
              DecryptedChannel ch = channels.getDecrypted(b.channelId());
              PushResult pr = registry.get(ch.type()).send(
                  ch.webhook(),
                  new PushMessage(b.msgType(), content, List.of()),
                  ch.rateLimit(),
                  ch.waitTimeout());
              if (pr.success()) {
                pushRepo.markSuccess(exec.getId(), b.channelId(), ak, b.msgType());
                anySuccess = true;
              } else {
                pushRepo.markFailed(exec.getId(), b.channelId(), ak, b.msgType(),
                    pr.errorCode(), pr.errorMsg(), 1);
                anyFail = true;
                anyRetryable |= pr.retryable();
                if (firstCode == null) { firstCode = pr.errorCode(); firstErr = pr.errorMsg(); }
              }
            } catch (Exception e) {
              log.warn("push failed exec={} channel={} artifact={}", exec.getId(), b.channelId(), ak, e);
              String code = e instanceof BizException be ? be.getErrorCode().getCode() : "SYS-003";
              pushRepo.markFailed(exec.getId(), b.channelId(), ak, b.msgType(),
                  code, e.getMessage(), 1);
              anyFail = true;
              anyRetryable |= ErrorClassifier.isRetryable(code);
              if (firstCode == null) { firstCode = code; firstErr = e.getMessage(); }
            }
          }
        }
        long pushMs = (System.nanoTime() - p0) / 1_000_000;
        sc = new StageCosts(sc.queueMs(), sc.queryMs(), sc.renderMs(), pushMs);

        if (anyFail && anySuccess) {
          finishOrRetry(exec, ExecStatus.PARTIAL_SUCCESS, sc, results, firstCode, firstErr, anyRetryable);
          return;
        }
        if (anyFail) {
          finishOrRetry(exec, ExecStatus.FAILED, sc, results, firstCode, firstErr, anyRetryable);
          return;
        }
      }

      finishOrRetry(exec, ExecStatus.SUCCESS, sc, results, null, null, false);
    } catch (BizException e) {
      finishOrRetry(exec, ExecStatus.FAILED, sc, Map.of(),
          e.getErrorCode().getCode(), e.getDetail(),
          ErrorClassifier.isRetryable(e.getErrorCode().getCode()));
    } catch (Exception e) {
      log.error("exec {} unexpected", exec.getId(), e);
      finishOrRetry(exec, ExecStatus.FAILED, sc, Map.of(),
          "SYS-003", e.getMessage(), false);
    }
  }

  private void finishOrRetry(TaskExec exec, ExecStatus st, StageCosts sc,
                             Map<String, DatasetResult> results,
                             String code, String msg, boolean retryable) {
    int rows = results.values().stream().mapToInt(DatasetResult::totalRows).sum();
    int maxRetry = exec.getMaxRetry() != null ? exec.getMaxRetry() : 3;
    int retryCount = exec.getRetryCount() != null ? exec.getRetryCount() : 0;

    if (st != ExecStatus.SUCCESS && retryable && retryCount < maxRetry) {
      int backoff = switch (retryCount) { case 0 -> 30; case 1 -> 120; default -> 480; };
      boolean ok = queue.retryWait(exec.getId(), code, msg, backoff);
      if (!ok) {
        log.warn("exec {} retryWait failed (concurrent state transition)", exec.getId());
      }
      return;
    }
    boolean ok = queue.finish(exec.getId(), st, sc.toJson(), rows, code, msg);
    if (!ok) {
      log.warn("exec {} finish failed (concurrent state transition)", exec.getId());
    }
  }

  private Map<String, String> readParams(String paramsJson) {
    if (paramsJson == null || paramsJson.isBlank()) return new LinkedHashMap<>();
    try {
      return om.readValue(paramsJson, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception e) {
      log.warn("exec params_json parse failed: {}", paramsJson, e);
      return new LinkedHashMap<>();
    }
  }

  private int rowsOf(Map<String, DatasetResult> results) {
    return results.values().stream().mapToInt(DatasetResult::totalRows).sum();
  }
}
