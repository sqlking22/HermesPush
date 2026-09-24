package com.hermes.push.common;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前用户静态门面。
 * 优先从 Sa-Token 会话取 loginId，反查 username；
 * 任何异常（非 Web 上下文、未登录、拦截器未启用等）均回落 X-User 头或 "admin"。
 */
public final class CurrentUserHolder {
  private CurrentUserHolder() {}

  public static String get() {
    try {
      if (StpUtil.isLogin()) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
          return lookupUsername(Long.valueOf(loginId.toString()));
        }
      }
    } catch (Exception ignored) {
      // 任何异常（非 Web 上下文、未登录、拦截器未启用等）均回落
    }
    return fallback();
  }

  private static String lookupUsername(Long userId) {
    try {
      com.hermes.push.security.UserMapper mapper =
          SpringContextHolder.getBean(com.hermes.push.security.UserMapper.class);
      if (mapper == null) return "admin";
      com.hermes.push.security.User user = mapper.selectById(userId);
      return user != null ? user.getUsername() : "admin";
    } catch (Exception e) {
      return "admin";
    }
  }

  private static String fallback() {
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) return "admin";
    HttpServletRequest req = attrs.getRequest();
    String user = req.getHeader("X-User");
    return (user == null || user.isBlank()) ? "admin" : user;
  }
}
