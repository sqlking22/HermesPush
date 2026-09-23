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
}
