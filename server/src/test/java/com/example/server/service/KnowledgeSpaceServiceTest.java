package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.KnowledgeSpaceCreateRequest;
import com.example.server.dto.KnowledgeSpaceUpdateRequest;
import com.example.server.dto.KnowledgeSpaceView;
import com.example.server.entity.KnowledgeSpace;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeSpaceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSpaceServiceTest {

    @Test
    void createsUserSpaceAfterEnsuringSystemDefault() {
        KnowledgeSpaceMapper mapper = mock(KnowledgeSpaceMapper.class);
        KnowledgeSpace defaultSpace = space(1L, 7L, KnowledgeSpaceService.DEFAULT_SPACE_NAME, true);
        AtomicLong ids = new AtomicLong(2L);
        when(mapper.selectOne(any())).thenReturn(defaultSpace);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(KnowledgeSpace.class))).thenAnswer(invocation -> {
            KnowledgeSpace candidate = invocation.getArgument(0);
            candidate.setId(ids.getAndIncrement());
            return 1;
        });

        KnowledgeSpaceService service = new KnowledgeSpaceService(mapper, mock(KnowledgeAuditService.class));
        KnowledgeSpaceView created = service.create(7L,
                new KnowledgeSpaceCreateRequest("  Java 课程  ", " JVM / 并发 "));

        assertEquals(2L, created.id());
        assertEquals("Java 课程", created.name());
        assertEquals("JVM / 并发", created.description());
        assertEquals(false, created.systemDefault());
    }

    @Test
    void rejectsDuplicateSpaceNameForSameOwner() {
        KnowledgeSpaceMapper mapper = mock(KnowledgeSpaceMapper.class);
        when(mapper.selectOne(any())).thenReturn(space(1L, 7L, KnowledgeSpaceService.DEFAULT_SPACE_NAME, true));
        when(mapper.selectCount(any())).thenReturn(1L);

        KnowledgeSpaceService service = new KnowledgeSpaceService(mapper, mock(KnowledgeAuditService.class));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(7L, new KnowledgeSpaceCreateRequest("Java", "")));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
    }

    @Test
    void refusesSpaceUpdateFromAnotherOwner() {
        KnowledgeSpaceMapper mapper = mock(KnowledgeSpaceMapper.class);
        when(mapper.selectById(5L)).thenReturn(space(5L, 8L, "Java", false));

        KnowledgeSpaceService service = new KnowledgeSpaceService(mapper, mock(KnowledgeAuditService.class));
        assertThrows(SecurityException.class,
                () -> service.update(7L, 5L, new KnowledgeSpaceUpdateRequest("Python", null)));
    }

    @Test
    void keepsSystemDefaultNameStable() {
        KnowledgeSpaceMapper mapper = mock(KnowledgeSpaceMapper.class);
        when(mapper.selectById(1L)).thenReturn(space(1L, 7L, KnowledgeSpaceService.DEFAULT_SPACE_NAME, true));

        KnowledgeSpaceService service = new KnowledgeSpaceService(mapper, mock(KnowledgeAuditService.class));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(7L, 1L, new KnowledgeSpaceUpdateRequest("学习", null)));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    private static KnowledgeSpace space(Long id, Long ownerId, String name, boolean systemDefault) {
        KnowledgeSpace space = new KnowledgeSpace();
        space.setId(id);
        space.setOwnerUserId(ownerId);
        space.setName(name);
        space.setDescription("");
        space.setSystemDefault(systemDefault);
        return space;
    }
}
