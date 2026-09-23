package com.hermes.push.schedule;

import com.hermes.push.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.quartz.CronExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@RestController
@RequestMapping("/api/cron")
@RequiredArgsConstructor
public class CronPreviewController {
  private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");
  private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final JdbcTemplate jdbc;

  @GetMapping("/preview")
  public ApiResponse<Map<String, Object>> preview(@RequestParam("expr") String expr) {
    if (expr == null || !CronExpression.isValidExpression(expr)) {
      return ApiResponse.ok(Map.of("valid", false, "next", List.of()));
    }
    try {
      CronExpression ce = new CronExpression(expr);
      ce.setTimeZone(SH);
      LocalDateTime now = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
      Date from = Date.from(now.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
      List<String> next = new ArrayList<>();
      Date cursor = from;
      for (int i = 0; i < 5; i++) {
        Date n = ce.getNextValidTimeAfter(cursor);
        if (n == null) break;
        next.add(n.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime().format(ISO_FMT));
        cursor = n;
      }
      return ApiResponse.ok(Map.of("valid", true, "next", next));
    } catch (Exception e) {
      return ApiResponse.ok(Map.of("valid", false, "next", List.of()));
    }
  }
}
