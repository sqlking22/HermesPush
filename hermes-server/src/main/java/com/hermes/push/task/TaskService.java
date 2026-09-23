package com.hermes.push.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {
  private final TaskMapper taskMapper;
  private final TaskVersionMapper versionMapper;
  private final JdbcTemplate jdbc;
  private final ObjectMapper om;
  private final ObjectProvider<ScheduleSyncPort> syncPort;
  private final ObjectProvider<TemplateScanPort> scanPort;

  public record EffectiveConfig(
      Long taskId,
      Long taskVersionId,
      TaskConfig config,
      String cron,
      String owner) {}

  @Transactional
  public Long create(String name, String taskKey, TaskConfig config, String remark, String operator) {
    TaskConfigValidator.validate(config, scanPort);
    Task t = new Task();
    t.setName(name);
    t.setTaskKey(taskKey);
    t.setTaskType("REPORT");
    t.setStatus("DRAFT");
    t.setOwner(operator);
    t.setCronExpr(config.schedule().cron());
    t.setJitterEnabled(config.schedule().jitterEnabled() ? 1 : 0);
    t.setLockVersion(0);
    try {
      taskMapper.insert(t);
    } catch (DuplicateKeyException e) {
      throw new BizException(ErrorCode.SYS_002, "任务Key已存在: " + taskKey);
    }
    Long vid = insertVersion(t.getId(), 1, config, remark, operator);
    t.setCurrentVersionId(vid);
    taskMapper.updateById(t);
    return t.getId();
  }

  @Transactional
  public Long saveVersion(Long taskId, TaskConfig config, String remark, int expectedLock, String operator) {
    TaskConfigValidator.validate(config, scanPort);
    Task t = require(taskId);
    if (t.getLockVersion() != expectedLock) {
      throw new BizException(ErrorCode.SYS_004,
          "lockVersion " + expectedLock + " != " + t.getLockVersion());
    }
    int no = nextVersionNo(taskId);
    Long vid = insertVersion(taskId, no, config, remark, operator);
    int updated = jdbc.update(
        "UPDATE hp_task SET current_version_id=?, cron_expr=?, lock_version=lock_version+1 WHERE id=? AND lock_version=?",
        vid, config.schedule().cron(), taskId, expectedLock);
    if (updated == 0) {
      throw new BizException(ErrorCode.SYS_004, "并发保存冲突");
    }
    // 乐观锁更新成功后重新读取 status，避免使用旧快照判断 onCronChange
    String freshStatus = jdbc.queryForObject(
        "SELECT status FROM hp_task WHERE id=?", String.class, taskId);
    if ("ONLINE".equals(freshStatus)) {
      syncPort.ifAvailable(p -> p.onCronChange(taskId, config.schedule().cron()));
    }
    return vid;
  }

  @Transactional
  public void publish(Long taskId) {
    Task t = require(taskId);
    if (!Set.of("DRAFT", "OFFLINE", "PAUSED").contains(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "当前状态 " + t.getStatus());
    }
    Long vid = t.getPinnedVersionId() != null ? t.getPinnedVersionId() : t.getCurrentVersionId();
    if (!hasSuccessTrial(taskId, vid)) {
      throw new BizException(ErrorCode.SYS_002,
          "当前版本未试运行通过，不能上线（FR-TSK-02）");
    }
    t.setStatus("ONLINE");
    taskMapper.updateById(t);
    syncPort.ifAvailable(p -> p.onPublish(taskId, t.getCronExpr()));
  }

  @Transactional
  public void offline(Long taskId) {
    Task t = require(taskId);
    if (!"ONLINE".equals(t.getStatus()) && !"PAUSED".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "当前状态 " + t.getStatus());
    }
    t.setStatus("OFFLINE");
    taskMapper.updateById(t);
    syncPort.ifAvailable(p -> p.onOffline(taskId));
  }

  @Transactional
  public void pause(Long taskId) {
    Task t = require(taskId);
    if (!"ONLINE".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "当前状态 " + t.getStatus());
    }
    t.setStatus("PAUSED");
    taskMapper.updateById(t);
    syncPort.ifAvailable(p -> p.onOffline(taskId));
  }

  @Transactional
  public void resume(Long taskId) {
    Task t = require(taskId);
    if (!"PAUSED".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "当前状态 " + t.getStatus());
    }
    Long vid = t.getPinnedVersionId() != null ? t.getPinnedVersionId() : t.getCurrentVersionId();
    if (!hasSuccessTrial(taskId, vid)) {
      throw new BizException(ErrorCode.SYS_002,
          "当前版本未试运行通过，不能恢复（FR-TSK-02）");
    }
    t.setStatus("ONLINE");
    taskMapper.updateById(t);
    syncPort.ifAvailable(p -> p.onPublish(taskId, t.getCronExpr()));
  }

  public boolean hasSuccessTrial(Long taskId, Long versionId) {
    Integer n = jdbc.queryForObject(
        "SELECT COUNT(*) FROM hp_task_exec WHERE task_id=? AND task_version_id=? AND trigger_type='TRIAL' AND status='SUCCESS'",
        Integer.class, taskId, versionId);
    return n != null && n > 0;
  }

  public EffectiveConfig loadEffectiveConfig(Long taskId) {
    Task t = require(taskId);
    Long vid = t.getPinnedVersionId() != null ? t.getPinnedVersionId() : t.getCurrentVersionId();
    if (vid == null) {
      throw new BizException(ErrorCode.SYS_003, "任务无可用版本");
    }
    TaskVersion v = versionMapper.selectById(vid);
    if (v == null) {
      throw new BizException(ErrorCode.SYS_003, "版本不存在: " + vid);
    }
    try {
      TaskConfig cfg = om.readValue(v.getConfigJson(), TaskConfig.class);
      return new EffectiveConfig(taskId, vid, cfg, t.getCronExpr(), t.getOwner());
    } catch (Exception e) {
      log.error("配置反序列化失败 taskId={} versionId={} versionNo={}", taskId, vid, v.getVersionNo(), e);
      throw new BizException(ErrorCode.SYS_003, "配置版本 v" + v.getVersionNo() + " 反序列化失败");
    }
  }

  // ---------- internal ----------

  private Task require(Long taskId) {
    Task t = taskMapper.selectById(taskId);
    if (t == null) {
      throw new BizException(ErrorCode.SYS_003, "任务不存在: " + taskId);
    }
    return t;
  }

  private Long insertVersion(Long taskId, int versionNo, TaskConfig config, String remark, String operator) {
    TaskVersion v = new TaskVersion();
    v.setTaskId(taskId);
    v.setVersionNo(versionNo);
    try {
      v.setConfigJson(om.writeValueAsString(config));
    } catch (Exception e) {
      log.error("任务配置序列化失败 taskId={} versionNo={}", taskId, versionNo, e);
      throw new BizException(ErrorCode.SYS_003, "任务配置序列化失败，请联系管理员");
    }
    v.setRemark(remark);
    v.setCreatedBy(operator);
    versionMapper.insert(v);
    return v.getId();
  }

  private int nextVersionNo(Long taskId) {
    Integer max = jdbc.queryForObject(
        "SELECT COALESCE(MAX(version_no), 0) FROM hp_task_version WHERE task_id=?",
        Integer.class, taskId);
    return (max == null ? 0 : max) + 1;
  }
}
