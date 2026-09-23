package com.hermes.push.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Component
public class MasterKeyProvider {
  private final SecretKeySpec key;
  public MasterKeyProvider(@Value("${hermes.master-key:}") String b64) {
    if (b64 == null || b64.isBlank()) {
      throw new IllegalStateException("缺少主密钥：请设置环境变量 HP_MASTER_KEY（base64 的 32 字节），参见部署手册");
    }
    byte[] raw;
    try { raw = Base64.getDecoder().decode(b64); }
    catch (IllegalArgumentException e) { throw new IllegalStateException("HP_MASTER_KEY 不是合法 base64", e); }
    if (raw.length != 32) throw new IllegalStateException("HP_MASTER_KEY 必须是 base64 的 32 字节");
    this.key = new SecretKeySpec(raw, "AES");
  }
  public SecretKeySpec requireKey() { return key; }
}
