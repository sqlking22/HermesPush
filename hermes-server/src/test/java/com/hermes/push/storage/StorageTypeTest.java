package com.hermes.push.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageTypeTest {
    @Test
    void valuesCoverLocalAndS3() {
        assertEquals(2, StorageType.values().length);
        assertSame(StorageType.LOCAL, StorageType.valueOf("LOCAL"));
        assertSame(StorageType.S3, StorageType.valueOf("S3"));
    }
}
