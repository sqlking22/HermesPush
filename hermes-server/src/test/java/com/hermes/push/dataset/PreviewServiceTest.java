package com.hermes.push.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.datasource.DatasourceSaveRequest;
import com.hermes.push.datasource.DatasourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class PreviewServiceTest extends AbstractIntegrationTest {
  @Autowired MockMvc mvc; @Autowired DatasourceService dsSvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper om;

  Long ds(LocalDate bizDate) {
    jdbc.execute("CREATE TABLE IF NOT EXISTS pv_t(a INT, dt DATE)");
    jdbc.update("INSERT IGNORE INTO pv_t VALUES (1,?),(2,?)", bizDate, bizDate);
    return dsSvc.save(new DatasourceSaveRequest("pv-" + System.nanoTime(), "MYSQL", TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD, true, null, 30, null, null), "admin");
  }

  @Test void previewOk_andAuditsPreview() throws Exception {
    LocalDate bizDate = jdbc.queryForObject("SELECT DATE_SUB(CURDATE(), INTERVAL 1 DAY)", LocalDate.class);
    Long id = ds(bizDate);
    mvc.perform(post("/api/datasources/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(new PreviewRequest("SELECT a, dt FROM pv_t WHERE dt = #{bizDate}", Map.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalRows").value(2))
        .andExpect(jsonPath("$.data.rows[0].dt").value(bizDate.toString()));
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='PREVIEW' AND datasource_id=?", Integer.class, id);
    org.assertj.core.api.Assertions.assertThat(n).isEqualTo(1);
  }
  @Test void previewRejectsDml_andAuditsValidationFailed() throws Exception {
    Long id = ds(LocalDate.now());
    mvc.perform(post("/api/datasources/" + id + "/preview").contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(new PreviewRequest("DELETE FROM pv_t", Map.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.data.errorCode").value("SQL-002"));
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM hp_sql_audit WHERE scene='VALIDATION_FAILED' AND datasource_id=?", Integer.class, id);
    org.assertj.core.api.Assertions.assertThat(n).isEqualTo(1);
  }
}
