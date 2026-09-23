package com.hermes.push.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hermes.push.common.ErrorCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * 企微机器人推送渠道实现。
 *
 * <p>send 执行顺序（顺序冻结）：
 * <ol>
 *   <li>whitelist.assertWebhookAllowed（发送时实时校验）</li>
 *   <li>rateLimiter.acquire（false → PUSH-011 retryable）</li>
 *   <li>组 JSON（msgtype text/markdown；text 且 mentionedList 非空才带 mentioned_list 字段）</li>
 *   <li>POST → 解析 errcode 按 WecomErrCodes 分类</li>
 * </ol>
 */
@Component
public class WecomBotChannel implements PushChannel {

  private static final String TYPE = "WEWORK_BOT";
  private static final int TIMEOUT_MS = 10_000;

  private final WhitelistService whitelist;
  private final RateLimiterFactory rateLimiter;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public WecomBotChannel(WhitelistService whitelist, RateLimiterFactory rateLimiter,
      ObjectMapper objectMapper) {
    this.whitelist = whitelist;
    this.rateLimiter = rateLimiter;
    this.objectMapper = objectMapper;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(TIMEOUT_MS);
    factory.setReadTimeout(TIMEOUT_MS);
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public PushResult send(String webhookUrl, PushMessage msg, int rateLimitPerMin,
      int queueWaitTimeoutSec) {
    // Step 1: 白名单校验（实时）
    whitelist.assertWebhookAllowed(webhookUrl);

    // Step 2: 限流
    if (!rateLimiter.acquire(webhookUrl, rateLimitPerMin, queueWaitTimeoutSec)) {
      return new PushResult(false, true, ErrorCode.PUSH_011.getCode(),
          ErrorCode.PUSH_011.getUserMessage());
    }

    // Step 3: 组 JSON
    String body = buildBody(msg);

    // Step 4: POST + 解析分类
    try {
      String response = restClient.post()
          .uri(webhookUrl)
          .header("Content-Type", "application/json")
          .body(body)
          .retrieve()
          .body(String.class);

      return parseResponse(response);
    } catch (RestClientResponseException e) {
      // HTTP 错误状态码（如 5xx）
      int status = e.getStatusCode().value();
      if (status >= 500) {
        return new PushResult(false, true, ErrorCode.PUSH_011.getCode(),
            "HTTP " + status + " " + e.getStatusText());
      }
      // 4xx 类错误统一归为 PUSH-012
      return new PushResult(false, false, ErrorCode.PUSH_012.getCode(),
          "HTTP " + status + " " + e.getStatusText());
    } catch (ResourceAccessException e) {
      // 网络异常 / 连接超时 / 读取超时
      return new PushResult(false, true, ErrorCode.PUSH_011.getCode(),
          "网络异常或超时: " + e.getMessage());
    } catch (Exception e) {
      return new PushResult(false, true, ErrorCode.PUSH_011.getCode(),
          "推送异常: " + e.getMessage());
    }
  }

  private String buildBody(PushMessage msg) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("msgtype", msg.msgType());

    ObjectNode contentNode = objectMapper.createObjectNode();
    if ("text".equals(msg.msgType())) {
      contentNode.put("content", msg.content());
      List<String> mentioned = msg.mentionedList();
      if (mentioned != null && !mentioned.isEmpty()) {
        contentNode.set("mentioned_list", objectMapper.valueToTree(mentioned));
      }
      root.set("text", contentNode);
    } else if ("markdown".equals(msg.msgType())) {
      contentNode.put("content", msg.content());
      root.set("markdown", contentNode);
    } else {
      // 未知类型，按内容字段透传
      contentNode.put("content", msg.content());
      root.set(msg.msgType(), contentNode);
    }
    return root.toString();
  }

  private PushResult parseResponse(String response) {
    try {
      JsonNode node = objectMapper.readTree(response);
      int errcode = node.path("errcode").asInt(-1);
      String errmsg = node.path("errmsg").asText("");

      if (WecomErrCodes.isSuccess(errcode)) {
        return new PushResult(true, false, null, null);
      }
      if (WecomErrCodes.isRetryable(errcode)) {
        return new PushResult(false, true, ErrorCode.PUSH_011.getCode(),
            "errcode=" + errcode + " errmsg=" + errmsg);
      }
      // 其余非零 errcode 统一不可重试
      return new PushResult(false, false, ErrorCode.PUSH_012.getCode(),
          "errcode=" + errcode + " errmsg=" + errmsg);
    } catch (Exception e) {
      return new PushResult(false, false, ErrorCode.PUSH_012.getCode(),
          "响应解析失败: " + e.getMessage());
    }
  }
}
