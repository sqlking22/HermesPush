package com.hermes.push.datasource;

import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DatasourceController {
  private final DatasourceService service;

  @PostMapping
  public ApiResponse<Long> save(@Valid @RequestBody DatasourceSaveRequest req) {
    return ApiResponse.ok(service.save(req, CurrentUserHolder.get()));
  }

  @PutMapping("/{id}")
  public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody DatasourceSaveRequest req) {
    service.update(id, req, CurrentUserHolder.get());
    return ApiResponse.ok(null);
  }

  @GetMapping
  public ApiResponse<List<DatasourceVO>> list() {
    return ApiResponse.ok(service.list());
  }

  @GetMapping("/{id}")
  public ApiResponse<DatasourceVO> get(@PathVariable Long id) {
    return ApiResponse.ok(DatasourceVO.from(service.getEnabled(id)));
  }

  @PostMapping("/{id}/status")
  public ApiResponse<Void> setStatus(@PathVariable Long id, @RequestParam boolean enable) {
    service.setStatus(id, enable);
    return ApiResponse.ok(null);
  }
}
