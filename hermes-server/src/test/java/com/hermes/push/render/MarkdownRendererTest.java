package com.hermes.push.render;

import com.hermes.push.common.BizException;
import com.hermes.push.dataset.ColumnMeta;
import com.hermes.push.dataset.DatasetResult;
import com.hermes.push.task.TaskConfig;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class MarkdownRendererTest {
  SandboxFreemarker fm = new SandboxFreemarker();
  MarkdownRenderer r = new MarkdownRenderer(fm, new TemplateScanner());

  DatasetResult ds(List<Map<String,Object>> rows) {
    return new DatasetResult() {
      public List<ColumnMeta> columns() { return List.of(new ColumnMeta("amount","DECIMAL"), new ColumnMeta("name","VARCHAR")); }
      public int totalRows() { return rows.size(); }
      public boolean truncated() { return false; }
      public void forEachRow(Consumer<Map<String,Object>> c) { rows.forEach(c); }
      public List<Map<String,Object>> toList(int max) { return rows.stream().limit(max).toList(); }
    };
  }
  Map<String,DatasetResult> model() {
    return Map.of("ds1", ds(List.of(
        Map.of("name","成都","amount", new java.math.BigDecimal("312004")),
        Map.of("name","绵阳","amount", new java.math.BigDecimal("208771")))));
  }
  TaskConfig.ArtifactDef def(String tpl) {
    return new TaskConfig.ArtifactDef("a1","MARKDOWN", tpl, "TRUNCATE", 4096);
  }

  @Test void rendersLoopAndFormatters() {
    var out = r.render(def("**日报**\n<#list ds1.rows as row>- ${row.name}: ${fmtNumber(row.amount)}\n</#list>合计占比 ${fmtPercent(0.1234,2)}"),
        model(), Map.of("bizDate","2026-09-21"), 30);
    assertThat(out.content()).contains("**日报**").contains("成都: 312,004").contains("12.34%");
    assertThat(out.bytes()).isEqualTo(out.content().getBytes(StandardCharsets.UTF_8).length);
  }
  @Test void missingField_tpl003WithLineNumber() {
    assertThatThrownBy(() -> r.render(def("line1\n${ds1.rows[0].noSuchField}"), model(), Map.of(), 30))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-003").hasMessageContaining("line 2");
  }
  @Test void sandboxBlocksNewBuiltin() {
    assertThatThrownBy(() -> r.render(def("<#assign ex='freemarker.template.utility.Execute'?new()>${ex('whoami')}"), model(), Map.of(), 30))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-010");
  }
  @Test void sandboxBlocksApiBuiltin() {
    assertThatThrownBy(() -> r.render(def("${'x'?api.class.forName('java.lang.Runtime')}"), model(), Map.of(), 30))
        .isInstanceOf(BizException.class).hasMessageContaining("TPL-010");
  }
  @Test void renderTimeout_sys005() {
    assertThatThrownBy(() -> r.render(def("<#list 1.. as i>${i}</#list>"), model(), Map.of(), 1))
        .isInstanceOf(BizException.class).hasMessageContaining("SYS-005");
  }
  @Test void truncateRespectsUtf8Bytes() {
    String big = "中文".repeat(3000); // 18000 bytes
    String t = r.enforceBytes(big, 4096, "TRUNCATE");
    assertThat(t.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
    assertThat(t).endsWith("…（内容超长已截断）");
  }
  @Test void failStrategy_rd002() {
    assertThatThrownBy(() -> r.enforceBytes("x".repeat(5000), 4096, "FAIL"))
        .isInstanceOf(BizException.class).hasMessageContaining("RD-002");
  }
  @Test void includeDirectiveRejected() {
    // 空 StringTemplateLoader 下，#include/#import 找不到任何模板 → TPL-003，证明本地文件不可读
    BizException ex = catchThrowableOfType(() ->
        r.render(def("<#include \"/etc/passwd\">"), model(), Map.of(), 30), BizException.class);
    assertThat(ex).isNotNull();
    assertThat(ex.getErrorCode().getCode()).startsWith("TPL-");
  }
  @Test void concatenatedPayloadStillBlockedBySandbox() {
    // 证明：即使扫描层被拼接绕过，沙箱层仍然拦截 ?new 类解析（无 RCE）
    // 直接调 SandboxFreemarker.render 绕过 MarkdownRenderer 入口 scan
    // 模板语法完全合法；?new 被字符串拼接拆开（扫描层看不见完整关键字）
    // ?eval 在求值期执行拼接结果，触发 ALLOWS_NOTHING_RESOLVER 拒绝类实例化
    String template = "<#assign cls=\"'freemarker.template.utility.Execute'?n\" + \"ew()\">"
        + "<#assign ex=cls?eval>${ex('whoami')}";
    BizException ex = catchThrowableOfType(() ->
        fm.render("sandbox-probe", template, Map.of(), 5), BizException.class);
    assertThat(ex).isNotNull();
    // 1. 证明已过解析阶段（不是"模板解析失败"，死在求值期）
    assertThat(ex.getDetail()).doesNotContain("模板解析失败");
    // 2. 证明是 ALLOWS_NOTHING_RESOLVER 沙箱拒绝
    //    特征消息："Instantiating freemarker.template.utility.Execute is not allowed
    //    in the template for security reasons."
    assertThat(ex.getDetail()).contains("not allowed");
    assertThat(ex.getDetail()).contains("Execute");
    // 3. 证明抛异常而非返回输出（whoami 未被执行，无命令输出）
    assertThat(ex.getErrorCode().getCode()).startsWith("TPL-");
    // 4. 定位证据：求值期 ?eval 触发的失败（FTL stack trace 显示 #assign ex = cls?eval）
    assertThat(ex.getDetail()).contains("cls?eval");
  }
  @Test void enforceBytesTooSmallBudget_rd002() {
    // maxBytes 小于后缀长度时，即使 TRUNCATE 也无法在预算内输出 → RD_002
    assertThatThrownBy(() -> r.enforceBytes("x".repeat(100), 10, "TRUNCATE"))
        .isInstanceOf(BizException.class).hasMessageContaining("RD-002");
  }
}
