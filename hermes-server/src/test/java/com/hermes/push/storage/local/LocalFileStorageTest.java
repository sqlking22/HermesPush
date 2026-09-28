package com.hermes.push.storage.local;

import com.hermes.push.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageTest {
    @TempDir Path tmp;

    private FileStorage storage() {
        return new LocalFileStorage("local", tmp.resolve("root"), "prefix");
    }

    @Test
    void roundtrip() throws Exception {
        FileStorage fs = storage();
        String uri = fs.put("artifacts/2026/09/28/1/1/a.txt", stream("hello"), 5);
        assertEquals("hp://local/artifacts/2026/09/28/1/1/a.txt", uri);
        assertTrue(fs.exists(uri));
        assertEquals("hello", new String(fs.get(uri).readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(fs.delete(uri));
        assertFalse(fs.exists(uri));
    }

    @Test
    void presignedUrlEmpty() {
        assertEquals(Optional.empty(), storage().presignedUrl("hp://local/a", Duration.ofMinutes(5)));
    }

    @Test
    void writesUnderPrefix() throws Exception {
        String uri = storage().put("d.txt", stream("x"), 1);
        assertTrue(Files.exists(tmp.resolve("root/prefix/d.txt")));
    }

    private InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
