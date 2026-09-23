package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ParamResolver {
  private static final Pattern HASH = Pattern.compile("#\\{([A-Za-z_]\\w*(?:[+-]\\d+[dwMy])*(?::[^}]+)?)\\}");
  private static final Pattern DOLLAR = Pattern.compile("\\$\\{(\\w+)\\}");
  private static final Pattern TIME_BASE = Pattern.compile("^(runDate|bizDate)");

  private final TimeVariableResolver time;

  public PreparedSql prepare(String rawSql, List<ParameterDefinition> defs,
                             Map<String, String> staticParams, Map<String, String> runtimeParams,
                             LocalDate runDate, int bizOffsetDays) {
    Map<String, ParameterDefinition> defMap = new HashMap<>();
    defs.forEach(d -> defMap.put(d.name(), d));
    Map<String, String> audit = new LinkedHashMap<>();
    List<Object> binds = new ArrayList<>();

    // 1) ${name} 文本替换（先做，避免 #{} 占位后位置漂移）
    // 校验顺序：def 存在 → allowTextSubstitution → 值非空 → pattern 匹配
    Matcher dm = DOLLAR.matcher(rawSql);
    StringBuilder sb = new StringBuilder();
    while (dm.find()) {
      String name = dm.group(1);
      ParameterDefinition def = defMap.get(name);
      if (def == null) {
        throw new BizException(ErrorCode.SQL_005, "参数 " + name + " 未定义");
      }
      if (!def.allowTextSubstitution()) {
        throw new BizException(ErrorCode.SQL_005, "参数 " + name + " 未启用文本替换");
      }
      String value = firstNonNull(runtimeParams.get(name), staticParams.get(name), def.defaultValue());
      if (value == null) {
        throw new BizException(ErrorCode.SQL_005, "参数 " + name + " 无值");
      }
      if (def.whitelistPattern() == null || !value.matches(def.whitelistPattern())) {
        throw new BizException(ErrorCode.SQL_005, "参数 " + name + " 文本替换校验失败");
      }
      audit.put(name, value);
      dm.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    dm.appendTail(sb);

    // 2) #{token} → ? 绑定
    Matcher hm = HASH.matcher(sb.toString());
    StringBuilder out = new StringBuilder();
    while (hm.find()) {
      String token = hm.group(1);
      // 提取基名（去掉偏移和 pattern）
      String baseName = extractBaseName(token);

      String value;
      boolean isTimeVariable = isTimeVariable(token);

      if (isTimeVariable) {
        // 裁决 B：时间变量始终走 TimeVariableResolver
        // combinedOverrides = static 为底 + runtime 覆盖同名（优先级 runtime > static）
        Map<String, String> combinedOverrides = new HashMap<>(staticParams);
        combinedOverrides.putAll(runtimeParams);
        value = time.resolve(token, runDate, bizOffsetDays, combinedOverrides);
      } else {
        // 非时间变量：runtime > static > default
        ParameterDefinition def = defMap.get(baseName);
        value = firstNonNull(runtimeParams.get(baseName), staticParams.get(baseName),
            def == null ? null : def.defaultValue());
      }

      ParameterDefinition def = defMap.get(baseName);

      if (value == null) {
        if (def != null && def.required()) {
          throw new BizException(ErrorCode.SQL_004, "参数 " + baseName);
        }
        if (def == null && !isTimeVariable) {
          throw new BizException(ErrorCode.SQL_004, "参数 " + baseName + "（无定义且非内置时间变量）");
        }
        // 裁决 A：可选且无值 → 绑定 NULL
        binds.add(null);
        audit.putIfAbsent(baseName, "<null>");
      } else {
        ParamType type;
        if (def != null) {
          type = def.type();
        } else if (isTimeVariable) {
          // 时间变量无显式 def：无 pattern → DATE，有 pattern → STRING
          type = token.contains(":") ? ParamType.STRING : ParamType.DATE;
        } else {
          type = ParamType.STRING;
        }
        try {
          binds.add(convert(value, type));
        } catch (IllegalArgumentException | DateTimeParseException e) {
          String firstLine = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
          throw new BizException(ErrorCode.SQL_004, "参数 " + baseName + " 类型转换失败: " + firstLine);
        }
        audit.putIfAbsent(baseName, value);
      }
      hm.appendReplacement(out, "?");
    }
    hm.appendTail(out);
    return new PreparedSql(out.toString(), binds, audit);
  }

  private String extractBaseName(String token) {
    // 去掉 pattern 部分
    String noPattern = token.contains(":") ? token.substring(0, token.indexOf(':')) : token;
    // 去掉偏移部分（第一个 [+-]\d+[dwMy] 之前的部分）
    Matcher om = Pattern.compile("^([A-Za-z_]\\w*)").matcher(noPattern);
    if (om.find()) return om.group(1);
    return noPattern;
  }

  private boolean isTimeVariable(String token) {
    String baseName = extractBaseName(token);
    return baseName.equals("runDate") || baseName.equals("bizDate");
  }

  private boolean tryParseDate(String value) {
    try {
      LocalDate.parse(value);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private Object convert(String v, ParamType t) {
    return switch (t) {
      case INT -> Long.parseLong(v);
      case DECIMAL -> new BigDecimal(v);
      case DATE -> convertDate(v);
      case STRING -> v;
    };
  }

  private java.sql.Date convertDate(String v) {
    if (v.length() == 10 && v.charAt(4) == '-') {
      return java.sql.Date.valueOf(v);
    }
    if (v.length() == 8 && v.matches("\\d{8}")) {
      LocalDate d = LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE);
      return java.sql.Date.valueOf(d);
    }
    throw new IllegalArgumentException("无法解析为日期: " + v);
  }

  private String firstNonNull(String... values) {
    for (String v : values) {
      if (v != null) return v;
    }
    return null;
  }
}
