package com.andrewstsai.instashare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    @InjectMocks private S3Service s3Service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(s3Service, "bucketName", "test-bucket");
    }

    @Test
    void uploadFile_callsPutObjectWithCorrectMetadata() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[100]);

        s3Service.uploadFile(file, "sess1");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo("test-bucket");
        assertThat(req.contentType()).isEqualTo("image/png");
        assertThat(req.contentLength()).isEqualTo(100L);
    }

    @Test
    void uploadFile_returnsKeyWithCorrectFormat() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[10]);

        String key = s3Service.uploadFile(file, "sess1");

        assertThat(key).startsWith("sessions/sess1/");
        assertThat(key).endsWith("_photo.png");
    }

    @Test
    void uploadFile_sanitizesFilenameSpecialChars() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "my file (2).png", "image/png", new byte[10]);

        String key = s3Service.uploadFile(file, "sess1");

        assertThat(key).endsWith("_my_file__2_.png");
    }

    @Test
    void uploadFile_whenInputStreamThrows_propagatesIOException() {
        MockMultipartFile brokenFile = new MockMultipartFile("file", "x.txt", "text/plain", new byte[0]) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThatThrownBy(() -> s3Service.uploadFile(brokenFile, "sess1"))
                .isInstanceOf(IOException.class)
                .hasMessage("disk error");
    }

    @Test
    void generatePresignedUrl_returnsUrlString() throws Exception {
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(URI.create("https://bucket.s3.amazonaws.com/key").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        String url = s3Service.generatePresignedUrl("sessions/sess1/file.txt");

        assertThat(url).isEqualTo("https://bucket.s3.amazonaws.com/key");
    }

    @Test
    void deleteFile_callsDeleteObjectWithCorrectBucketAndKey() {
        s3Service.deleteFile("sessions/sess1/file.txt");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("sessions/sess1/file.txt");
    }
}
