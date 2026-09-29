package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paragraph derivation contract: bounded, non-trivial units in document order.
 */
class ScriptChunkingServiceTest {

    private final ScriptChunkingService service = new ScriptChunkingService();

    @Test
    void splitsOnBlankLinesAndDropsNoise() {
        List<String> paragraphs = service.paragraphs("第一段内容。\n\n\n   \n第二段内容。");
        assertEquals(List.of("第一段内容。", "第二段内容。"), paragraphs);
    }

    @Test
    void mergesTinyBlocksIntoNeighbours() {
        // 标题单独成块太短，必须并入下一段，否则向量语义密度不足。
        List<String> paragraphs = service.paragraphs("# 标题\n\n正文正文正文正文正文正文正文正文正文正文。");
        assertEquals(1, paragraphs.size());
        assertTrue(paragraphs.get(0).contains("# 标题"));
        assertTrue(paragraphs.get(0).contains("正文"));
    }

    @Test
    void hardSplitsOversizedParagraphsAtSentenceBoundary() {
        String sentence = "这是一个用于测试的完整句子，长度合适。";
        String huge = sentence.repeat(120); // ~3.6k chars > MAX_PARAGRAPH_CHARS
        List<String> paragraphs = service.paragraphs(huge);
        assertTrue(paragraphs.size() >= 2);
        for (String paragraph : paragraphs) {
            assertTrue(paragraph.length() <= ScriptChunkingService.MAX_PARAGRAPH_CHARS);
            assertTrue(paragraph.endsWith("。"), "split must land on a sentence boundary");
        }
        assertEquals(huge.strip(), String.join("", paragraphs), "split must not lose text");
    }

    @Test
    void blankInputYieldsNoParagraphs() {
        assertTrue(service.paragraphs("  \n\n \n").isEmpty());
    }
}
