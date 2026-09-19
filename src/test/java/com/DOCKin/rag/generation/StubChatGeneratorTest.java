package com.DOCKin.rag.generation;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubChatGeneratorTest {

    private final StubChatGenerator stub = new StubChatGenerator();
    private final ChatDomain.Request any = new ChatDomain.Request(List.of(), "ko", "t");

    @Test
    @DisplayName("근거가 있으면 스텁임을 밝힌 뒤 번호·출처·유사도·앞 80자를 그대로 돌려준다 - 답을 지어내지 않는다")
    void 근거_있음() {
        String longText = "가".repeat(200);
        RetrievalResult found = new RetrievalResult(RetrievalResult.RetrievalMode.VECTOR, List.of(
                new RetrievedChunk(11L, SourceType.WORK_LOG, 1L, 0, "용접 와이어가\n간헐적으로 멈춤", 0.8312),
                new RetrievedChunk(12L, SourceType.SAFETY_COURSE, 3L, 0, longText, 0.5)));

        String reply = stub.generate(any, found, "u").reply();
        String[] lines = reply.split("\n");

        assertEquals(StubChatGenerator.HEADER, lines[0]);
        assertEquals("근거 2건 (retrieval_mode=VECTOR)", lines[1]);
        assertEquals("1. WORK_LOG #1 (score 0.831) 용접 와이어가 간헐적으로 멈춤", lines[2]);
        assertTrue(lines[3].startsWith("2. SAFETY_COURSE #3 (score 0.500) " + "가".repeat(80) + "…"));
        assertEquals(4, lines.length);
    }

    @Test
    @DisplayName("근거가 없으면 모드를 밝히고 '권한 안에 없거나 검색 실패'라고 답한다")
    void 근거_없음() {
        String reply = stub.generate(any, RetrievalResult.none(), "u").reply();
        assertTrue(reply.startsWith(StubChatGenerator.HEADER));
        assertTrue(reply.contains("retrieval_mode=NONE"));
        assertTrue(reply.contains("권한 안에"));
    }
}
