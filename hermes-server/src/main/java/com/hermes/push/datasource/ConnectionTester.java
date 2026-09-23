package com.hermes.push.datasource;

import com.hermes.push.common.ErrorCode;
import com.hermes.push.security.AesGcmCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

@Component @RequiredArgsConstructor
public class ConnectionTester {
  private final AesGcmCipher cipher;

  public TestResultVO test(Datasource d) { return doTest(d.getJdbcUrl(), d.getUsername(), cipher.decrypt(d.getPasswordCipher())); }
  public TestResultVO testInline(String url, String user, String pwd) { return doTest(url, user, pwd); }

  private TestResultVO doTest(String url, String user, String pwd) {
    long t0 = System.nanoTime();
    String testUrl = url + (url.contains("?") ? "&" : "?") + "createDatabaseIfNotExist=false";
    Properties p = new Properties(); p.setProperty("user", user); p.setProperty("password", pwd);
    p.setProperty("connectTimeout", "10000"); p.setProperty("socketTimeout", "10000");
    try (Connection c = DriverManager.getConnection(testUrl, p)) {
      String ver = c.getMetaData().getDatabaseProductVersion();
      c.createStatement().execute("SELECT 1");
      return new TestResultVO(true, ver, (System.nanoTime() - t0) / 1_000_000, null, null);
    } catch (Exception e) {
      String msg = String.valueOf(e.getMessage());
      ErrorCode ec = msg.contains("Access denied") ? ErrorCode.DS_003
                   : msg.contains("Unknown database") ? ErrorCode.DS_001
                   : ErrorCode.DS_002;
      return new TestResultVO(false, null, (System.nanoTime() - t0) / 1_000_000, ec.getCode(), ec.getUserMessage() + " | " + msg);
    }
  }
}
