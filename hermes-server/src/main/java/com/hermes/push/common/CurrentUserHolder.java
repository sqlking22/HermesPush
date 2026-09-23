package com.hermes.push.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前用户静态门面。M1 阶段取请求头 X-User，缺失则返回 "admin"。
 * Task 20 接入 Sa-Token 时替换实现。
 */
public final class CurrentUserHolder {
  private CurrentUserHolder() {}

  public static String get() {
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) return "admin";
    HttpServletRequest req = attrs.getRequest();
    String user = req.getHeader("X-User");
    return (user == null || user.isBlank()) ? "admin" : user;
  }
}
