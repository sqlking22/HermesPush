package com.hermes.push.datasource;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.dataset.DatasetResult;
import com.hermes.push.dataset.PreparedSql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QueryEngineTest extends AbstractIntegrationTest {
  @Autowired QueryEngine engine;
  @Autowired DatasourceService svc;

  // 种子库与测试库分离：hermes_test 每用例被 flyway clean 重建，6 万行 big_t 放 hermes_seed（每 JVM 只灌一次，不受 clean 影响）
  static final String SEED_DB_URL = TEST_DB_URL.replace("hermes_test", "hermes_seed");
  static boolean seeded = false;

  Long dsId(int maxRows, String overflow, int timeoutSec) {
    Long id = svc.save(new DatasourceSaveRequest("qe-" + System.nanoTime(), "MYSQL",
        SEED_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, true, maxRows, timeoutSec, 2, overflow), "admin");
    return id;
  }

  void seedRows() {
    if (seeded) return;
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(SEED_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD);
         java.sql.Statement st = c.createStatement()) {
      st.execute("CREATE TABLE IF NOT EXISTS big_t (id INT PRIMARY KEY, d DATE, amt DECIMAL(12,2))");
      var rs = st.executeQuery("SELECT COUNT(*) FROM big_t"); rs.next();
      if (rs.getInt(1) == 0) {
        StringBuilder sb = new StringBuilder("INSERT INTO big_t VALUES ");
        for (int i = 1; i <= 60001; i++) {
          sb.append("(").append(i).append(",'2026-09-21',").append(i).append(".5)");
          if (i < 60001) sb.append(",");
        }
        st.execute(sb.toString());
      }
    } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    seeded = true;
  }

  @Test void truncateAtMaxRows() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(50000, "TRUNCATE", 60));
    DatasetResult r = engine.execute(ds, new PreparedSql("SELECT id,d,amt FROM big_t", List.of(), Map.of()), 0, null);
    assertThat(r.totalRows()).isEqualTo(50000);
    assertThat(r.truncated()).isTrue();
    assertThat(r.columns()).extracting("name").contains("id","d","amt");
  }
  @Test void failPolicyThrows() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(100, "FAIL", 60));
    assertThatThrownBy(() -> engine.execute(ds, new PreparedSql("SELECT id FROM big_t", List.of(), Map.of()), 0, null))
        .isInstanceOf(BizException.class).hasMessageContaining("SQL-003");
  }
  @Test void timeout_ds002() {
    Datasource ds = svc.getEnabled(dsId(100, "TRUNCATE", 1));
    assertThatThrownBy(() -> engine.execute(ds, new PreparedSql("SELECT SLEEP(5)", List.of(), Map.of()), 0, null))
        .isInstanceOf(BizException.class).hasMessageContaining("DS-002");
  }
  @Test void typeMapping() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(10, "TRUNCATE", 60));
    DatasetResult r = engine.execute(ds, new PreparedSql("SELECT d, amt FROM big_t WHERE id=1", List.of(), Map.of()), 0, null);
    List<Map<String,Object>> rows = r.toList(10);
    assertThat(rows.get(0).get("d")).isEqualTo("2026-09-21");
    assertThat((java.math.BigDecimal) rows.get(0).get("amt")).isEqualByComparingTo("1.5");
  }
  @Test void rowLimitOverrideForPreview() {
    seedRows();
    Datasource ds = svc.getEnabled(dsId(50000, "TRUNCATE", 60));
    DatasetResult r = engine.execute(ds, new PreparedSql("SELECT id FROM big_t", List.of(), Map.of()), 200, null);
    assertThat(r.totalRows()).isEqualTo(200);
  }
}
