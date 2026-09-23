package com.hermes.push.security;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class AesGcmCipherTest {
  // base64 of 32 bytes 0x00..0x1f
  static final String KEY_B64;
  static {
    byte[] key = new byte[32];
    for (int i = 0; i < 32; i++) key[i] = (byte) i;
    KEY_B64 = Base64.getEncoder().encodeToString(key);
  }
  AesGcmCipher cipher() { return new AesGcmCipher(new MasterKeyProvider(KEY_B64)); }

  @Test void roundTrip() {
    String c = cipher().encrypt("p@ssw0rd-中文");
    assertThat(cipher().decrypt(c)).isEqualTo("p@ssw0rd-中文");
  }
  @Test void nonceRandomized_eachCiphertextDiffers() {
    assertThat(cipher().encrypt("same")).isNotEqualTo(cipher().encrypt("same"));
  }
  @Test void ciphertextLayout_keyIdNonceBody() {
    byte[] raw = Base64.getDecoder().decode(cipher().encrypt("x"));
    assertThat(raw[0]).isEqualTo((byte) 1);          // keyId=1（M1 固定）
    assertThat(raw.length).isGreaterThan(1 + 12 + 16); // keyId+nonce+tag 至少存在
  }
  @Test void wrongKey_throwsSys001() {
    String c = cipher().encrypt("secret");
    String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
    AesGcmCipher other = new AesGcmCipher(new MasterKeyProvider(otherKey));
    assertThatThrownBy(() -> other.decrypt(c)).isInstanceOf(BizException.class)
        .hasMessageContaining("SYS-001");
  }
  @Test void missingMasterKey_failsFast() {
    assertThatThrownBy(() -> new MasterKeyProvider(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HP_MASTER_KEY");
  }
}
