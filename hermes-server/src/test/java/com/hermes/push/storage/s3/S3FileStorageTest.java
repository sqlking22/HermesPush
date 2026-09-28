package com.hermes.push.storage.s3;

import com.hermes.push.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3FileStorageTest {
    private final S3Client client = mock(S3Client.class);

    private FileStorage storage() {
        return new S3FileStorage("s3-main", client, "hermes", "prefix/");
    }

    @Test
    void putUsesBucketAndPrefixedKey() {
        String uri = storage().put("artifacts/a.xlsx", new ByteArrayInputStream(new byte[]{1}), 1);
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(req.capture(), any(RequestBody.class));
        assertEquals("hermes", req.getValue().bucket());
        assertEquals("prefix/artifacts/a.xlsx", req.getValue().key());
        assertEquals("hp://s3-main/artifacts/a.xlsx", uri);
    }

    @Test
    void getReturnsStream() throws Exception {
        GetObjectResponse resp = GetObjectResponse.builder().build();
        when(client.getObject(any(GetObjectRequest.class)))
            .thenReturn(new ResponseInputStream<>(resp, new ByteArrayInputStream("hi".getBytes())));
        byte[] got = storage().get("hp://s3-main/x").readAllBytes();
        assertEquals("hi", new String(got));
    }

    @Test
    void existsFalseOnS3Exception() {
        when(client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(404).build());
        assertFalse(storage().exists("hp://s3-main/missing"));
    }

    @Test
    void deleteCallsDeleteObject() {
        assertTrue(storage().delete("hp://s3-main/x"));
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void presignedUrlNotYetImplemented() {
        assertEquals(Optional.empty(), storage().presignedUrl("hp://s3-main/x", java.time.Duration.ofMinutes(5)));
    }
}
