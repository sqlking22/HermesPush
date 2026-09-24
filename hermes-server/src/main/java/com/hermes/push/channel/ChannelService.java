package com.hermes.push.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.datasource.TestResultVO;
import com.hermes.push.security.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 渠道管理服务。
 *
 * <ul>
 *   <li>save: 新建/更新渠道，配置加密存储，白名单校验，限流缓存 evict</li>
 *   <li>getDecrypted: 返回解密后渠道配置（服务端内部使用）</li>
 *   <li>healthCheck: 调用对应 PushChannel 发送健康检查消息</li>
 *   <li>deleteWhitelist: 白名单删除，被启用渠道引用的 host 不可删</li>
 * </ul>
 */
@Service
public class ChannelService {
  private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

  private static final Set<String> ALLOWED_TYPES = Set.of("WEWORK_BOT");
  private static final Set<String> ALLOWED_WL_TYPES = Set.of("WEBHOOK_HOST", "EMAIL_DOMAIN", "EMAIL_ADDRESS");

  private final ChannelMapper mapper;
  private final WhitelistMapper whitelistMapper;
  private final AesGcmCipher cipher;
  private final WhitelistService whitelistService;
  private final RateLimiterFactory rateLimiter;
  private final PushChannelRegistry registry;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbc;

  public ChannelService(ChannelMapper mapper, WhitelistMapper whitelistMapper,
      AesGcmCipher cipher, WhitelistService whitelistService,
      RateLimiterFactory rateLimiter, PushChannelRegistry registry,
      ObjectMapper objectMapper, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.whitelistMapper = whitelistMapper;
    this.cipher = cipher;
    this.whitelistService = whitelistService;
    this.rateLimiter = rateLimiter;
    this.registry = registry;
    this.objectMapper = objectMapper;
    this.jdbc = jdbc;
  }

  /**
   * 新建渠道。name 已存在（未删除）抛 SYS_002 "渠道名已存在"。
   * M1 阶段 type 仅允许 WEWORK_BOT；configJson 必须含 webhook 字段。
   *
   * @return 新建渠道 ID
   */
  @Transactional
  public Long save(String name, String type, String configJson, int rateLimitPerMin,
      int waitTimeoutSec, boolean testFlag, String operator) {
    // 1. 校验 type
    validateType(type);
    // 2. 解析 configJson 取 webhook
    String webhook = extractWebhook(configJson);
    // 3. 白名单校验
    whitelistService.assertWebhookAllowed(webhook);
    // 4. 加密 config
    String configCipher = cipher.encrypt(configJson);
    // 5. 校验 name 唯一（未删除渠道）
    Channel duplicate = mapper.selectOne(new QueryWrapper<Channel>()
        .eq("name", name).eq("deleted", 0));
    if (duplicate != null) {
      throw new BizException(ErrorCode.SYS_002, "渠道名已存在: " + name);
    }
    // 6. 新建
    Channel ch = new Channel();
    ch.setName(name);
    ch.setType(type);
    ch.setConfigCipher(configCipher);
    ch.setRateLimitPerMin(rateLimitPerMin);
    ch.setQueueWaitTimeoutSec(waitTimeoutSec);
    ch.setTestFlag(testFlag ? 1 : 0);
    ch.setStatus("ENABLED");
    ch.setDeleted(0);
    ch.setCreatedBy(operator);
    mapper.insert(ch);
    return ch.getId();
  }

  /**
   * 按 ID 更新渠道。不存在或已删除抛 SYS_003；name 冲突抛 SYS_002。
   * configJson 中 webhook 为空/缺失时，保留旧 webhook（与密码字段"留空=不修改"模式对称）。
   */
  @Transactional
  public void update(Long id, String name, String type, String configJson,
      int rateLimitPerMin, int waitTimeoutSec, boolean testFlag, String operator) {
    // 1. 按 id 查询（已删除视为不存在）
    Channel ch = requireActiveChannel(id);
    // 2. 校验 type
    validateType(type);
    // 3. 解析 configJson 取 webhook；空/缺失时保留旧 webhook（合并进新 configJson）
    String newWebhook;
    String effectiveConfigJson;
    try {
      JsonNode root = objectMapper.readTree(configJson);
      JsonNode webhookNode = root.path("webhook");
      if (webhookNode.isMissingNode() || webhookNode.asText().isBlank()) {
        // webhook 留空 → 合并旧 webhook，其余字段用新值
        String oldWebhook = decryptWebhook(ch.getConfigCipher());
        var node = objectMapper.createObjectNode();
        // 先复制新 configJson 全部字段（排除空 webhook）
        if (root.isObject()) {
          root.fields().forEachRemaining(e -> {
            if (!"webhook".equals(e.getKey())) node.set(e.getKey(), e.getValue());
          });
        }
        if (oldWebhook != null) node.put("webhook", oldWebhook);
        effectiveConfigJson = objectMapper.writeValueAsString(node);
        newWebhook = oldWebhook;
      } else {
        effectiveConfigJson = configJson;
        newWebhook = webhookNode.asText();
      }
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(ErrorCode.SYS_006, "configJson 解析失败: " + e.getMessage());
    }
    String oldWebhook = decryptWebhook(ch.getConfigCipher());
    // 4. webhook 变更时重新白名单校验 + evict 新旧缓存；未变则跳过
    boolean webhookChanged = (oldWebhook == null) ? (newWebhook != null) : !oldWebhook.equals(newWebhook);
    if (webhookChanged) {
      whitelistService.assertWebhookAllowed(newWebhook);
      if (oldWebhook != null) rateLimiter.evict(oldWebhook);
      rateLimiter.evict(newWebhook);
    }
    // 5. name 变更时校验唯一
    if (name != null && !name.equals(ch.getName())) {
      Channel duplicate = mapper.selectOne(new QueryWrapper<Channel>()
          .eq("name", name).eq("deleted", 0));
      if (duplicate != null) {
        throw new BizException(ErrorCode.SYS_002, "渠道名已存在: " + name);
      }
      ch.setName(name);
    }
    // 6. 更新字段
    ch.setType(type);
    ch.setConfigCipher(cipher.encrypt(effectiveConfigJson));
    ch.setRateLimitPerMin(rateLimitPerMin);
    ch.setQueueWaitTimeoutSec(waitTimeoutSec);
    ch.setTestFlag(testFlag ? 1 : 0);
    mapper.updateById(ch);
  }

