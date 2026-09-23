package com.hermes.push.dataset;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DruidSqlValidatorTest {
  DruidSqlValidator v = new DruidSqlValidator();

  @Test void plainSelectPasses() {
    assertThat(v.validate("SELECT a, b FROM t WHERE dt = ?", "MYSQL")).contains("SELECT");
  }
  @Test void withCtePasses() {
    assertThat(v.validate("WITH x AS (SELECT 1 AS n) SELECT n FROM x", "MYSQL")).isNotBlank();
  }
  @ParameterizedTest
  @ValueSource(strings = {
      "UPDATE t SET a=1",
      "DELETE FROM t",
      "INSERT INTO t VALUES(1)",
      "DROP TABLE t",
      "TRUNCATE TABLE t",
      "CREATE TABLE x(a int)",
      "SELECT 1; DROP TABLE t",
      "SELECT 1; SELECT 2",
      "SELECT * FROM t INTO OUTFILE '/tmp/x'",
      "SELECT * FROM t INTO DUMPFILE '/tmp/x'"
  })
  void rejected(String sql) {
    assertThatThrownBy(() -> v.validate(sql, "MYSQL"))
        .isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).startsWith("SQL-"));
  }
  @Test void commentBypassRejected() {
    assertThatThrownBy(() -> v.validate("SELECT 1 /* ; */; DROP TABLE t -- x", "MYSQL"))
        .isInstanceOf(BizException.class);
  }
  @Test void garbageSyntax_sql001() {
    assertThatThrownBy(() -> v.validate("SELCT ((( FROM", "MYSQL"))
        .isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo("SQL-001"));
  }
  @Test void postgresFlavorOk() {
    assertThat(v.validate("SELECT 1", "POSTGRESQL")).isNotBlank();
  }
  // ---- 评审轮 1 新增 ----
  @Test void outfileCommentBypassRejected() {
    BizException e = catchThrowableOfType(() -> v.validate("SELECT * FROM t INTO /*x*/ OUTFILE '/tmp/y'", "MYSQL"), BizException.class);
    assertThat(e.getErrorCode().getCode()).isEqualTo("SQL-002");
  }
  @Test void forUpdateRejected() {
    BizException e = catchThrowableOfType(() -> v.validate("SELECT a FROM t WHERE id=? FOR UPDATE", "MYSQL"), BizException.class);
    assertThat(e.getErrorCode().getCode()).isEqualTo("SQL-002");
  }
  @Test void lockInShareModeRejected() {
    BizException e = catchThrowableOfType(() -> v.validate("SELECT a FROM t LOCK IN SHARE MODE", "MYSQL"), BizException.class);
    assertThat(e.getErrorCode().getCode()).isEqualTo("SQL-002");
  }
  @Test void unknownDsType_sql003() {
    BizException e = catchThrowableOfType(() -> v.validate("SELECT 1", "SQLSERVER"), BizException.class);
    assertThat(e.getErrorCode().getCode()).isEqualTo("SQL-003");
  }
  @Test void stringLiteralSemicolonPasses() {
    assertThat(v.validate("SELECT ';' AS x", "MYSQL")).isNotBlank();
  }
}
