package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestPlannerTest {

    private static final IngestPlanner.KnownAsset ASSET =
            new IngestPlanner.KnownAsset("/root/a.mp4", "md5-a", 1L, 11L);

    @Test
    void emptyInputsProduceNoActions() {
        assertTrue(IngestPlanner.plan(List.of(), List.of(), Map.of()).isEmpty());
    }

    @Test
    void unknownFilesArePlannedAsCreated() {
        var actions = IngestPlanner.plan(
                List.of(file("/root/new.mp4", "md5-1")), List.of(), Map.of());
        assertEquals(1, actions.size());
        assertEquals(IngestPlanner.ActionType.CREATED, actions.get(0).type());
    }

    @Test
    void samePathAndHashIsUnchanged() {
        var actions = IngestPlanner.plan(
                List.of(file("/root/a.mp4", "md5-a")), List.of(ASSET), Map.of());
        assertEquals(IngestPlanner.ActionType.UNCHANGED, actions.get(0).type());
    }

    @Test
    void samePathWithDifferentHashIsChanged() {
        var actions = IngestPlanner.plan(
                List.of(file("/root/a.mp4", "md5-new")), List.of(ASSET), Map.of());
        assertEquals(IngestPlanner.ActionType.CHANGED, actions.get(0).type());
        assertEquals(1L, actions.get(0).sourceId());
    }

    @Test
    void sameContentAtNewPathIsMovedWhenOldPathIsGone() {
        var actions = IngestPlanner.plan(
                List.of(file("/root/b.mp4", "md5-a")), List.of(ASSET), Map.of());
        assertEquals(1, actions.size());
        assertEquals(IngestPlanner.ActionType.MOVED, actions.get(0).type());
        assertEquals("/root/b.mp4", actions.get(0).path());
        assertEquals(1L, actions.get(0).sourceId());
    }

    @Test
    void disappearedKnownPathIsDeleted() {
        var actions = IngestPlanner.plan(List.of(), List.of(ASSET), Map.of());
        assertEquals(1, actions.size());
        assertEquals(IngestPlanner.ActionType.DELETED, actions.get(0).type());
    }

    @Test
    void contentCopiedToTwoNewPathsCreatesTwiceAndNeverDoubleMoves() {
        var actions = IngestPlanner.plan(
                List.of(file("/root/p1.mp4", "md5-x"), file("/root/p2.mp4", "md5-x")),
                List.of(ASSET), Map.of());
        assertEquals(3, actions.size());
        assertEquals(2, actions.stream()
                .filter(action -> action.type() == IngestPlanner.ActionType.CREATED).count());
        assertEquals(1, actions.stream()
                .filter(action -> action.type() == IngestPlanner.ActionType.DELETED).count());
    }

    @Test
    void unreadableFilesAreReportedAsErrors() {
        var actions = IngestPlanner.plan(
                List.of(), List.of(), Map.of("/root/broken.mp4", "无法读取文件"));
        assertEquals(1, actions.size());
        assertEquals(IngestPlanner.ActionType.ERROR, actions.get(0).type());
    }

    @Test
    void rootBoundaryCheckAllowsRootItselfAndChildrenOnly() {
        var root = Paths.get("/data/videos").toAbsolutePath().normalize();
        assertTrue(IngestPlanner.isUnderRoot(root, root));
        assertTrue(IngestPlanner.isUnderRoot(root, root.resolve("java/a.mp4")));
        assertTrue(!IngestPlanner.isUnderRoot(root, Paths.get("/data/videos-evil")));
    }

    private static IngestPlanner.DiscoveredFile file(String path, String md5) {
        return new IngestPlanner.DiscoveredFile(path, md5);
    }
}
