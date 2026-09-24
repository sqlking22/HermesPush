package com.hermes.push.common;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class SpringContextHolder implements ApplicationContextAware {
  private static ApplicationContext context;

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    context = applicationContext;
  }

  public static <T> T getBean(Class<T> clazz) {
    if (context == null) return null;
    try {
      return context.getBean(clazz);
    } catch (BeansException e) {
      return null;
    }
  }
}