  /**
   * 根据 ID 获取解密后的渠道配置（服务端内部使用，Task 17 推送用）。
   * 渠道已删除/停用抛 SYS_003 / SYS_002。
   */
  public DecryptedChannel getDecrypted(Long channelId) {
    Channel ch = requireChannel(channelId);
    if (ch.getDeleted() != null && ch.getDeleted() == 1) {
      throw new BizException(ErrorCode.SYS_003, "渠道已删除: " + channelId);
    }
    if (!"ENABLED".equals(ch.getStatus())) {
      throw new BizException(ErrorCode.SYS_002, "渠道已停用: " + channelId);
    }
    String webhook = decryptWebhook(ch.getConfigCipher());
    int rateLimit = ch.getRateLimitPerMin() == null ? 20 : ch.getRateLimitPerMin();
    int waitTimeout = ch.getQueueWaitTimeoutSec() == null ? 300 : ch.getQueueWaitTimeoutSec();
    return new DecryptedChannel(ch.getType(), webhook, rateLimit, waitTimeout);
  }

  /**
   * 便捷方法：仅获取解密后的 webhook（测试与内部使用）。
   */
  public String getDecryptedWebhook(Long channelId) {
    return getDecrypted(channelId).webhook();
  }

  /**
   * 列出所有未删除渠道（含停用，管理界面需看到停用渠道以执行启用操作）。
   */
  public List<ChannelVO> list() {
    List<Channel> list = mapper.selectList(new QueryWrapper<Channel>()
        .eq("deleted", 0)
        .orderByDesc("id"));
    return list.stream().map(this::toVO).toList();
  }

  /**
   * 获取单个渠道 VO（脱敏）。
   */
  public ChannelVO get(Long id) {
    Channel ch = requireChannel(id);
    if (ch.getDeleted() != null && ch.getDeleted() == 1) {
      throw new BizException(ErrorCode.SYS_003, "渠道已删除: " + id);
    }
    return toVO(ch);
  }

  /**
   * 启用/停用渠道。
   */
  public void setStatus(Long id, boolean enable) {
    Channel ch = requireChannel(id);
    ch.setStatus(enable ? "ENABLED" : "DISABLED");
    mapper.updateById(ch);
    if (!enable) {
      // 停用时 evict 限流缓存
      String webhook = decryptWebhook(ch.getConfigCipher());
      if (webhook != null) rateLimiter.evict(webhook);
    }
  }

  /**
   * 逻辑删除渠道。
   */
  public void delete(Long id) {
    Channel ch = requireChannel(id);
    ch.setDeleted(1);
    mapper.updateById(ch);
    String webhook = decryptWebhook(ch.getConfigCipher());
    if (webhook != null) rateLimiter.evict(webhook);
  }

  /**
   * 健康检查：向渠道发送 "[HermesPush] 健康检查 {时间戳}" text 消息。
   */
  public TestResultVO healthCheck(Long channelId) {
    long start = System.currentTimeMillis();
    DecryptedChannel dc = getDecrypted(channelId);
    PushChannel pushChannel = registry.get(dc.type());

    String timestamp = jdbc.queryForObject("SELECT NOW(3)", String.class);
    PushMessage msg = new PushMessage("text",
        "[HermesPush] 健康检查 " + timestamp,
        List.of());

    PushResult result = pushChannel.send(dc.webhook(), msg, dc.rateLimit(), dc.waitTimeout());
    long costMs = System.currentTimeMillis() - start;

    if (result.success()) {
      return new TestResultVO(true, null, costMs, null, null);
    }
    return new TestResultVO(false, null, costMs,
        result.errorCode(), result.errorMsg());
  }

  // ---------- 白名单管理 ----------

