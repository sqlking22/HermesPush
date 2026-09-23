package com.hermes.push.render;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.dataset.ColumnMeta;
import com.hermes.push.dataset.DatasetResult;
import com.hermes.push.task.TaskConfig;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 渲染器：基于沙箱化 Freemarker 渲染 Markdown 模板。
 *
 * 双保险机制：
 * 1. 入库时 TemplateScanAdapter 扫描（Task 12 校验链路）
 * 2. 渲染时本类入口再次扫描（防绕过入库）
 *
 * model 结构：
 * - 每个 dataset key → Map{rows, columns, totalRows, truncated}
 * - params（Map<String,String>）
 * - bizDate / runDate（字符串）
 * - 内置函数：fmtNumber, fmtPercent, fmtDate, dft
 */
@Component
public class MarkdownRenderer {

  private static final String TRUNCATE_SUFFIX = "\n…（内容超长已截断）";
  private static final byte[] TRUNCATE_SUFFIX_BYTES = TRUNCATE_SUFFIX.getBytes(StandardCharsets.UTF_8);

  private final SandboxFreemarker freemarker;
  private final TemplateScanner scanner;

  public MarkdownRenderer(SandboxFreemarker freemarker, TemplateScanner scanner) {
    this.freemarker = freemarker;
    this.scanner = scanner;
  }

  public RenderedArtifact render(TaskConfig.ArtifactDef def,
                                 Map<String, DatasetResult> datasets,
                                 Map<String, String> params,
                                 int timeoutSec) {
    String content = def.inlineTemplate();
    // 双保险：渲染入口先扫描危险指令，防绕过入库
    scanner.scan(content);

    Map<String, Object> model = buildModel(datasets, params);

    String rendered = freemarker.render(def.key(), content, model, timeoutSec);

    // 字节数限制处理
    int maxBytes = def.maxBytes() != null ? def.maxBytes() : Integer.MAX_VALUE;
    String strategy = def.overflowStrategy() != null ? def.overflowStrategy() : "TRUNCATE";
    String finalContent = enforceBytes(rendered, maxBytes, strategy);

    int bytes = finalContent.getBytes(StandardCharsets.UTF_8).length;
    return new RenderedArtifact(def.key(), def.type(), finalContent, bytes);
  }

  private Map<String, Object> buildModel(Map<String, DatasetResult> datasets,
                                         Map<String, String> params) {
    Map<String, Object> model = new HashMap<>();

    // 每个 dataset key → {rows, columns, totalRows, truncated}
    for (Map.Entry<String, DatasetResult> entry : datasets.entrySet()) {
      DatasetResult ds = entry.getValue();
      Map<String, Object> dsMap = new HashMap<>();
      dsMap.put("rows", ds.toList(5000));
      dsMap.put("columns", ds.columns());
      dsMap.put("totalRows", ds.totalRows());
      dsMap.put("truncated", ds.truncated());
      model.put(entry.getKey(), dsMap);
    }

    // params
    model.put("params", params != null ? params : Map.of());

    // 时间变量
    if (params != null && params.containsKey("bizDate")) {
      model.put("bizDate", params.get("bizDate"));
    }
    model.put("runDate", LocalDate.now().toString());

    // 内置函数
    model.put("fmtNumber", FMT_NUMBER);
    model.put("fmtPercent", FMT_PERCENT);
    model.put("fmtDate", FMT_DATE);
    model.put("dft", DFT);

    return model;
  }

  // ---- 内置模板函数 ----

  private static final TemplateMethodModelEx FMT_NUMBER = args -> {
    if (args.isEmpty()) return "";
    Object val = unwrap(args.get(0));
    if (val == null) return "";
    DecimalFormat df = new DecimalFormat("#,##0.##");
    return df.format(toNumber(val));
  };

  private static final TemplateMethodModelEx FMT_PERCENT = args -> {
    if (args.size() < 2) return "";
    Object val = unwrap(args.get(0));
    Object digits = unwrap(args.get(1));
    if (val == null) return "";
    double d = toNumber(val).doubleValue() * 100;
    int dec = toNumber(digits).intValue();
    String pattern = "0." + "0".repeat(Math.max(0, dec));
    DecimalFormat df = new DecimalFormat(pattern);
    return df.format(d) + "%";
  };

  private static final TemplateMethodModelEx FMT_DATE = args -> {
    if (args.size() < 2) return "";
    Object iso = unwrap(args.get(0));
    Object pattern = unwrap(args.get(1));
    if (iso == null || pattern == null) return "";
    LocalDate date = LocalDate.parse(iso.toString());
    return date.format(DateTimeFormatter.ofPattern(pattern.toString()));
  };

  private static final TemplateMethodModelEx DFT = args -> {
    if (args.size() < 2) return "";
    Object val = unwrap(args.get(0));
    Object def = unwrap(args.get(1));
    if (val == null || (val instanceof String s && s.isEmpty())) {
      return def == null ? "" : def.toString();
    }
    return val.toString();
  };

  /** 把 Freemarker 包装对象解包为原始值 */
  private static Object unwrap(Object o) {
    if (o instanceof freemarker.template.TemplateScalarModel sm) {
      try { return sm.getAsString(); } catch (TemplateModelException e) { return null; }
    }
    if (o instanceof freemarker.template.TemplateNumberModel nm) {
      try { return nm.getAsNumber(); } catch (TemplateModelException e) { return null; }
    }
    return o;
  }

  private static Number toNumber(Object val) {
    if (val instanceof Number n) return n;
    if (val == null) return 0;
    // 字符串尝试解析为 BigDecimal
    try { return new java.math.BigDecimal(val.toString()); }
    catch (Exception e) { return 0; }
  }

  // ---- 字节截断 ----

  /**
   * 按 UTF-8 字节数限制处理内容。
   *
   * @param content  原始内容
   * @param maxBytes 最大字节数
   * @param strategy TRUNCATE / FAIL
   * @return 处理后的内容
   */
  public String enforceBytes(String content, int maxBytes, String strategy) {
    if (content == null) content = "";
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return content;
    }
    if ("FAIL".equals(strategy)) {
      throw new BizException(ErrorCode.RD_002,
          "内容字节数 " + bytes.length + " 超过限制 " + maxBytes);
    }
    if (!"TRUNCATE".equals(strategy)) {
      throw new BizException(ErrorCode.RD_002, "非法 overflowStrategy: " + strategy);
    }
    // TRUNCATE：按 UTF-8 字节截断，不切断多字节字符，追加后缀
    int suffixLen = TRUNCATE_SUFFIX_BYTES.length;
    if (maxBytes <= suffixLen) {
      // 预算扣除后缀后无剩余空间，视为配置错误
      throw new BizException(ErrorCode.RD_002,
          "maxBytes 配置过小，不足以容纳截断后缀（需要至少 " + (suffixLen + 1) + " 字节）");
    }
    int budget = maxBytes - suffixLen;
    // 从 budget 处向前回退到字符边界
    int cut = budget;
    while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
      cut--;
    }
    String head = new String(bytes, 0, cut, StandardCharsets.UTF_8);
    return head + TRUNCATE_SUFFIX;
  }
}
