package com.hermes.push.exec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 阶段耗时记录，Jackson 序列化进 stage_costs_json。
 */
public record StageCosts(
    long queueMs,
    long queryMs,
    long renderMs,
    long pushMs) {

  public StageCosts() {
    this(0, 0, 0, 0);
  }

  public String toJson() {
    try {
      return new ObjectMapper().writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("StageCosts serialize failed", e);
    }
  }
}
