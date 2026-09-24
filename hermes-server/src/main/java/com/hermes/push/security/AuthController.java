package com.hermes.push.security;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
  private final UserMapper userMapper;

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody LoginRequest req) {
    User user = userMapper.selectOne(new QueryWrapper<User>()
        .eq("username", req.username())
        .eq("status", "ACTIVE"));

    if (user == null || !new BCryptPasswordEncoder().matches(req.password(), user.getPasswordHash())) {
      return ResponseEntity.status(401)
          .body(ApiResponse.fail(ErrorCode.AUTH_001, "用户名或密码错误"));
    }

    StpUtil.login(user.getId());
    return ResponseEntity.ok(ApiResponse.ok(Map.of(
        "token", StpUtil.getTokenValue(),
        "username", user.getUsername(),
        "role", user.getRole()
    )));
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout() {
    StpUtil.logout();
    return ApiResponse.ok(null);
  }

  @GetMapping("/me")
  public ApiResponse<Map<String, Object>> me() {
    Long userId = StpUtil.getLoginIdAsLong();
    User user = userMapper.selectById(userId);
    return ApiResponse.ok(Map.of(
        "username", user.getUsername(),
        "role", user.getRole()
    ));
  }

  public record LoginRequest(String username, String password) {}
}
