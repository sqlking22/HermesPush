package com.hermes.push.channel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.exec.ExecPushRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class WecomBotChannelTest extends AbstractIntegrationTest {
  static WireMockServer wm;
  @Autowired WecomBotChannel channel; @Autowired WhitelistService whitelist;
  @Autowired ExecPushRepository pushRepo; @Autowired JdbcTemplate jdbc;
  @Autowired RateLimiterFactory rateLimiter;

  @BeforeAll static void startWm() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
  @AfterAll static void stopWm() { wm.stop(); }
  @BeforeEach void reset() { wm.resetAll(); jdbc.update("DELETE FROM hp_whitelist WHERE value='localhost'");
    jdbc.update("INSERT INTO hp_whitelist(type,value,created_by) VALUES('WEBHOOK_HOST','localhost','test')");
    rateLimiter.evict(url()); }

  String url() { return "http://localhost:" + wm.port() + "/cgi-bin/webhook/send?key=test"; }
  PushMessage md() { return new PushMessage("markdown", "**日报** 内容", List.of()); }

  @Test void successPath() {
    wm.stubFor(post(urlPathEqualTo("/cgi-bin/webhook/send")).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    PushResult r = channel.send(url(), md(), 20, 5);
    assertThat(r.success()).isTrue();
    wm.verify(postRequestedFor(urlPathEqualTo("/cgi-bin/webhook/send"))
        .withRequestBody(matchingJsonPath("$.msgtype", equalTo("markdown"))));
  }
  @Test void invalidWebhook_notRetryable() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}")));
    PushResult r = channel.send(url(), md(), 20, 5);
    assertThat(r.success()).isFalse(); assertThat(r.retryable()).isFalse(); assertThat(r.errorCode()).isEqualTo("PUSH-012");
  }
  @Test void rateLimitedByWecom_retryable() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":45009,\"errmsg\":\"api freq limit\"}")));
    PushResult r = channel.send(url(), md(), 20, 5);
    assertThat(r.success()).isFalse(); assertThat(r.retryable()).isTrue(); assertThat(r.errorCode()).isEqualTo("PUSH-011");
  }
  @Test void serverError_retryable() {
    wm.stubFor(post(anyUrl()).willReturn(serverError()));
    assertThat(channel.send(url(), md(), 20, 5).retryable()).isTrue();
  }
  @Test void whitelistRejectsForeignHost() {
    assertThatThrownBy(() -> whitelist.assertWebhookAllowed("https://evil.example.com/hook"))
        .isInstanceOf(com.hermes.push.common.BizException.class).hasMessageContaining("白名单");
  }
  @Test void localRateLimiterWaitsThenFails() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    assertThat(channel.send(url(), md(), 2, 1).success()).isTrue();
    assertThat(channel.send(url(), md(), 2, 1).success()).isTrue();
    PushResult third = channel.send(url(), md(), 2, 1); // 限流 2/min，等待 1s 超时
    assertThat(third.success()).isFalse(); assertThat(third.errorCode()).isEqualTo("PUSH-011");
  }
  @Test void idempotentSkipOnSecondSuccess() {
    wm.stubFor(post(anyUrl()).willReturn(okJson("{\"errcode\":0,\"errmsg\":\"ok\"}")));
    pushRepo.markSuccess(9001L, 1L, "a1", "markdown");
    assertThat(pushRepo.existsSuccess(9001L, 1L, "a1", "markdown")).isTrue();
    assertThat(pushRepo.existsSuccess(9001L, 1L, "a1", "text")).isFalse(); // 消息类型维度独立
  }
  @Test void unknownException_notRetryable_pushes012() {
    PushResult r = channel.classifySendFailure(new RuntimeException("oops"));
    assertThat(r.success()).isFalse();
    assertThat(r.retryable()).isFalse();
    assertThat(r.errorCode()).isEqualTo("PUSH-012");
    assertThat(r.errorMsg()).contains("未预期的推送异常").contains("RuntimeException");
  }
}
