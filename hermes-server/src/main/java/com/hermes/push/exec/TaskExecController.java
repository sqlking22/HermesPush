package com.hermes.push.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.channel.ChannelService;
import com.hermes.push.channel.ChannelVO;
import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.BizException;
import com.hermes.push.common.CurrentUserHolder;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.task.Task;
import com.hermes.push.task.TaskMapper;
import com.hermes.push.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务执行 REST 端点：试运行 / 手动触发 / 测试发送。
 * <p>均异步入队，立即返回 execId，前端轮询执行详情。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks/{id}")
@RequiredArgsConstructor
public class TaskExecController {

  private final TaskService tasks;
  private final TaskMapper taskMapper;
  private final ExecQueueRepository queue;
  private final ChannelService channels;
  private final ObjectMapper om;

  /**
   * 试运行：完整查询+渲染+写 artifact，跳过推送，priority=70。
   */
  @PostMapping("/trial")
  public ApiResponse<Map<String, Object>> trial(@PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Task t = requireActiveTask(id);
    TaskService.EffectiveConfig eff = tasks.loadEffectiveConfig(id);
    Map<String, String> params = extractParams(body);

    LocalDate runDate = LocalDate.now();
    int offset = eff.config().schedule().bizOffsetDays() != null
        ? eff.config().schedule().bizOffsetDays() : 0;
    LocalDate bizDate = runDate.plusDays(offset);

    String paramsJson = toJson(params);
    String idemKey = "trial-" + UUID.randomUUID();

    Long execId = queue.insertPending(
        id, eff.taskVersionId(), TriggerType.TRIAL, ExecPriority.TRIAL_TEST,
        LocalDateTime.now(), bizDate, paramsJson, idemKey);
    if (execId == null) {
      throw new BizException(ErrorCode.SYS_002, "重复提交");
    }
    return ApiResponse.ok(Map.of("execId", execId));
  }

  /**
   * 手动触发：priority=60，支持 bizDate 覆盖和 idempotencyKey。
   */
  @PostMapping("/trigger")
  public ApiResponse<Map<String, Object>> trigger(@PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Task t = requireActiveTask(id);
    TaskService.EffectiveConfig eff = tasks.loadEffectiveConfig(id);
    Map<String, String> params = extractParams(body);

    // bizDate 校验
    LocalDate bizDate = null;
    if (body != null && body.containsKey("bizDate") && body.get("bizDate") != null) {
      String bizDateStr = body.get("bizDate").toString();
      try {
        bizDate = LocalDate.parse(bizDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
      } catch (DateTimeParseException e) {
        throw new BizException(ErrorCode.SQL_004,
            "bizDate 格式必须为 yyyy-MM-dd，实际: " + bizDateStr);
      }
    }

    String idemKey = body != null && body.containsKey("idempotencyKey") && body.get("idempotencyKey") != null
        ? body.get("idempotencyKey").toString()
        : "manual-" + UUID.randomUUID();

    String paramsJson = toJson(params);
    Long execId = queue.insertPending(
        id, eff.taskVersionId(), TriggerType.MANUAL, ExecPriority.MANUAL_API,
        LocalDateTime.now(), bizDate, paramsJson, idemKey);
    if (execId == null) {
      throw new BizException(ErrorCode.SYS_002, "重复提交");
    }
    return ApiResponse.ok(Map.of("execId", execId));
  }

  /**
   * 测试发送：仅推送指定 test_flag=1 的渠道，priority=70，content 加 [测试] 前缀。
   */
  @PostMapping("/test-send")
  public ApiResponse<Map<String, Object>> testSend(@PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Task t = requireActiveTask(id);
    TaskService.EffectiveConfig eff = tasks.loadEffectiveConfig(id);

    Long channelId = null;
    if (body != null && body.containsKey("channelId") && body.get("channelId") != null) {
      Object chObj = body.get("channelId");
      if (chObj instanceof Number n) {
        channelId = n.longValue();
      } else {
        try {
          channelId = Long.parseLong(chObj.toString());
        } catch (NumberFormatException e) {
          throw new BizException(ErrorCode.SYS_006, "channelId 格式错误");
        }
      }
    }
    if (channelId == null) {
      throw new BizException(ErrorCode.SYS_006, "channelId 不能为空");
    }

    // 校验渠道 test_flag=1
    ChannelVO ch = channels.get(channelId);
    if (!ch.testFlag()) {
      throw new BizException(ErrorCode.SYS_002, "测试渠道 test_flag 必须为 1");
    }

    Map<String, String> params = extractParams(body);
    params.put("__testChannelId", channelId.toString());

    LocalDate runDate = LocalDate.now();
    int offset = eff.config().schedule().bizOffsetDays() != null
        ? eff.config().schedule().bizOffsetDays() : 0;
    LocalDate bizDate = runDate.plusDays(offset);

    String paramsJson = toJson(params);
    String idemKey = "test-" + UUID.randomUUID();

    Long execId = queue.insertPending(
        id, eff.taskVersionId(), TriggerType.TEST, ExecPriority.TRIAL_TEST,
        LocalDateTime.now(), bizDate, paramsJson, idemKey);
    if (execId == null) {
      throw new BizException(ErrorCode.SYS_002, "重复提交");
    }
    return ApiResponse.ok(Map.of("execId", execId));
  }

  // ---------- helpers ----------

  private Task requireActiveTask(Long id) {
    Task t = taskMapper.selectById(id);
    if (t == null) {
      throw new BizException(ErrorCode.SYS_003, "任务不存在: " + id);
    }
    if ("DELETED".equals(t.getStatus())) {
      throw new BizException(ErrorCode.SYS_003, "任务已删除: " + id);
    }
    return t;
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> extractParams(Map<String, Object> body) {
    Map<String, String> params = new LinkedHashMap<>();
    if (body == null) return params;
    Object paramsObj = body.get("params");
    if (paramsObj instanceof Map<?, ?> m) {
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (e.getValue() != null) {
          params.put(e.getKey().toString(), e.getValue().toString());
        }
      }
    }
    return params;
  }

  private String toJson(Map<String, String> params) {
    try {
      return om.writeValueAsString(params);
    } catch (Exception e) {
      return "{}";
    }
  }
}
