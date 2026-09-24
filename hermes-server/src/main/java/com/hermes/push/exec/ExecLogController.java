package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hermes.push.common.ApiResponse;
import com.hermes.push.exec.vo.ExecDetailVO;
import com.hermes.push.exec.vo.ExecListVO;
import com.hermes.push.exec.vo.TodaySummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/execs")
@RequiredArgsConstructor
public class ExecLogController {

  private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final ExecLogService execLogService;

  @GetMapping
  public ApiResponse<IPage<ExecListVO>> list(
      @RequestParam(required = false) Long taskId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String triggerType,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {

    LocalDateTime fromDt = from != null && !from.isBlank() ? LocalDateTime.parse(from, ISO_DT) : null;
    LocalDateTime toDt = to != null && !to.isBlank() ? LocalDateTime.parse(to, ISO_DT) : null;

    IPage<ExecListVO> result = execLogService.list(taskId, status, triggerType, fromDt, toDt, page, size);
    return ApiResponse.ok(result);
  }

  @GetMapping("/{id}")
  public ApiResponse<ExecDetailVO> detail(@PathVariable Long id) {
    ExecDetailVO detail = execLogService.detail(id);
    return ApiResponse.ok(detail);
  }

  @GetMapping("/today-summary")
  public ApiResponse<TodaySummaryVO> todaySummary() {
    TodaySummaryVO summary = execLogService.todaySummary();
    return ApiResponse.ok(summary);
  }
}
