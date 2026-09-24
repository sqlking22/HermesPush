package com.hermes.push.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestPropertySource(properties = "hermes.auth.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthControllerTest extends AbstractIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper om;
  @Autowired AdminUserInitializer initializer;

  @org.junit.jupiter.api.BeforeEach
  void seedAdmin() {
    try { initializer.run(null); } catch (Exception e) { throw new RuntimeException(e); }
  }

  @Test
  void unauthenticatedRejected401() throws Exception {
    mvc.perform(get("/api/datasources"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.errorCode").value("AUTH-002"));
  }

  @Test
  void loginSuccessAndTokenWorks() throws Exception {
    // 1. login with admin/hermes@2026 (AdminUserInitializer 已建)
    MvcResult loginResult = mvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"hermes@2026\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.token").exists())
        .andExpect(jsonPath("$.data.username").value("admin"))
        .andExpect(jsonPath("$.data.role").value("ADMIN"))
        .andReturn();

    String token = om.readTree(loginResult.getResponse().getContentAsString())
        .path("data").path("token").asText();

    // 2. 带 token 访问 /api/auth/me
    mvc.perform(get("/api/auth/me").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.username").value("admin"))
        .andExpect(jsonPath("$.data.role").value("ADMIN"));
  }

  @Test
  void wrongPasswordRejected401() throws Exception {
    // 错误密码
    MvcResult wrongPwd = mvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.errorCode").value("AUTH-001"))
        .andReturn();

    // 不存在用户
    MvcResult noUser = mvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.errorCode").value("AUTH-001"))
        .andReturn();

    // 防枚举：两者响应体完全一致
    JsonNode pwdBody = om.readTree(wrongPwd.getResponse().getContentAsString());
    JsonNode noBody = om.readTree(noUser.getResponse().getContentAsString());
    assertEquals(pwdBody.path("code").asInt(), noBody.path("code").asInt());
    assertEquals(pwdBody.path("message").asText(), noBody.path("message").asText());
    assertEquals(pwdBody.path("data").path("errorCode").asText(),
        noBody.path("data").path("errorCode").asText());
    assertEquals(pwdBody.path("data").path("detail").asText(),
        noBody.path("data").path("detail").asText());
  }
}
