package com.hermes.push.channel;

import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.CurrentUserHolder;
import com.hermes.push.datasource.TestResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {
  private final ChannelService service;

  @PostMapping
  public ApiResponse<Long> save(@RequestBody Map<String, Object> req) {
    String name = (String) req.get("name");
    String type = (String) req.get("type");
    String configJson = (String) req.get("configJson");
    int rateLimitPerMin = req.get("rateLimitPerMin") != null
        ? ((Number) req.get("rateLimitPerMin")).intValue() : 20;
    int waitTimeoutSec = req.get("waitTimeoutSec") != null
        ? ((Number) req.get("waitTimeoutSec")).intValue() : 300;
    boolean testFlag = req.get("testFlag") != null && (Boolean) req.get("testFlag");
    return ApiResponse.ok(service.save(name, type, configJson, rateLimitPerMin, waitTimeoutSec, testFlag, CurrentUserHolder.get()));
  }

  @PutMapping("/{id}")
  public ApiResponse<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> req) {
    String name = (String) req.get("name");
    String type = (String) req.get("type");
    String configJson = (String) req.get("configJson");
    int rateLimitPerMin = req.get("rateLimitPerMin") != null
        ? ((Number) req.get("rateLimitPerMin")).intValue() : 20;
    int waitTimeoutSec = req.get("waitTimeoutSec") != null
        ? ((Number) req.get("waitTimeoutSec")).intValue() : 300;
    boolean testFlag = req.get("testFlag") != null && (Boolean) req.get("testFlag");
    service.update(id, name, type, configJson, rateLimitPerMin, waitTimeoutSec, testFlag, CurrentUserHolder.get());
    return ApiResponse.ok(null);
  }

  @GetMapping
  public ApiResponse<List<ChannelVO>> list() {
    return ApiResponse.ok(service.list());
  }

  @GetMapping("/{id}")
  public ApiResponse<ChannelVO> get(@PathVariable Long id) {
    return ApiResponse.ok(service.get(id));
  }

  @PostMapping("/{id}/status")
  public ApiResponse<Void> setStatus(@PathVariable Long id, @RequestParam boolean enable) {
    service.setStatus(id, enable);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/health-check")
  public ApiResponse<TestResultVO> healthCheck(@PathVariable Long id) {
    return ApiResponse.ok(service.healthCheck(id));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.ok(null);
  }
}
