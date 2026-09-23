package com.hermes.push.datasource;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTesterTest extends AbstractIntegrationTest {
  @Autowired ConnectionTester tester;
  @Autowired DatasourceService svc;

  Datasource build(String url, String user, String pwd) {
    Long id = svc.save(new DatasourceSaveRequest("ct-" + System.nanoTime(), "MYSQL", url, user, pwd, true, null, 10, null, null), "admin");
    return svc.getEnabled(id);
  }

  @Test void okPath() {
    var r = tester.test(build(TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD));
    assertThat(r.ok()).isTrue();
    assertThat(r.dbVersion()).contains("8.0");
  }
  @Test void wrongPassword_ds003() {
    var r = tester.test(build(TEST_DB_URL, TEST_DB_USER, "wrong-password"));
    assertThat(r.ok()).isFalse(); assertThat(r.errorCode()).isEqualTo("DS-003");
  }
  @Test void unknownDb_ds001() {
    var r = tester.test(build(TEST_DB_URL.replace("hermes_test", "no_such_db_xyz"), TEST_DB_USER, TEST_DB_PASSWORD));
    assertThat(r.ok()).isFalse(); assertThat(r.errorCode()).isEqualTo("DS-001");
  }
  @Test void unreachable_ds002() {
    var r = tester.test(build("jdbc:mysql://127.0.0.1:1/x?connectTimeout=3000", "root", "root"));
    assertThat(r.ok()).isFalse(); assertThat(r.errorCode()).isEqualTo("DS-002");
  }
}
