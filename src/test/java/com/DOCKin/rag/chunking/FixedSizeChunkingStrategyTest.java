package com.DOCKin.rag.chunking;

import com.DOCKin.rag.model.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedSizeChunkingStrategyTest {

    private final FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy();

    @Test
    @DisplayName("빈 입력이면 청크를 만들지 않는다")
    void 빈_입력() {
        assertTrue(strategy.split(null).isEmpty());
        assertTrue(strategy.split("").isEmpty());
        assertTrue(strategy.split("   ").isEmpty());
    }

    @Test
    @DisplayName("목표 길이보다 짧으면 자르지 않고 한 덩어리로 둔다")
    void 짧은_텍스트는_분할하지_않는다() {
        String text = "크레인 하부 통행 금지 구역에서는 신호수의 수신호를 확인한다.";

        List<String> chunks = strategy.split(text);

        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    @DisplayName("긴 텍스트는 여러 청크로 나뉘고, 각 청크는 모델 입력 상한 안에 들어간다")
    void 긴_텍스트_분할() {
        // 500자를 넘기는 텍스트. 문장 종결부호를 넣어 경계 탐색도 함께 태운다.
        String sentence = "용접 작업 중 환기 상태를 점검하고 보호구 착용 여부를 확인하였다. ";
        String text = sentence.repeat(40);

        List<String> chunks = strategy.split(text);

        assertTrue(chunks.size() > 1, "500자를 넘으면 분할되어야 한다");
        for (String chunk : chunks) {
            assertFalse(chunk.isBlank());
            // 문장 경계를 찾으면 목표보다 짧아질 수 있으므로 상한만 검증한다.
            assertTrue(chunk.length() <= FixedSizeChunkingStrategy.TARGET_CHARS,
                    "청크가 목표 길이를 넘었다: " + chunk.length());
        }
    }

    @Test
    @DisplayName("분할해도 원문의 내용이 유실되지 않는다")
    void 내용_유실_없음() {
        String text = ("안전벨트 착용을 확인한다. ".repeat(30)) + "마지막문장은반드시남아야한다";

        List<String> chunks = strategy.split(text);

        assertTrue(chunks.get(chunks.size() - 1).contains("마지막문장은반드시남아야한다"),
                "마지막 내용이 잘려 사라지면 검색에서 조용히 누락된다");
    }

    @Test
    @DisplayName("인접한 청크는 겹치는 부분이 있어 경계에서 문맥이 끊기지 않는다")
    void 오버랩() {
        String text = "가나다라마바사아자차카타파하".repeat(60); // 840자, 종결부호 없음 → 강제 절단 경로

        List<String> chunks = strategy.split(text);

        assertTrue(chunks.size() >= 2);
        String first = chunks.get(0);
        String second = chunks.get(1);
        String tail = first.substring(first.length() - FixedSizeChunkingStrategy.OVERLAP_CHARS);
        assertTrue(second.startsWith(tail), "앞 청크의 끝이 다음 청크의 앞에 겹쳐야 한다");
    }

    @Test
    @DisplayName("현재 정의된 모든 소스 타입을 처리할 수 있다")
    void 지원_소스_타입() {
        for (SourceType type : SourceType.values()) {
            assertTrue(strategy.supports(type), "전략이 없는 소스 타입이 있으면 인덱싱이 실패한다: " + type);
        }
    }
}
