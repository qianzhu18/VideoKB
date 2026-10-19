package com.example.server.service;

import com.example.server.entity.KnowledgeSegment;
import java.util.*;

/** Deterministic sentence/topic-boundary chunks, with complete original-window mapping. */
public class EvidenceBlockChunker {
    public static final String PROFILE = "raw-boundary-v1-max1400-overlap1";
    private static final int MAX_CODEPOINTS = 1400;
    public record Block(String text, List<KnowledgeSegment> evidence) {}

    public List<Block> split(List<KnowledgeSegment> originals) {
        var result = new ArrayList<Block>();
        var group = new ArrayList<KnowledgeSegment>();
        for (var raw : originals) {
            String text = text(raw);
            if (text.isBlank()) continue;
            if (text.codePointCount(0, text.length()) > MAX_CODEPOINTS) {
                flush(result, group); group.clear();
                for (String part : sentences(text)) result.add(new Block(part, List.of(raw)));
                continue;
            }
            if (!group.isEmpty()) {
                var previous = group.getLast();
                String joined = group.stream().map(EvidenceBlockChunker::text).reduce("", (a,b) -> a + "\n" + b) + "\n" + text;
                boolean topicBoundary = text.substring(0, Math.min(80,text.length())).matches("(?s).*(接下来|下面我们|再来看|换一个问题).*")
                        || similarity(text(previous), text) < 0.06;
                boolean capacity = joined.codePointCount(0, joined.length()) > MAX_CODEPOINTS || group.size() >= 3;
                if (raw.getStartMs() < previous.getStartMs()) throw new IllegalArgumentException("原始证据时间必须有序");
                if (raw.getStartMs() - previous.getEndMs() > 15000 || topicBoundary || capacity) {
                    flush(result, group);
                    // Only overlap across a size boundary; never bridge a topic or time gap.
                    boolean overlap = capacity && !topicBoundary && raw.getStartMs() <= previous.getEndMs() + 15000
                            && (text(previous) + text).codePointCount(0, (text(previous) + text).length()) + 1 <= MAX_CODEPOINTS;
                    group.clear(); if (overlap) group.add(previous);
                }
            }
            group.add(raw);
        }
        flush(result, group);
        return List.copyOf(result);
    }
    private void flush(List<Block> out, List<KnowledgeSegment> group) {
        if (!group.isEmpty()) out.add(new Block(String.join("\n", group.stream().map(EvidenceBlockChunker::text).toList()), List.copyOf(group)));
    }
    private List<String> sentences(String text) {
        var parts = new ArrayList<String>();
        while (!text.isBlank()) {
            int end = text.offsetByCodePoints(0, Math.min(MAX_CODEPOINTS, text.codePointCount(0,text.length())));
            if (end < text.length()) {
                int boundary = -1;
                for (int i = end - 1; i > end / 2; i--) if ("。！？；\n.!?;".indexOf(text.charAt(i)) >= 0) { boundary = i + 1; break; }
                if (boundary > 0) end = boundary;
            }
            parts.add(text.substring(0,end)); text = text.substring(end);
        }
        return parts;
    }
    static String text(KnowledgeSegment raw) {
        return (Objects.toString(raw.getTranscript(), "") + "\n" + Objects.toString(raw.getOcrText(), "")).strip();
    }
    private double similarity(String a, String b) {
        Set<String> left = grams(a), right = grams(b);
        int common = 0; for (String term : left) if (right.contains(term)) common++;
        return common / Math.max(1.0, Math.sqrt((double)left.size() * right.size()));
    }
    private Set<String> grams(String text) {
        text = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        var grams = new HashSet<String>(); for (int i=0;i+1<text.length();i++) grams.add(text.substring(i,i+2));
        return grams;
    }
}
