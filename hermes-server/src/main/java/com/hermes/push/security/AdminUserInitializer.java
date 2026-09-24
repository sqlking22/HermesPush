package com.hermes.push.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {
  private final UserMapper userMapper;
  private final Environment env;

  @Override
  public void run(ApplicationArguments args) {
    Long count = userMapper.selectCount(new QueryWrapper<>());
    if (count != null && count > 0) return;

    String password = env.getProperty("HP_ADMIN_PASSWORD", "hermes@2026");
    if ("hermes@2026".equals(password)) {
      log.warn("HP_ADMIN_PASSWORD 未设置，使用默认密码 hermes@2026，请立即修改！");
    }
    String hash = new BCryptPasswordEncoder().encode(password);

    User admin = new User();
    admin.setUsername("admin");
    admin.setPasswordHash(hash);
    admin.setRole("ADMIN");
    admin.setStatus("ACTIVE");
    userMapper.insert(admin);
    log.info("已初始化管理员账户 admin");
  }
}
