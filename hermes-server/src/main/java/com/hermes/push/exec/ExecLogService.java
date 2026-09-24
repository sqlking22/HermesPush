package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.channel.Channel;
import com.hermes.push.channel.ChannelMapper;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.exec.vo.ArtifactVO;
import com.hermes.push.exec.vo.ExecDetailVO;
import com.hermes.push.exec.vo.ExecListVO;
import com.hermes.push.exec.vo.NextTriggerVO;
import com.hermes.push.exec.vo.PushVO;
import com.hermes.push.exec.vo.TodaySummaryVO;
import com.hermes.push.task.Task;
import com.hermes.push.task.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecLogService {

  private static final String GROUP = "hermes";
  private static final DateTimeFormatter TRIGGER_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

  private final ExecLogMapper execLogMapper;
  private final ExecQueueRepository execQueue;
  private final ExecArtifactMapper artifactMapper;
  private final ExecPushMapper pushMapper;
  private final ChannelMapper channelMapper;
  private final TaskMapper taskMapper;
  private final ObjectMapper om;
  private final Scheduler scheduler;

  public IPage<ExecListVO> list(Long taskId, String status, String triggerType,
      LocalDateTime from, LocalDateTime to, int page, int size) {
    Page<ExecListVO> p = new Page<>(page, size);
    return execLogMapper.selectExecPage(p, taskId, status, triggerType, from, to);
  }

  public ExecDetailVO detail(Long id) {
    TaskExec exec = execQueue.getById(id);
    if (exec == null) {
      throw new BizException(ErrorCode.SYS_003, "执行记录不存在: " + id);
    }

    List<TaskExecArtifact> artifactList = artifactMapper.selectList(
        new QueryWrapper<TaskExecArtifact>().eq("exec_id", id).orderByAsc("id"));
    List<ArtifactVO> artifacts = artifactList.stream()
        .map(a -> new ArtifactVO(
            a.getArtifactKey(),
            a.getType(),
            a.getRenderProvider(),
            a.getRowsCount(),
            a.getBytes(),
            a.getContent(),
            a.getStorageUri(),
            a.getCostMs(),
            a.getErrorCode(),
            a.getErrorMsg()))
        .toList();

    List<TaskExecPush> pushList = pushMapper.selectList(
        new QueryWrapper<TaskExecPush>().eq("exec_id", id).orderByAsc("id"));

    // 批量查 channel name
    List<Long> channelIds = pushList.stream()
        .map(TaskExecPush::getChannelId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    Map<Long, String> channelNameMap = new HashMap<>();
    if (!channelIds.isEmpty()) {
      List<Channel> channels = channelMapper.selectBatchIds(channelIds);
      for (Channel c : channels) {
        channelNameMap.put(c.getId(), c.getName());
      }
    }

    List<PushVO> pushes = pushList.stream()
        .map(p -> new PushVO(
            p.getChannelId(),
            channelNameMap.get(p.getChannelId()),
            p.getArtifactKey(),
            p.getMsgType(),
            p.getStatus(),
            p.getRetryCount(),
            p.getErrorCode(),
            p.getErrorMsg(),
            p.getSentAt()))
        .toList();

    StageCosts stageCosts = parseStageCosts(exec.getStageCostsJson());

    return new ExecDetailVO(exec, artifacts, pushes, stageCosts, true);
  }

  private StageCosts parseStageCosts(String json) {
    if (json == null || json.isEmpty()) {
      return null;
    }
    try {
      return om.readValue(json, StageCosts.class);
    } catch (JsonProcessingException e) {
      log.warn("parse stage_costs_json failed: {}", json, e);
      return null;
    }
  }

  public TodaySummaryVO todaySummary() {
    List<Map<String, Object>> rows = execLogMapper.selectTodayCounts();

    long total = 0, success = 0, failed = 0, running = 0;
    Set<String> successSet = Set.of("SUCCESS", "PARTIAL_SUCCESS");
    Set<String> failedSet = Set.of("FAILED", "TIMEOUT");
    Set<String> runningSet = Set.of("PENDING", "RUNNING", "RETRY_WAIT");

    for (Map<String, Object> row : rows) {
      String status = (String) row.get("status");
      long cnt = ((Number) row.get("cnt")).longValue();
      total += cnt;
      if (successSet.contains(status)) success += cnt;
      else if (failedSet.contains(status)) failed += cnt;
      else if (runningSet.contains(status)) running += cnt;
    }

    List<NextTriggerVO> nextTriggers = fetchNextTriggers();

    return new TodaySummaryVO(total, success, failed, running, nextTriggers);
  }

  private List<NextTriggerVO> fetchNextTriggers() {
    try {
      if (!scheduler.isStarted()) {
        return List.of();
      }
      List<Trigger> triggers = new ArrayList<>();
      for (var tk : scheduler.getTriggerKeys(GroupMatcher.anyGroup())) {
        Trigger t = scheduler.getTrigger(tk);
        if (t != null) triggers.add(t);
      }

      LocalDateTime now = LocalDateTime.now();
      LocalDateTime oneHourLater = now.plusHours(1);

      List<NextTriggerVO> result = new ArrayList<>();
      for (Trigger t : triggers) {
        Date nextFire = t.getNextFireTime();
        if (nextFire == null) continue;
        LocalDateTime next = nextFire.toInstant().atZone(SH).toLocalDateTime();
        if (next.isAfter(now) && !next.isAfter(oneHourLater)) {
          JobKey jk = t.getJobKey();
          String taskIdStr = extractTaskId(jk.getName());
          String taskName = null;
          if (taskIdStr != null) {
            try {
              Long taskId = Long.parseLong(taskIdStr);
              Task task = taskMapper.selectById(taskId);
              if (task != null) taskName = task.getName();
            } catch (NumberFormatException ignored) {}
          }
          result.add(new NextTriggerVO(next.format(TRIGGER_FMT), taskName));
        }
      }
      return result.stream()
          .sorted(Comparator.comparing(NextTriggerVO::fireTime))
          .limit(20)
          .toList();
    } catch (SchedulerException e) {
      log.warn("fetch next triggers failed", e);
      return List.of();
    }
  }

  private String extractTaskId(String jobName) {
    String prefix = "hermes-task-";
    if (jobName != null && jobName.startsWith(prefix)) {
      return jobName.substring(prefix.length());
    }
    return null;
  }
}
