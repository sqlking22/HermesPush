package com.hermes.push.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.hermes.push.security.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前用户静态门面。
 *
 * 分支语义（纵深防御，防止拦截器漏覆盖时匿名拿到 admin 身份）：
 *   1. 非 Web 上下文 → 返回 "system"（后台线程兜底，审计用）
 *   2. Web 上下文 + auth 开启 → 必须已登录，未登录抛 NotLoginException（走 401 handler）
 *   3. Web 上下文 + auth 关闭 → X-User 头或 "admin"（兼容集成测试）
 */
public final class CurrentUserHolder {
  private CurrentUserHolder() {}

  public static String get() {
    // 分支 1：非 Web 上下文
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) return "system";

    // 读 auth 开关（缓存到 Environment，不查库）
    boolean authEnabled = isAuthEnabled();

    if (authEnabled) {
      // 分支 2：auth 开启 → 必须已登录，未登录直接抛
      try {
        if (!StpUtil.isLogin()) {
          throw new NotLoginException("未登录", NotLoginException.NOT_TOKEN, null);
        }
        Long userId = StpUtil.getLoginIdAsLong();
        String username = lookupUsername(userId);
        if (username != null) return username;
        // 用户查不到（已删）：登出并抛未登录
        StpUtil.logout();
        throw new NotLoginException("用户不存在", NotLoginException.TOKEN_TIMEOUT, null);
      } catch (NotLoginException e) {
        throw e;
      } catch (Exception e) {
        // Sa-Token 上下文异常（非 Web 已在上层判断，这里兜底）
        throw new NotLoginException("未登录", NotLoginException.NOT_TOKEN, null);
      }
    } else {
      // 分支 3：auth 关闭 → X-User 头或 "admin"
      HttpServletRequest req = attrs.getRequest();
      String user = req.getHeader("X-User");
      return (user == null || user.isBlank()) ? "admin" : user;
    }
  }

  private static boolean isAuthEnabled() {
    Environment env = SpringContextHolder.getBean(Environment.class);
    if (env == null) return false;
    return Boolean.TRUE.equals(env.getProperty("hermes.auth.enabled", Boolean.class, false));
  }

  private static String lookupUsername(Long userId) {
    UserMapper mapper = SpringContextHolder.getBean(UserMapper.class);
    if (mapper == null) return null;
    var user = mapper.selectById(userId);
    return user != null ? user.getUsername() : null;
  }
}
