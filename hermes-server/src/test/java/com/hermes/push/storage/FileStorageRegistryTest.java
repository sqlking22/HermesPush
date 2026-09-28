package com.hermes.push.storage;

import com.hermes.push.AbstractIntegrationTest;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = "hermes.storage.local-root=target/test-storage")
class FileStorageRegistryTest extends AbstractIntegrationTest {
    @Autowired FileStorageRegistry registry;
    @Autowired StorageConfigService svc;

    @Test
    void resolvesLocalAndRoundtrips() throws Exception {
        svc.save("local-dev", "本地", "LOCAL", null, null, null, null, null, null, true, "admin");
        FileStorage fs = registry.resolveByKey("local-dev");
        assertEquals(StorageType.LOCAL, fs.type());
        String uri = fs.put("t.txt", new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 3);
        assertEquals("hp://local-dev/t.txt", uri);
        assertEquals("abc", new String(fs.get(uri).readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void unknownKeyThrowsSto001() {
        BizException e = assertThrows(BizException.class, () -> registry.resolveByKey("no-such-key"));
        assertEquals(ErrorCode.STO_001, e.getErrorCode());
    }
}
