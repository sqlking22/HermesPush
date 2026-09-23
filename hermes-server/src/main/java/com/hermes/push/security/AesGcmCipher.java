package com.hermes.push.security;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/** 密文格式: base64( keyId(1B)=1 + nonce(12B) + ciphertext+tag )。格式 M1 冻结，keyId 为轮换预留。 */
@Component
public class AesGcmCipher {
  private static final byte KEY_ID = 1;
  private static final int NONCE_LEN = 12, TAG_BITS = 128;
  private final MasterKeyProvider keys;
  private final SecureRandom random = new SecureRandom();

  public AesGcmCipher(MasterKeyProvider keys) { this.keys = keys; }

  public String encrypt(String plain) {
    try {
      byte[] nonce = new byte[NONCE_LEN];
      random.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, keys.requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
      byte[] body = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      ByteBuffer buf = ByteBuffer.allocate(1 + NONCE_LEN + body.length);
      buf.put(KEY_ID).put(nonce).put(body);
      return Base64.getEncoder().encodeToString(buf.array());
    } catch (Exception e) { throw new BizException(ErrorCode.SYS_001, e); }
  }

  public String decrypt(String cipherText) {
    try {
      byte[] raw = Base64.getDecoder().decode(cipherText);
      if (raw.length < 1 + NONCE_LEN + 16 || raw[0] != KEY_ID) throw new IllegalArgumentException("bad format");
      ByteBuffer buf = ByteBuffer.wrap(raw);
      buf.get(); // keyId
      byte[] nonce = new byte[NONCE_LEN]; buf.get(nonce);
      byte[] body = new byte[buf.remaining()]; buf.get(body);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, keys.requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
      return new String(c.doFinal(body), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) { throw new BizException(ErrorCode.SYS_001, e); }
  }
}
