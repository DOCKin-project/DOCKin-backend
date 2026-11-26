package com.DOCKin.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UnitQueryParser - 질문에서 설비와 호기를 뽑는다 (호기 필터 실험)")
class UnitQueryParserTest {

    @Test
    @DisplayName("세 언어의 호기 표기를 읽는다")
    void parsesThreeLanguages() {
        assertThat(UnitQueryParser.parse("겐트리 크레인 3호기 유압 실린더"))
                .contains(new UnitQueryParser.Machine("겐트리 크레인", "3"));
        assertThat(UnitQueryParser.parse("Gantry crane No. 3 hydraulic cylinder"))
                .contains(new UnitQueryParser.Machine("겐트리 크레인", "3"));
        assertThat(UnitQueryParser.parse("Xi lanh thủy lực cần trục giàn số 3"))
                .contains(new UnitQueryParser.Machine("겐트리 크레인", "3"));
    }

    @Test
    @DisplayName("CO2의 2를 호기로 읽지 않는다 - 호기 표지가 붙은 숫자만")
    void co2IsNotAUnit() {
        assertThat(UnitQueryParser.parse("CO2 용접기 송급 롤러 이상 진동")).isEmpty();
        assertThat(UnitQueryParser.parse("CO2 용접기 5호기 제어 패널"))
                .contains(new UnitQueryParser.Machine("CO2 용접기", "5"));
    }

    @Test
    @DisplayName("긴 표기를 먼저 맞춘다 - 베트남어 고소작업대가 지게차로 읽히지 않는다")
    void longestAliasWins() {
        assertThat(UnitQueryParser.parse("Bộ phận vòng bi xe nâng người làm việc trên cao số 1"))
                .contains(new UnitQueryParser.Machine("고소작업대", "1"));
        assertThat(UnitQueryParser.parse("Trục động cơ điện xe nâng số 2"))
                .contains(new UnitQueryParser.Machine("지게차", "2"));
    }

    /**
     * 평가셋 전체에서 — 호기 질문은 전부 정답 조건대로 잡고, 나머지는 하나도 잡지 않아야 한다.
     * 이 파서는 평가셋의 표기로 사전을 닫았으므로 여기서 100%가 아니면 실험 결과를 읽을 수 없다.
     */
    @Test
    @DisplayName("평가셋: 호기 질문은 모두 정답대로, 나머지는 하나도 잡지 않는다")
    void evalSetPrecisionAndRecall() throws Exception {
        List<String> misses = new ArrayList<>();
        List<String> falsePositives = new ArrayList<>();
        int unitQuestions = 0;
        List<String> lines = Files.readAllLines(SearchQualityEvalTest.QUERIES, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t", -1);
            String qid = f[0] + "/" + f[2];
            String query = f[3];
            String equipment = f[4];
            String unit = f[5];
            Optional<UnitQueryParser.Machine> got = UnitQueryParser.parse(query);
            if (!unit.isEmpty()) {
                unitQuestions++;
                if (!got.equals(Optional.of(new UnitQueryParser.Machine(equipment, unit)))) {
                    misses.add(qid + " " + got);
                }
            } else if (got.isPresent()) {
                falsePositives.add(qid + " " + got);
            }
        }
        assertThat(unitQuestions).as("호기 질문 수(11 × 3언어)").isEqualTo(33);
        assertThat(misses).as("호기 질문을 못 잡음").isEmpty();
        assertThat(falsePositives).as("호기 질문이 아닌데 잡음").isEmpty();
    }
}
