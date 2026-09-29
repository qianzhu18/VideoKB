package com.example.server.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits uploaded Markdown/plain-text scripts into paragraph-level knowledge units.
 * Pure function over the text: blank-line blocks become paragraphs, Markdown
 * headings attach to the paragraph they introduce, and oversized paragraphs are
 * hard-split on sentence boundaries so every unit is a bounded searchable semantic
 * chunk.
 */
@Service
public class ScriptChunkingService {

    static final int MAX_PARAGRAPH_CHARS = 1500;
    /** A cut must keep at least this much text, otherwise the sentence scan is pointless. */
    private static final int MIN_TAIL_CHARS = 40;

    /** Ordered paragraphs of the document; never empty for non-blank input. */
    public List<String> paragraphs(String content) {
        List<String> blocks = new ArrayList<>();
        for (String block : content.split("\\n\\s*\\n")) {
            String normalized = block.strip().replaceAll("[ \\t]+", " ");
            if (!normalized.isBlank()) blocks.add(normalized);
        }
        List<String> withHeadings = attachHeadings(blocks);
        List<String> bounded = new ArrayList<>(withHeadings.size());
        for (String paragraph : withHeadings) {
            bounded.addAll(splitLong(paragraph));
        }
        return bounded;
    }

    /** A heading alone ("# 标题", "## 小节") carries no standalone evidence — merge it forward. */
    private static List<String> attachHeadings(List<String> blocks) {
        List<String> out = new ArrayList<>(blocks.size());
        StringBuilder carry = new StringBuilder();
        for (String block : blocks) {
            if (block.startsWith("#")) {
                carry.append(block).append('\n');
            } else if (carry.isEmpty()) {
                out.add(block);
            } else {
                out.add(carry + block);
                carry.setLength(0);
            }
        }
        if (!carry.isEmpty()) out.add(carry.toString().strip());
        return out;
    }

    /** Sentence-boundary split for oversized paragraphs, fallback to a hard cut. */
    private static List<String> splitLong(String paragraph) {
        if (paragraph.length() <= MAX_PARAGRAPH_CHARS) return List.of(paragraph);
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int limit = Math.min(start + MAX_PARAGRAPH_CHARS, paragraph.length());
            int cut = limit >= paragraph.length() ? limit : sentenceBoundary(paragraph, start, limit);
            parts.add(paragraph.substring(start, cut).strip());
            start = cut;
        }
        parts.removeIf(String::isBlank);
        return parts;
    }

    private static int sentenceBoundary(String text, int start, int limit) {
        for (int i = limit - 1; i > start + MIN_TAIL_CHARS; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return limit;
    }
}
