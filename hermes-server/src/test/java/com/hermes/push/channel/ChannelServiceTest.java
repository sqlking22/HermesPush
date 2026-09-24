package com.hermes.push.channel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.datasource.TestResultVO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class ChannelServiceTest extends AbstractIntegrationTest {
  static WireMockServer wm;
  @Autowired ChannelService svc; @Autowired JdbcTemplate jdbc;

  @BeforeAll static void startWm() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
  @AfterAll static void stopWm() { wm.stop(); }
  @BeforeEach void reset() { wm.resetAll(); }

  String webhookUrl() { return "http://localhost:" + wm.port() + "/cgi-bin/webhook/send?key=test"; }

  @Test void saveRejectsNonWhitelistedWebhook() {
    assertThatThrownBy(() -> svc.save("外部群", "WEWORK_BOT", "{\"webhook\":\"https://evil.example.com/x?key=abcd1234\"}", 20, 300, false, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("白名单");
  }
  @Test void voMasksWebhook() {
    Long id = svc.save("一大群", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=SECRET9999\"}", 20, 300, false, "admin");
    ChannelVO vo = svc.list().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    assertThat(vo.webhookMasked()).contains("qyapi.weixin.qq.com").contains("9999").doesNotContain("SECRET");
    assertThat(svc.getDecryptedWebhook(id)).contains("SECRET9999");
  }
  @Test void whitelistEntryReferencedCannotBeDeleted() {
    svc.save("二大群", "WEWORK_BOT", "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k2\"}", 20, 300, false, "admin");
    Long wlId = jdbc.queryForObject("SELECT id FROM hp_whitelist WHERE value='qyapi.weixin.qq.com'", Long.class);
    assertThatThrownBy(() -> svc.deleteWhitelist(wlId)).isInstanceOf(BizException.class).hasMessageContaining("引用");
  }

  @Test void healthCheckSendsTextMessage() {
    // insert localhost whitelist so webhook is allowed
    jdbc.update("INSERT INTO hp_whitelist(type,value,created_by) VALUES('WEBHOOK_HOST','localhost','test')");
    wm.stubFor(post(urlPathEqualTo("/cgi-bin/webhook/send"))
        .willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    Long id = svc.save("健康群", "WEWORK_BOT",
        "{\"webhook\":\"" + webhookUrl() + "\"}", 20, 300, false, "admin");
    TestResultVO r = svc.healthCheck(id);
    assertThat(r.ok()).isTrue();
    assertThat(r.dbVersion()).isNull();
    wm.verify(postRequestedFor(urlPathEqualTo("/cgi-bin/webhook/send"))
        .withRequestBody(matchingJsonPath("$.msgtype", equalTo("text")))
        .withRequestBody(matchingJsonPath("$.text.content", containing("健康检查"))));
  }
}
