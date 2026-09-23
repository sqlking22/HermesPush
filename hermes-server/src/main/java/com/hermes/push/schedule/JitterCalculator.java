package com.hermes.push.schedule;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;

@Component
public class JitterCalculator {

  public int offsetSeconds(long taskId, LocalDate day) {
    String input = taskId + ":" + day;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      int x = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16)
          | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);
      return Math.floorMod(x, 300);
    } catch (Exception e) {
      throw new RuntimeException("sha256 failed", e);
    }
  }
}
