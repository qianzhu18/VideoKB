package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeCollectionCreateRequest;
import com.example.server.dto.KnowledgeCollectionView;
import com.example.server.entity.KnowledgeCollection;
import com.example.server.entity.KnowledgeSpace;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeCollectionMapper;
import com.example.server.mapper.KnowledgeSourceMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeCollectionServiceTest {

    @Test
    void createsDirectoryWithStablePath() {
        KnowledgeCollectionMapper collectionMapper = mock(KnowledgeCollectionMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        AtomicLong ids = new AtomicLong(11L);
        when(spaceService.requireOwnedSpace(7L, 3L)).thenReturn(space(3L, 7L));
        when(collectionMapper.selectCount(any())).thenReturn(0L);
        when(collectionMapper.insert(any(KnowledgeCollection.class))).thenAnswer(invocation -> {
            KnowledgeCollection collection = invocation.getArgument(0);
            collection.setId(ids.getAndIncrement());
            return 1;
        });

        KnowledgeCollectionService service = new KnowledgeCollectionService(
                collectionMapper, sourceMapper, spaceService, mock(KnowledgeAuditService.class));
        KnowledgeCollectionView view = service.create(7L, 3L,
                new KnowledgeCollectionCreateRequest(null, " Java ", null));

        assertEquals(11L, view.id());
        assertEquals("Java", view.name());
        assertEquals("/Java", view.path());
    }

    @Test
    void rejectsParentFromAnotherSpace() {
        KnowledgeCollectionMapper collectionMapper = mock(KnowledgeCollectionMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        KnowledgeSpaceService spaceService = mock(KnowledgeSpaceService.class);
        when(spaceService.requireOwnedSpace(7L, 3L)).thenReturn(space(3L, 7L));
        KnowledgeCollection foreignParent = new KnowledgeCollection();
        foreignParent.setId(9L);
        foreignParent.setSpaceId(4L);
        foreignParent.setPath("/Other");
        when(collectionMapper.selectById(eq(9L))).thenReturn(foreignParent);

        KnowledgeCollectionService service = new KnowledgeCollectionService(
                collectionMapper, sourceMapper, spaceService, mock(KnowledgeAuditService.class));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(7L, 3L, new KnowledgeCollectionCreateRequest(9L, "JVM", 0)));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    private static KnowledgeSpace space(Long id, Long ownerId) {
        KnowledgeSpace space = new KnowledgeSpace();
        space.setId(id);
        space.setOwnerUserId(ownerId);
        return space;
    }
}
