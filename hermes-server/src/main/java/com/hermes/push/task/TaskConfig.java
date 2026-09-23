package com.hermes.push.task;

import com.hermes.push.dataset.ParameterDefinition;
import java.util.List;

public record TaskConfig(
    List<DatasetDef> datasets,
    List<ArtifactDef> artifacts,
    List<ChannelBinding> channelBindings,
    ScheduleDef schedule) {

  public record DatasetDef(
      String key,
      Long datasourceId,
      String sql,
      List<ParameterDefinition> params) {}

  public record ArtifactDef(
      String key,
      String type,
      String inlineTemplate,
      String overflowStrategy,
      Integer maxBytes) {}

  public record ChannelBinding(
      Long channelId,
      List<String> artifactKeys,
      String msgType) {}

  public record ScheduleDef(
      String cron,
      Integer bizOffsetDays,
      Integer timeoutMinutes,
      Integer maxRetry,
      boolean jitterEnabled) {}
}
