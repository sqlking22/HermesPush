package com.hermes.push.task;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.quartz.CronExpression;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class TaskConfigValidator {
  private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z]\\w{0,31}$");
  private static final Set<String> BUILTIN_TIME_VARS = Set.of("runDate", "bizDate");
  private static final Set<String> ALLOWED_ARTIFACT_TYPES = Set.of("MARKDOWN");
  private static final Set<String> ALLOWED_OVERFLOW = Set.of("TRUNCATE", "FAIL");
  private static final Set<String> ALLOWED_MSG_TYPES = Set.of("text", "markdown");

  private TaskConfigValidator() {}

  static void validate(TaskConfig config, ObjectProvider<TemplateScanPort> scanPort) {
    // datasets / artifacts / channelBindings 各 ≥1
    List<TaskConfig.DatasetDef> datasets = config.datasets();
    List<TaskConfig.ArtifactDef> artifacts = config.artifacts();
    List<TaskConfig.ChannelBinding> bindings = config.channelBindings();
    if (datasets == null || datasets.isEmpty()) {
      throw new BizException(ErrorCode.SYS_006, "datasets 不能为空");
    }
    if (artifacts == null || artifacts.isEmpty()) {
      throw new BizException(ErrorCode.SYS_006, "artifacts 不能为空");
    }
    if (bindings == null || bindings.isEmpty()) {
      throw new BizException(ErrorCode.SYS_006, "channelBindings 不能为空");
    }

    // dataset.key 唯一且匹配正则
    Set<String> dsKeys = new HashSet<>();
    for (var ds : datasets) {
      if (ds.key() == null || !KEY_PATTERN.matcher(ds.key()).matches()) {
        throw new BizException(ErrorCode.SYS_006, "dataset.key 不合法: " + ds.key());
      }
      if (!dsKeys.add(ds.key())) {
        throw new BizException(ErrorCode.SYS_002, "dataset.key 重复: " + ds.key());
      }
      // params 名不得为 runDate/bizDate
      if (ds.params() != null) {
        for (var p : ds.params()) {
          if (BUILTIN_TIME_VARS.contains(p.name())) {
            throw new BizException(ErrorCode.SYS_002,
                "参数名 " + p.name() + " 与内置时间变量冲突");
          }
        }
      }
    }

    // artifact.key 唯一且匹配正则；type 仅 MARKDOWN；inlineTemplate 非空
    Set<String> artKeys = new HashSet<>();
    for (var a : artifacts) {
      if (a.key() == null || !KEY_PATTERN.matcher(a.key()).matches()) {
        throw new BizException(ErrorCode.SYS_006, "artifact.key 不合法: " + a.key());
      }
      if (!artKeys.add(a.key())) {
        throw new BizException(ErrorCode.SYS_002, "artifact.key 重复: " + a.key());
      }
      if (!ALLOWED_ARTIFACT_TYPES.contains(a.type())) {
        throw new BizException(ErrorCode.SYS_006, "artifact.type 不支持: " + a.type());
      }
      if (a.inlineTemplate() == null || a.inlineTemplate().isBlank()) {
        throw new BizException(ErrorCode.SYS_006, "artifact.inlineTemplate 不能为空");
      }
      if (a.overflowStrategy() != null && !ALLOWED_OVERFLOW.contains(a.overflowStrategy())) {
        throw new BizException(ErrorCode.SYS_006, "overflowStrategy 不合法: " + a.overflowStrategy());
      }
    }

    // binding 的 artifactKeys 必须存在于 artifacts；msgType 合法
    for (var b : bindings) {
      if (b.artifactKeys() == null || b.artifactKeys().isEmpty()) {
        throw new BizException(ErrorCode.SYS_006, "channelBinding.artifactKeys 不能为空");
      }
      for (var ak : b.artifactKeys()) {
        if (!artKeys.contains(ak)) {
          throw new BizException(ErrorCode.SYS_002,
              "channelBinding 引用了不存在的 artifact: " + ak);
        }
      }
      if (!ALLOWED_MSG_TYPES.contains(b.msgType())) {
        throw new BizException(ErrorCode.SYS_006, "msgType 不支持: " + b.msgType());
      }
    }

    // cron 合法
    TaskConfig.ScheduleDef sched = config.schedule();
    if (sched == null || sched.cron() == null || sched.cron().isBlank()) {
      throw new BizException(ErrorCode.SYS_006, "schedule.cron 不能为空");
    }
    if (!CronExpression.isValidExpression(sched.cron())) {
      throw new BizException(ErrorCode.SYS_006, "cron 表达式不合法: " + sched.cron());
    }

    // 模板扫描（Task 14 提供实现，缺省 no-op）
    scanPort.ifAvailable(p -> {
      for (var a : artifacts) {
        p.scan(a.inlineTemplate());
      }
    });
  }
}