  public List<Whitelist> listWhitelist(String type) {
    QueryWrapper<Whitelist> qw = new QueryWrapper<>();
    if (type != null && !type.isBlank()) qw.eq("type", type);
    qw.orderByDesc("id");
    return whitelistMapper.selectList(qw);
  }

  @Transactional
  public Long saveWhitelist(String type, String value, String operator) {
    if (!ALLOWED_WL_TYPES.contains(type)) {
      throw new BizException(ErrorCode.SYS_006, "不支持的白名单类型: " + type);
    }
    if (value == null || value.isBlank()) {
      throw new BizException(ErrorCode.SYS_006, "白名单值不能为空");
    }
    // 幂等：已存在则返回既有 id
    Whitelist existing = whitelistMapper.selectOne(
        new QueryWrapper<Whitelist>().eq("type", type).eq("value", value));
    if (existing != null) return existing.getId();

    try {
      Whitelist wl = new Whitelist();
      wl.setType(type);
      wl.setValue(value);
      wl.setCreatedBy(operator);
      whitelistMapper.insert(wl);
      return wl.getId();
    } catch (DuplicateKeyException e) {
      // 并发插入兜底：唯一键冲突则重新查询返回既有 id
      Whitelist dup = whitelistMapper.selectOne(
          new QueryWrapper<Whitelist>().eq("type", type).eq("value", value));
      if (dup != null) return dup.getId();
      throw e; // 异常情况重抛
    }
  }

  /**
   * 删除白名单。删除前遍历所有启用未删除渠道，解密 webhook 比对 host。
   * 被引用的 host 不可删除，抛 SYS_002 detail 含"引用"。
   */
  @Transactional
  public void deleteWhitelist(Long id) {
    Whitelist wl = whitelistMapper.selectById(id);
    if (wl == null) {
      throw new BizException(ErrorCode.SYS_003, "白名单不存在: " + id);
    }
    if (!"WEBHOOK_HOST".equals(wl.getType())) {
      // 非 WEBHOOK_HOST 类型，直接删
      whitelistMapper.deleteById(id);
      return;
    }

    String host = wl.getValue();
    // 遍历全部启用未删除渠道，解密 webhook 比对 host
    List<Channel> channels = mapper.selectList(new QueryWrapper<Channel>()
        .eq("deleted", 0)
        .eq("status", "ENABLED"));

    for (Channel ch : channels) {
      String webhook = decryptWebhook(ch.getConfigCipher());
      if (webhook == null) continue;
      String chHost = extractHost(webhook);
      if (host.equals(chHost)) {
        throw new BizException(ErrorCode.SYS_002,
            "白名单被渠道引用，无法删除: 渠道[" + ch.getName() + "] 使用了该 host");
      }
    }

    whitelistMapper.deleteById(id);
  }

  // ---------- 私有方法 ----------

  private void validateType(String type) {
    if (!ALLOWED_TYPES.contains(type)) {
      throw new BizException(ErrorCode.SYS_002, "渠道类型未开放: " + type);
    }
  }

  private Channel requireChannel(Long id) {
    Channel ch = mapper.selectById(id);
    if (ch == null) throw new BizException(ErrorCode.SYS_003, "渠道不存在: " + id);
    return ch;
  }

  /** require 未删除的渠道（删除=不存在） */
  private Channel requireActiveChannel(Long id) {
    Channel ch = requireChannel(id);
    if (ch.getDeleted() != null && ch.getDeleted() == 1) {
      throw new BizException(ErrorCode.SYS_003, "渠道不存在: " + id);
    }
    return ch;
  }

  private String extractWebhook(String configJson) {
    try {
      JsonNode root = objectMapper.readTree(configJson);
      JsonNode webhookNode = root.path("webhook");
      if (webhookNode.isMissingNode() || webhookNode.asText().isBlank()) {
        throw new BizException(ErrorCode.SYS_006, "缺少 webhook");
      }
      return webhookNode.asText();
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(ErrorCode.SYS_006, "configJson 解析失败: " + e.getMessage());
    }
  }

  private String decryptWebhook(String configCipher) {
    if (configCipher == null || configCipher.isBlank()) return null;
    try {
      String configJson = cipher.decrypt(configCipher);
      return extractWebhook(configJson);
    } catch (Exception e) {
      log.warn("渠道配置解密失败", e);
      return null;
    }
  }

  private String extractHost(String url) {
    try {
      URI uri = URI.create(url);
      return uri.getHost();
    } catch (Exception e) {
      return null;
    }
  }

  private ChannelVO toVO(Channel ch) {
    String webhook = decryptWebhook(ch.getConfigCipher());
    String masked = ChannelVO.maskWebhook(webhook);
    int rateLimit = ch.getRateLimitPerMin() == null ? 20 : ch.getRateLimitPerMin();
    int waitTimeout = ch.getQueueWaitTimeoutSec() == null ? 300 : ch.getQueueWaitTimeoutSec();
    boolean testFlg = ch.getTestFlag() != null && ch.getTestFlag() == 1;
    return new ChannelVO(
        ch.getId(),
        ch.getName(),
        ch.getType(),
        masked,
        rateLimit,
        waitTimeout,
        testFlg,
        ch.getStatus()
    );
  }
}
