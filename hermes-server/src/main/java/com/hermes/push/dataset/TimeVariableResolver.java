package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TimeVariableResolver {
  // 例：bizDate-1d:yyyyMMdd、runDate+2w、bizDate-1M+3d:yyyy-MM-dd
  private static final Pattern TOKEN = Pattern.compile("^(runDate|bizDate)((?:[+-]\\d+[dwMy])*)(?::(.+))?$");
  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  public String resolve(String token, LocalDate runDate, int bizOffsetDays) {
    return resolve(token, runDate, bizOffsetDays, Map.of());
  }

  public String resolve(String token, LocalDate runDate, int bizOffsetDays, Map<String, String> overrides) {
    Matcher m = TOKEN.matcher(token);
    if (!m.matches()) return null; // 非时间变量，交给参数解析

    String baseName = m.group(1);
    LocalDate base;

    String overrideVal = overrides.get(baseName);
    if (overrideVal != null) {
      try {
        base = LocalDate.parse(overrideVal, ISO_DATE);
      } catch (DateTimeParseException e) {
        throw new BizException(ErrorCode.SQL_004, "参数 " + baseName + " 日期格式非法，应为 yyyy-MM-dd");
      }
      // 覆盖值直接作为基准日期，不再加 bizOffsetDays
    } else {
      base = baseName.equals("runDate") ? runDate : runDate.plusDays(bizOffsetDays);
    }

    String offsets = m.group(2);
    if (offsets != null && !offsets.isEmpty()) {
      Matcher om = Pattern.compile("([+-])(\\d+)([dwMy])").matcher(offsets);
      while (om.find()) {
        long n = Long.parseLong(om.group(2)) * (om.group(1).equals("-") ? -1 : 1);
        base = switch (om.group(3)) {
          case "d" -> base.plusDays(n);
          case "w" -> base.plusWeeks(n);
          case "M" -> base.plusMonths(n);
          case "y" -> base.plusYears(n);
          default -> base;
        };
      }
    }

    String pattern = m.group(3);
    return base.format(pattern == null || pattern.isBlank()
        ? ISO_DATE : DateTimeFormatter.ofPattern(pattern));
  }
}
