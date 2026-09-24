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

  // --- 修复轮 1 新增测试 ---
  @Test void putByIdUpdatesCorrectChannel() {
    Long id1 = svc.save("渠道A", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k1\"}", 20, 300, false, "admin");
    Long id2 = svc.save("渠道B", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k2\"}", 20, 300, false, "admin");
    svc.update(id1, "渠道A", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k1\"}", 99, 300, false, "admin");
    ChannelVO vo1 = svc.list().stream().filter(v -> v.id().equals(id1)).findFirst().orElseThrow();
    ChannelVO vo2 = svc.list().stream().filter(v -> v.id().equals(id2)).findFirst().orElseThrow();
    assertThat(vo1.rateLimitPerMin()).isEqualTo(99);
    assertThat(vo2.rateLimitPerMin()).isEqualTo(20);
  }
  @Test void putWithOtherExistingNameRejected() {
    Long id1 = svc.save("渠道X", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k1\"}", 20, 300, false, "admin");
    svc.save("渠道Y", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k2\"}", 20, 300, false, "admin");
    assertThatThrownBy(() -> svc.update(id1, "渠道Y", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k1\"}", 20, 300, false, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("渠道名已存在");
  }
  @Test void putDeletedChannelRejected() {
    Long id = svc.save("待删渠道", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=kdel\"}", 20, 300, false, "admin");
    svc.delete(id);
    assertThatThrownBy(() -> svc.update(id, "待删渠道", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=kdel\"}", 20, 300, false, "admin"))
        .isInstanceOf(BizException.class).hasMessageContaining("SYS-003");
  }
  @Test void whitelistPostDuplicateReturnsExistingId() {
    // 预插一条同 (type,value) 模拟并发场景
    Whitelist pre = new Whitelist();
    pre.setType("WEBHOOK_HOST");
    pre.setValue("concurrent.example.com");
    pre.setCreatedBy("pre");
    jdbc.update("INSERT INTO hp_whitelist(type,value,created_by) VALUES(?,?,?)",
        pre.getType(), pre.getValue(), pre.getCreatedBy());
    Long existingId = jdbc.queryForObject("SELECT id FROM hp_whitelist WHERE type=? AND value=?",
        Long.class, "WEBHOOK_HOST", "concurrent.example.com");
    Long returnedId = svc.saveWhitelist("WEBHOOK_HOST", "concurrent.example.com", "admin");
    assertThat(returnedId).isEqualTo(existingId);
  }
  @Test void listIncludesDisabledChannels() {
    Long enabledId = svc.save("启用渠道", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=en1\"}", 20, 300, false, "admin");
    Long disabledId = svc.save("停用渠道", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ds1\"}", 20, 300, false, "admin");
    svc.setStatus(disabledId, false);
    List<ChannelVO> list = svc.list();
    assertThat(list).hasSize(2);
    ChannelVO enabledVo = list.stream().filter(v -> v.id().equals(enabledId)).findFirst().orElseThrow();
    ChannelVO disabledVo = list.stream().filter(v -> v.id().equals(disabledId)).findFirst().orElseThrow();
    assertThat(enabledVo.status()).isEqualTo("ENABLED");
    assertThat(disabledVo.status()).isEqualTo("DISABLED");
  }

  @Test void updateWithBlankWebhookKeepsOld() {
    // 1. 新建渠道
    Long id = svc.save("留空测试群", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ORIGINAL\"}",
        20, 300, false, "admin");
    assertThat(svc.getDecryptedWebhook(id)).contains("ORIGINAL");

    // 2. update 传空 webhook，应保留旧值，且限流等其他字段正常更新
    svc.update(id, "留空测试群-改名", "WEWORK_BOT",
        "{\"webhook\":\"\"}",
        99, 250, true, "admin");
    assertThat(svc.getDecryptedWebhook(id)).contains("ORIGINAL");

    ChannelVO vo = svc.list().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    assertThat(vo.name()).isEqualTo("留空测试群-改名");
    assertThat(vo.rateLimitPerMin()).isEqualTo(99);
    assertThat(vo.waitTimeoutSec()).isEqualTo(250);
    assertThat(vo.testFlag()).isTrue();

    // 3. 显式传新 webhook（白名单内）时正常更新
    svc.update(id, "留空测试群-改名", "WEWORK_BOT",
        "{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=CHANGED\"}",
        99, 250, true, "admin");
    assertThat(svc.getDecryptedWebhook(id)).contains("CHANGED");
  }
}
