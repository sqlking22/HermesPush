package com.hermes.push.storage;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogicalUriTest {
    @Test
    void buildAndParseRoundTrip() {
        LogicalUri u = LogicalUri.of("oss-main", "artifacts/2026/09/28/1/2/a.xlsx");
        assertEquals("hp://oss-main/artifacts/2026/09/28/1/2/a.xlsx", u.toString());
        LogicalUri p = LogicalUri.parse(u.toString());
        assertEquals("oss-main", p.storageKey());
        assertEquals("artifacts/2026/09/28/1/2/a.xlsx", p.path());
    }

    @Test
    void rejectsTraversal() {
        assertThrows(BizException.class, () -> LogicalUri.parse("hp://oss-main/../etc/passwd"));
        assertThrows(BizException.class, () -> LogicalUri.of("oss-main", "a/../../b"));
    }

    @Test
    void rejectsAbsoluteAndBackslash() {
        assertThrows(BizException.class, () -> LogicalUri.parse("hp://oss-main//etc"));
        assertThrows(BizException.class, () -> LogicalUri.of("oss-main", "C:\\a\\b"));
    }

    @Test
    void rejectsBadStorageKey() {
        assertThrows(BizException.class, () -> LogicalUri.parse("hp:///path"));
        assertThrows(BizException.class, () -> LogicalUri.of("bad key!", "a"));
    }

    @Test
    void errorCodeIsSto003() {
        BizException e = assertThrows(BizException.class, () -> LogicalUri.parse("hp://k/.."));
        assertEquals(ErrorCode.STO_003, e.getErrorCode());
    }
}
