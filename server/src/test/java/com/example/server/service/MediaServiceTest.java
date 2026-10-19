package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Upload-layer content deduplication: the same user uploading the same MD5
 *  reuses the existing media; a Redis outage degrades the dedup instead of
 *  blocking the upload. */
class MediaServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long EXISTING_ID = 9L;
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String FILE_URL = "http://minio:9000/media/new-upload.mp4";

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock dedupLock = mock(RLock.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final KnowledgeSourceService knowledgeSourceService = mock(KnowledgeSourceService.class);

    private MediaService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redissonClient.getLock(anyString())).thenReturn(dedupLock);
        when(dedupLock.isHeldByCurrentThread()).thenReturn(true);
        service = new MediaService(mediaFileMapper, redisTemplate, redissonClient, minioUtils,
                new ObjectMapper(), mock(AgentCheckpointService.class), mock(AgentTelemetry.class),
                mock(QdrantVectorStore.class), mock(VideoContextService.class),
                knowledgeSourceService);
    }

    private MediaFile existing() {
        MediaFile existing = new MediaFile();
        existing.setId(EXISTING_ID);
        existing.setUserId(USER_ID);
        existing.setFilename("旧上传.mp4");
        existing.setContentHash(HASH);
        return existing;
    }

    @Test
    void duplicateUploadReturnsExistingMediaAndRemovesNewObject() {
        when(mediaFileMapper.selectOne(any())).thenReturn(existing());

        MediaFile result = service.saveUploadedMedia("重复上传.mp4", FILE_URL, USER_ID, HASH);

        assertEquals(EXISTING_ID, result.getId());
        verify(minioUtils).removeFile(FILE_URL);
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
        verify(knowledgeSourceService, never()).ensureMediaSource(any());
    }

    @Test
    void firstUploadInsertsRowRegistersSourceAndCachesHash() {
        when(mediaFileMapper.selectOne(any())).thenReturn(null);
        // Simulate the MyBatis-Plus auto-increment id write-back, otherwise the
        // hash cache write would be skipped.
        when(mediaFileMapper.insert(any(MediaFile.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, MediaFile.class).setId(123L);
            return 1;
        });

        MediaFile result = service.saveUploadedMedia("新上传.mp4", FILE_URL, USER_ID, HASH);

        ArgumentCaptor<MediaFile> inserted = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper).insert(inserted.capture());
        assertEquals(HASH, inserted.getValue().getContentHash());
        assertEquals(USER_ID, inserted.getValue().getUserId());
        verify(knowledgeSourceService).ensureMediaSource(result);
        // The hash cache now carries a TTL instead of relying on deletion cleanup.
        verify(valueOps).set(eq("media:md5:123"), eq(HASH), eq(Duration.ofDays(7)));
    }

    @Test
    void missingHashSkipsDedupAndInserts() {
        MediaFile result = service.saveUploadedMedia("无哈希.mp4", FILE_URL, USER_ID, null);

        verify(mediaFileMapper, never()).selectOne(any());
        assertNull(result.getContentHash());
    }

    @Test
    void dedupLockOutageDegradesToUnlockedQuery() throws Exception {
        when(dedupLock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("redis down"));
        when(mediaFileMapper.selectOne(any())).thenReturn(null);

        MediaFile result = service.saveUploadedMedia("锁故障.mp4", FILE_URL, USER_ID, HASH);

        // fail-open: with Redis unavailable the dedup proceeds without the lock
        // instead of rejecting the upload.
        verify(mediaFileMapper).insert(any(MediaFile.class));
        assertEquals("锁故障.mp4", result.getFilename());
    }

    @Test
    void insertFailureCleansUpUploadedObject() {
        when(mediaFileMapper.selectOne(any())).thenReturn(null);
        when(mediaFileMapper.insert(any(MediaFile.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class,
                () -> service.saveUploadedMedia("入库失败.mp4", FILE_URL, USER_ID, HASH));
        verify(minioUtils).removeFile(FILE_URL);
    }

    @Test
    void sameContentDifferentUserIsNotDeduplicated() {
        // The duplicate query is scoped by (user_id, content_hash): content is
        // never merged across users, so one user's upload stays invisible to another.
        when(mediaFileMapper.selectOne(any())).thenReturn(null);

        service.saveUploadedMedia("他人同内容.mp4", FILE_URL, OTHER_USER_ID, HASH);

        verify(mediaFileMapper).insert(any(MediaFile.class));
    }
}
