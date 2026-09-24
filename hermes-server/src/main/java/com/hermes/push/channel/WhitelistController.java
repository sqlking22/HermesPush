package com.hermes.push.channel;

import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/whitelist")
@RequiredArgsConstructor
public class WhitelistController {
  private final ChannelService service;

  @GetMapping
  public ApiResponse<List<Whitelist>> list(@RequestParam(required = false) String type) {
    return ApiResponse.ok(service.listWhitelist(type));
  }

  @PostMapping
  public ApiResponse<Long> save(@RequestBody Map<String, String> req) {
    String type = req.get("type");
    String value = req.get("value");
    return ApiResponse.ok(service.saveWhitelist(type, value, CurrentUserHolder.get()));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    service.deleteWhitelist(id);
    return ApiResponse.ok(null);
  }
}
