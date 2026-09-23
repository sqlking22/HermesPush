package com.hermes.push.datasource;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class DatasourceServiceTest extends AbstractIntegrationTest {
  @Autowired DatasourceService svc;
  @Autowired JdbcTemplate jdbc;

  DatasourceSaveRequest req(String name, String pwd) {
    return new DatasourceSaveRequest(name, "MYSQL",
        "jdbc:mysql://10.0.0.1:3306/trade", "ro_user", pwd, true,
        null, null, null, null);
  }

  @Test void saveStoresCipherNotPlain_andVoNeverEchoes() {
    Long id = svc.save(req("ds-a", "S3cret!"), "admin");
    String cipher = jdbc.queryForObject("SELECT password_cipher FROM hp_datasource WHERE id=?", String.class, id);
    assertThat(cipher).doesNotContain("S3cret");
    assertThat(svc.list()).allSatisfy(vo -> assertThat(vo.toString()).doesNotContain("S3cret"));
    assertThat(svc.list().get(0).hasPassword()).isTrue();
  }
  @Test void updateWithBlankPasswordKeepsOld() {
    Long id = svc.save(req("ds-b", "OldPwd"), "admin");
    String before = jdbc.queryForObject("SELECT password_cipher FROM hp_datasource WHERE id=?", String.class, id);
    svc.update(id, req("ds-b", ""), "admin");
    String after = jdbc.queryForObject("SELECT password_cipher FROM hp_datasource WHERE id=?", String.class, id);
    assertThat(after).isEqualTo(before);
  }
  @Test void saveWithoutRoConfirmRejected() {
    var r = new DatasourceSaveRequest("ds-c","MYSQL","jdbc:mysql://x/y","u","p",false,null,null,null,null);
    assertThatThrownBy(() -> svc.save(r, "admin")).isInstanceOf(BizException.class).hasMessageContaining("SYS-002");
  }
  @Test void disableWithOnlineTaskReferenceRejected() {
    Long id = svc.save(req("ds-d", "pwd"), "admin");
    // 造一个引用该数据源的上线任务（config_json 直插，绕过 TaskService——该任务表已存在）
    jdbc.update("INSERT INTO hp_task(name,task_key,task_type,status,owner,current_version_id) VALUES('t','t1','REPORT','ONLINE','admin',1)");
    jdbc.update("INSERT INTO hp_task_version(id,task_id,version_no,config_json) VALUES(1,1,1,JSON_OBJECT('datasets',JSON_ARRAY(JSON_OBJECT('datasourceId',?))))", id);
    assertThatThrownBy(() -> svc.setStatus(id, false)).isInstanceOf(BizException.class).hasMessageContaining("1 个上线任务");
  }
  @Test void updateRefreshesUpdatedAtByDb() throws InterruptedException {
    Long id = svc.save(req("ds-e", "pwd"), "admin");
    LocalDateTime createdBefore = jdbc.queryForObject("SELECT created_at FROM hp_datasource WHERE id=?", LocalDateTime.class, id);
    LocalDateTime updatedBefore = jdbc.queryForObject("SELECT updated_at FROM hp_datasource WHERE id=?", LocalDateTime.class, id);
    assertThat(createdBefore).isNotNull();
    assertThat(updatedBefore).isNotNull();
    Thread.sleep(30);
    svc.update(id, req("ds-e-renamed", ""), "admin");
    LocalDateTime createdAfter = jdbc.queryForObject("SELECT created_at FROM hp_datasource WHERE id=?", LocalDateTime.class, id);
    LocalDateTime updatedAfter = jdbc.queryForObject("SELECT updated_at FROM hp_datasource WHERE id=?", LocalDateTime.class, id);
    assertThat(createdAfter).isEqualTo(createdBefore);
    assertThat(updatedAfter).isAfter(updatedBefore);
  }
}
