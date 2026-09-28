package com.hermes.push.storage;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class StorageMapperTest extends AbstractIntegrationTest {
    @Autowired StorageMapper mapper;

    @Test
    void insertAndSelect() {
        Storage s = new Storage();
        s.setStorageKey("t1");
        s.setDisplayName("测试");
        s.setStorageType("LOCAL");
        s.setEnabled(1);
        s.setCreatedBy("test");
        mapper.insert(s);
        Storage got = mapper.selectById(s.getId());
        assertEquals("t1", got.getStorageKey());
        assertEquals("LOCAL", got.getStorageType());
    }
}