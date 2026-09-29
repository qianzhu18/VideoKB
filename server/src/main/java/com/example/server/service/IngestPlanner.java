package com.example.server.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pure diff planner for local-directory ingest. Identity is content, not path: a file is
 * matched against previously ingested assets by MD5 first (move), then by recorded path
 * (unchanged / changed). Producing the plan here, without any I/O, keeps the
 * create/changed/moved/deleted semantics unit-testable.
 */
public final class IngestPlanner {

    public enum ActionType {
        CREATED, CHANGED, MOVED, DELETED, UNCHANGED, ERROR
    }

    public record DiscoveredFile(String path, String md5) {
    }

    /** Previously ingested asset state under the same root. */
    public record KnownAsset(String path, String md5, Long sourceId, Long mediaId) {
    }

    public record Action(ActionType type, String path, String md5,
                         Long sourceId, Long mediaId, String message) {
    }

    private IngestPlanner() {
    }

    /**
     * @param discovered video files currently on disk under the root
     * @param known      ingested assets whose recorded path is under the same root
     * @param hashErrors files that exist but could not be hashed (unreadable etc.)
     */
    public static List<Action> plan(List<DiscoveredFile> discovered,
                                    List<KnownAsset> known,
                                    Map<String, String> hashErrors) {
        Map<String, KnownAsset> knownByPath = known.stream()
                .filter(asset -> asset.path() != null)
                .collect(Collectors.toMap(KnownAsset::path, asset -> asset,
                        (left, right) -> left));

        List<Action> actions = new java.util.ArrayList<>();
        java.util.Set<String> claimedAssets = new java.util.HashSet<>();
        for (DiscoveredFile file : discovered) {
            KnownAsset samePath = knownByPath.remove(file.path());
            if (samePath != null && Objects.equals(samePath.md5(), file.md5())) {
                actions.add(new Action(ActionType.UNCHANGED, file.path(), file.md5(),
                        samePath.sourceId(), samePath.mediaId(), null));
                claimedAssets.add(samePath.path());
                continue;
            }
            if (samePath != null) {
                actions.add(new Action(ActionType.CHANGED, file.path(), file.md5(),
                        samePath.sourceId(), samePath.mediaId(), null));
                claimedAssets.add(samePath.path());
                continue;
            }
            KnownAsset moved = known.stream()
                    .filter(asset -> Objects.equals(asset.md5(), file.md5()))
                    .filter(asset -> !file.path().equals(asset.path()))
                    .filter(asset -> !discoveredContainsPath(discovered, asset.path()))
                    .filter(asset -> claimedAssets.add(asset.path()))
                    .findFirst()
                    .orElse(null);
            if (moved != null) {
                actions.add(new Action(ActionType.MOVED, file.path(), file.md5(),
                        moved.sourceId(), moved.mediaId(), null));
                knownByPath.remove(moved.path());
                continue;
            }
            actions.add(new Action(ActionType.CREATED, file.path(), file.md5(), null, null, null));
        }

        // Whatever remains known under this root has disappeared from disk.
        for (KnownAsset missing : knownByPath.values()) {
            actions.add(new Action(ActionType.DELETED, missing.path(), missing.md5(),
                    missing.sourceId(), missing.mediaId(), null));
        }
        hashErrors.forEach((path, message) ->
                actions.add(new Action(ActionType.ERROR, path, null, null, null, message)));
        return actions;
    }

    private static boolean discoveredContainsPath(List<DiscoveredFile> discovered, String path) {
        return discovered.stream().anyMatch(file -> file.path().equals(path));
    }

    /** True when {@code candidate} equals {@code root} or lives underneath it. */
    public static boolean isUnderRoot(Path root, Path candidate) {
        return candidate.startsWith(root);
    }
}
