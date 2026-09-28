package com.hermes.push.storage;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class StorageConfigServiceTest extends AbstractIntegrationTest {
    @Autowired StorageConfigService svc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void saveEncryptsKeysAndRoundtrips() {
        Long id = svc.save("s3-main", "生产S3", "S3", "http://minio.internal:9000",
            "cn", "hermes", "prefix/", "AKIA", "SECRET", true, "admin");

        String stored = jdbc.queryForObject(
            "SELECT access_key_enc FROM hp_storage WHERE id=?", String.class, id);
        assertFalse(stored.contains("AKIA"), "accessKey 不应落明文");

        DecryptedStorage d = svc.requireDecrypted("s3-main");
        assertEquals("AKIA", d.accessKey());
        assertEquals("SECRET", d.secretKey());
        assertEquals(StorageType.S3, d.type());
        assertEquals("hermes", d.bucket());
    }

    @Test
    void duplicateKeyRejected() {
        svc.save("dup", "a", "LOCAL", null, null, null, null, null, null, true, "admin");
        BizException e = assertThrows(BizException.class,
            () -> svc.save("dup", "b", "LOCAL", null, null, null, null, null, null, true, "admin"));
        assertEquals(ErrorCode.STO_004, e.getErrorCode());
    }

    @Test
    void voDoesNotLeakKeys() {
        svc.save("s3-vo", "v", "S3", "e", "r", "b", "p", "AK", "SK", true, "admin");
        var vo = svc.get(svc.list().stream()
            .filter(v -> "s3-vo".equals(v.storageKey())).findFirst().orElseThrow().id());
        assertEquals("s3-vo", vo.storageKey());
    }
}
