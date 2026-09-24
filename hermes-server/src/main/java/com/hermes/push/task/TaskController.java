package com.hermes.push.task;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.BizException;
import com.hermes.push.common.CurrentUserHolder;
import com.hermes.push.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

  private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");
  private static final ZoneId SH_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
  private static final Set<String> DELETABLE_STATUS = Set.of("DRAFT", "OFFLINE");

  private final TaskService taskService;
  private final TaskMapper taskMapper;
  private final TaskVersionMapper versionMapper;
  private final JdbcTemplate jdbc;

  public record TaskListVO(
      Long id, String name, String taskKey, String taskType, String status,
      String cronExpr, String nextFire, String owner,
      Integer currentVersionNo, Boolean pinned) {}

  public record TaskDetailVO(
      Long id, String name, String taskKey, String taskType, String status,
      String cronExpr, Integer jitterEnabled, Long currentVersionId, Long pinnedVersionId,
      String owner, Integer lockVersion, LocalDateTime createdAt, LocalDateTime updatedAt,
      Integer currentVersionNo, TaskConfig config) {}

  public record TaskCreateRequest(
      @NotBlank String name,
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]{1,63}$", message = "taskKey 必须以小写字母开头，可含数字/下划线/中划线，长度 2-64 位")
      String taskKey,
      @NotNull TaskConfig config,
      String remark) {}

  public record TaskUpdateRequest(
      @NotNull TaskConfig config,
      String remark,
      @NotNull Integer lockVersion) {}

  @PostMapping
  public ApiResponse<Map<String, Long>> create(@Valid @RequestBody TaskCreateRequest req) {
    Long id = taskService.create(req.name(), req.taskKey(), req.config(), req.remark(), CurrentUserHolder.get());
    return ApiResponse.ok(Map.of("taskId", id));
  }

  @PutMapping("/{id}")
  public ApiResponse<Long> update(@PathVariable Long id, @Valid @RequestBody TaskUpdateRequest req) {
    Long versionId = taskService.saveVersion(id, req.config(), req.remark(), req.lockVersion(), CurrentUserHolder.get());
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
    List<Task> records = pageResult.getRecords();

    // 批量查版本号（N+1 → 1）
    List<Long> versionIds = records.stream()
        .map(Task::getCurrentVersionId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toList());
    Map<Long, Integer> versionNoMap = new HashMap<>();
    if (!versionIds.isEmpty()) {
      List<TaskVersion> versions = versionMapper.selectBatchIds(versionIds);
      for (TaskVersion v : versions) {
        versionNoMap.put(v.getId(), v.getVersionNo());
      }
    }

    // 数据库 NOW() 只取一次，所有行复用
    LocalDateTime now = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);

    IPage<TaskListVO> voPage = pageResult.convert(t -> {
      String nextFire = computeNextFire(t.getStatus(), t.getCronExpr(), now);
      Integer verNo = versionNoMap.get(t.getCurrentVersionId());
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
    TaskVersion current = t.getCurrentVersionId() != null
        ? versionMapper.selectById(t.getCurrentVersionId()) : null;
    Integer verNo = current != null ? current.getVersionNo() : null;
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
    if (!DELETABLE_STATUS.contains(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "请先下线任务");
    }
    t.setStatus("DELETED");
    taskMapper.updateById(t);
    return ApiResponse.ok(null);
  }

  // ---------- helpers ----------

  private String computeNextFire(String status, String cronExpr, LocalDateTime now) {
    if (cronExpr == null || cronExpr.isBlank()) return null;
    if (!List.of("ONLINE", "PAUSED").contains(status)) return null;
    try {
      CronExpression ce = new CronExpression(cronExpr);
      ce.setTimeZone(SH);
      Date from = Date.from(now.atZone(SH_ZONE).toInstant());
      Date next = ce.getNextValidTimeAfter(from);
      if (next == null) return null;
      return next.toInstant().atZone(SH_ZONE).toLocalDateTime().format(ISO_DT);
    } catch (Exception e) {
      return null;
    }
  }
}
