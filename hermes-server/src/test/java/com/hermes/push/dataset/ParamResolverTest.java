package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ParamResolverTest {
  static final LocalDate RUN = LocalDate.of(2026, 9, 22);
  ParamResolver resolver = new ParamResolver(new TimeVariableResolver());

  ParameterDefinition def(String name, ParamType t, boolean req, String dft, boolean textSub, String pattern) {
    return new ParameterDefinition(name, t, req, dft, textSub, pattern);
  }

  // ---- Step 1: 简报 7 个基础用例 ----

  @Test void bizDateDefaultsToRunMinusOne() {
    PreparedSql p = resolver.prepare("SELECT * FROM t WHERE dt = #{bizDate}",
        List.of(), Map.of(), Map.of(), RUN, -1);
    assertThat(p.sql()).isEqualTo("SELECT * FROM t WHERE dt = ?");
    assertThat(p.bindValues()).containsExactly(java.sql.Date.valueOf(LocalDate.of(2026, 9, 21)));
    assertThat(p.auditParams()).containsEntry("bizDate", "2026-09-21");
  }

  @Test void offsetAndPattern() {
    PreparedSql p = resolver.prepare("SELECT #{bizDate-1d:yyyyMMdd}, #{bizDate:yyyy-MM-01}",
        List.of(), Map.of(), Map.of(), RUN, -1);
    assertThat(p.bindValues()).containsExactly("20260920", "2026-09-01");
  }

  @Test void runtimeOverridesStaticOverridesDefault() {
    var defs = List.of(def("deptId", ParamType.INT, true, "1", false, null));
    assertThat(resolver.prepare("SELECT #{deptId}", defs, Map.of("deptId","2"), Map.of(), RUN, -1).bindValues())
        .containsExactly(2L);
    assertThat(resolver.prepare("SELECT #{deptId}", defs, Map.of("deptId","2"), Map.of("deptId","9"), RUN, -1).bindValues())
        .containsExactly(9L);
    assertThat(resolver.prepare("SELECT #{deptId}", defs, Map.of(), Map.of(), RUN, -1).bindValues())
        .containsExactly(1L);
  }

  @Test void requiredMissing_sql004() {
    var defs = List.of(def("branchId", ParamType.INT, true, null, false, null));
    assertThatThrownBy(() -> resolver.prepare("SELECT #{branchId}", defs, Map.of(), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-004");
  }

  @Test void textSubstitutionWhitelist() {
    var defs = List.of(def("weekTag", ParamType.STRING, true, null, true, "^\\d{4}W\\d{2}$"));
    PreparedSql ok = resolver.prepare("SELECT * FROM t WHERE wk = '${weekTag}'", defs, Map.of("weekTag","2026W38"), Map.of(), RUN, -1);
    assertThat(ok.sql()).contains("'2026W38'");
    assertThatThrownBy(() -> resolver.prepare("SELECT * FROM t WHERE wk = '${weekTag}'", defs, Map.of("weekTag","x' OR 1=1--"), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-005");
  }

  @Test void textSubstitutionNotEnabledRejected() {
    var defs = List.of(def("col", ParamType.STRING, true, null, false, null));
    assertThatThrownBy(() -> resolver.prepare("SELECT ${col} FROM t", defs, Map.of("col","a"), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-005");
  }

  @Test void decimalType() {
    var defs = List.of(def("th", ParamType.DECIMAL, true, null, false, null));
    assertThat(resolver.prepare("SELECT #{th}", defs, Map.of("th","1000.5"), Map.of(), RUN, -1).bindValues())
        .containsExactly(new BigDecimal("1000.5"));
  }

  // ---- 裁决 A：可选参数缺失绑定 NULL ----

  @Test void optionalParamMissingBindsNull() {
    var defs = List.of(def("opt", ParamType.STRING, false, null, false, null));
    PreparedSql p = resolver.prepare("SELECT #{opt}", defs, Map.of(), Map.of(), RUN, -1);
    assertThat(p.bindValues()).hasSize(1);
    assertThat(p.bindValues().get(0)).isNull();
    assertThat(p.auditParams()).containsEntry("opt", "<null>");
  }

  // ---- 裁决 B：运行时覆盖 bizDate 保留偏移与格式化 ----

  @Test void runtimeBizDateOverrideKeepsOffsetAndPattern() {
    PreparedSql p = resolver.prepare("SELECT #{bizDate}, #{bizDate:yyyyMMdd}, #{bizDate-1d}",
        List.of(), Map.of(), Map.of("bizDate", "2026-09-01"), RUN, -1);
    assertThat(p.bindValues()).containsExactly(
        java.sql.Date.valueOf(LocalDate.of(2026, 9, 1)),
        "20260901",
        java.sql.Date.valueOf(LocalDate.of(2026, 8, 31)));
    assertThat(p.auditParams()).containsEntry("bizDate", "2026-09-01");
  }

  @Test void invalidBizDateOverride_sql004() {
    assertThatThrownBy(() -> resolver.prepare("SELECT #{bizDate}",
        List.of(), Map.of(), Map.of("bizDate", "2026/09/01"), RUN, -1))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("SQL-004")
        .hasMessageContaining("bizDate");
  }

  // ---- 评审轮 1 修复 ----

  @Test void paramNameStartingWithBizDateNotTimeVariable() {
    var defs = List.of(def("bizDateRegion", ParamType.STRING, true, null, false, null));
    PreparedSql p = resolver.prepare("SELECT #{bizDateRegion}", defs,
        Map.of(), Map.of("bizDateRegion", "chengdu"), RUN, -1);
    assertThat(p.bindValues()).containsExactly("chengdu");
    assertThat(p.auditParams()).containsEntry("bizDateRegion", "chengdu");
  }

  @Test void staticBizDateOverrideAppliesWithPattern() {
    // static 覆盖生效
    PreparedSql p1 = resolver.prepare("SELECT #{bizDate}, #{bizDate:yyyyMMdd}",
        List.of(), Map.of("bizDate", "2026-01-01"), Map.of(), RUN, -1);
    assertThat(p1.bindValues()).containsExactly(
        java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)),
        "20260101");

    // runtime 同名覆盖 static
    PreparedSql p2 = resolver.prepare("SELECT #{bizDate}",
        List.of(), Map.of("bizDate", "2026-01-01"), Map.of("bizDate", "2026-06-15"), RUN, -1);
    assertThat(p2.bindValues()).containsExactly(
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 15)));
  }

  @Test void intParamBadValue_sql004() {
    var defs = List.of(def("cnt", ParamType.INT, true, null, false, null));
    assertThatThrownBy(() -> resolver.prepare("SELECT #{cnt}", defs,
        Map.of("cnt", "abc"), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("SQL-004")
        .hasMessageContaining("cnt");
  }

  @Test void dateParamEightDigits() {
    var defs = List.of(def("dt", ParamType.DATE, true, null, false, null));
    PreparedSql p = resolver.prepare("SELECT #{dt}", defs,
        Map.of("dt", "20260920"), Map.of(), RUN, -1);
    assertThat(p.bindValues()).containsExactly(java.sql.Date.valueOf(LocalDate.of(2026, 9, 20)));
  }

  @Test void dateParamGarbage_sql004() {
    var defs = List.of(def("dt", ParamType.DATE, true, null, false, null));
    assertThatThrownBy(() -> resolver.prepare("SELECT #{dt}", defs,
        Map.of("dt", "abc"), Map.of(), RUN, -1))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("SQL-004");
  }
}
