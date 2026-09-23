package com.hermes.push;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
  /** 本地 MySQL 测试库（hermes_test 由 URL 参数自动建库）；业务表每用例 clean+migrate 重建 */
  public static final String TEST_DB_URL = "jdbc:mysql://localhost:3306/hermes_test?createDatabaseIfNotExist=true&useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
  public static final String TEST_DB_USER = "root";
  public static final String TEST_DB_PASSWORD = System.getenv().getOrDefault("HP_TEST_DB_PASSWORD", "123456");

  @Autowired Flyway flyway;

  @BeforeEach void resetSchema() { flyway.clean(); flyway.migrate(); } // 用例级隔离（无容器环境的替代方案）
}
