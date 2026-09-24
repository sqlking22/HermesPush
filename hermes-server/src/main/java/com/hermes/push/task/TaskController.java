package com.hermes.push.task;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.BizException;
import com.hermes.push.common.CurrentUserHolder;
import com.hermes.push.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

  private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");
  private static final ZoneId SH_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final TaskService taskService;
  private final TaskMapper taskMapper;
  private final TaskVersionMapper versionMapper;
  private final JdbcTemplate jdbc;
  private final ObjectMapper om;

  public record TaskListVO(
      Long id, String name, String taskKey, String taskType, String status,
      String cronExpr, String nextFire, String owner,
      Integer currentVersionNo, Boolean pinned) {}

  public record TaskDetailVO(
      Long id, String name, String taskKey, String taskType, String status,
      String cronExpr, Integer jitterEnabled, Long currentVersionId, Long pinnedVersionId,
      String owner, Integer lockVersion, LocalDateTime createdAt, LocalDateTime updatedAt,
      Integer currentVersionNo, TaskConfig config) {}

  @PostMapping
  public ApiResponse<Map<String, Long>> create(@RequestBody Map<String, Object> body) {
    String name = (String) body.get("name");
    String taskKey = (String) body.get("taskKey");
    String remark = body.get("remark") != null ? body.get("remark").toString() : null;
    TaskConfig config = om.convertValue(body.get("config"), TaskConfig.class);
    Long id = taskService.create(name, taskKey, config, remark, CurrentUserHolder.get());
    return ApiResponse.ok(Map.of("taskId", id));
  }

  @PutMapping("/{id}")
  public ApiResponse<Long> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
    TaskConfig config = body.get("config") != null
        ? om.convertValue(body.get("config"), TaskConfig.class) : null;
    String remark = body.get("remark") != null ? body.get("remark").toString() : null;
    int lockVersion = body.get("lockVersion") != null
        ? ((Number) body.get("lockVersion")).intValue() : 0;
    Long versionId;
    if (config != null) {
      versionId = taskService.saveVersion(id, config, remark, lockVersion, CurrentUserHolder.get());
    } else {
      // 仅更新 remark 等字段（不走版本号机制）
      Task t = taskMapper.selectById(id);
      if (t == null || "DELETED".equals(t.getStatus())) {
        throw new BizException(ErrorCode.SYS_003, "任务不存在: " + id);
      }
      if (remark != null) {
        // 当前 Task 实体无 remark 字段，这里直接返回当前版本号
      }
      versionId = t.getCurrentVersionId();
    }
    return ApiResponse.ok(versionId);
  }

  @GetMapping
  public ApiResponse<IPage<TaskListVO>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {

    Page<Task> p = new Page<>(page, size);
    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task> w =
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
    w.ne(Task::getStatus, "DELETED");
    if (status != null && !status.isBlank()) {
      w.eq(Task::getStatus, status);
    }
    if (keyword != null && !keyword.isBlank()) {
      w.and(q -> q.like(Task::getName, keyword).or().like(Task::getTaskKey, keyword));
    }
    w.orderByDesc(Task::getId);

    IPage<Task> pageResult = taskMapper.selectPage(p, w);
    IPage<TaskListVO> voPage = pageResult.convert(t -> {
      String nextFire = computeNextFire(t.getStatus(), t.getCronExpr());
      Integer verNo = lookupVersionNo(t.getCurrentVersionId());
      Boolean pinned = t.getPinnedVersionId() != null;
      return new TaskListVO(
          t.getId(), t.getName(), t.getTaskKey(), t.getTaskType(), t.getStatus(),
          t.getCronExpr(), nextFire, t.getOwner(), verNo, pinned);
    });
    return ApiResponse.ok(voPage);
  }

  @GetMapping("/{id}")
  public ApiResponse<TaskDetailVO> detail(@PathVariable Long id) {
    Task t = taskMapper.selectById(id);
    if (t == null || "DELETED".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_003, "任务不存在: " + id);
    }
    TaskService.EffectiveConfig eff = taskService.loadEffectiveConfig(id);
    Integer verNo = lookupVersionNo(t.getCurrentVersionId());
    TaskDetailVO vo = new TaskDetailVO(
        t.getId(), t.getName(), t.getTaskKey(), t.getTaskType(), t.getStatus(),
        t.getCronExpr(), t.getJitterEnabled(), t.getCurrentVersionId(), t.getPinnedVersionId(),
        t.getOwner(), t.getLockVersion(), t.getCreatedAt(), t.getUpdatedAt(),
        verNo, eff.config());
    return ApiResponse.ok(vo);
  }

  @PostMapping("/{id}/publish")
  public ApiResponse<Void> publish(@PathVariable Long id) {
    taskService.publish(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/offline")
  public ApiResponse<Void> offline(@PathVariable Long id) {
    taskService.offline(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/pause")
  public ApiResponse<Void> pause(@PathVariable Long id) {
    taskService.pause(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/resume")
  public ApiResponse<Void> resume(@PathVariable Long id) {
    taskService.resume(id);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    Task t = taskMapper.selectById(id);
    if (t == null || "DELETED".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_003, "任务不存在: " + id);
    }
    if ("ONLINE".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "已上线任务请先下线再删除");
    }
    t.setStatus("DELETED");
    taskMapper.updateById(t);
    return ApiResponse.ok(null);
  }

  // ---------- helpers ----------

  private Integer lookupVersionNo(Long versionId) {
    if (versionId == null) return null;
    TaskVersion v = versionMapper.selectById(versionId);
    return v != null ? v.getVersionNo() : null;
  }

  private String computeNextFire(String status, String cronExpr) {
    if (cronExpr == null || cronExpr.isBlank()) return null;
    if (!List.of("ONLINE", "PAUSED").contains(status)) return null;
    try {
      CronExpression ce = new CronExpression(cronExpr);
      ce.setTimeZone(SH);
      LocalDateTime now = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
      Date from = Date.from(now.atZone(SH_ZONE).toInstant());
      Date next = ce.getNextValidTimeAfter(from);
      if (next == null) return null;
      return next.toInstant().atZone(SH_ZONE).toLocalDateTime().format(ISO_DT);
    } catch (Exception e) {
      return null;
    }
  }
}
