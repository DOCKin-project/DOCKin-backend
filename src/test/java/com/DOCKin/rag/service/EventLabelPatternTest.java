package com.DOCKin.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("검색 평가 정답 라벨 — 한 사건이 조건을 모두 갖춰야 정답 (#219)")
class EventLabelPatternTest {

    /** 긴 본문 하나. 첫 사건: 크레인 3호기 유압 실린더 진동/윤활 부족/그리스. 둘째: 같은 호기 감속기 누유/개스킷 경화/개스킷 교체. */
    private static final String LONG = "겐트리 크레인 3호기 유압 실린더에서 이상 진동이 발생했다. "
            + "점검 결과 윤활 부족이 원인으로 확인되었다. 그리스를 보충했다. 조치 후 20분간 시운전하여 정상 동작을 확인했다. "
            + "측정값은 50.1이며 기준 범위 이내였다. 설비 이력 카드에 반영했다. 추가로 인접 계통도 함께 점검했다. "
            + "겐트리 크레인 3호기 감속기에서도 누유가 확인되었다. 이쪽은 개스킷 경화가 원인으로 확인되었다. "
            + "개스킷을 신품으로 교체했다. 두 건 모두 같은 원인 계열로 보이므로 개별 조치가 아니라 계통 단위 점검이 필요하다.";

    private static boolean matches(String eq, String unit, String part, String symptom, String cause, String action) {
        return Pattern.compile(EventLabelPattern.of(eq, unit, part, symptom, cause, action, true)).matcher(LONG).find()
                || Pattern.compile(EventLabelPattern.of(eq, unit, part, symptom, cause, action, false)).matcher(LONG).find();
    }

    @Test
    @DisplayName("첫 사건이 조건을 다 갖추면 정답")
    void firstEvent() {
        assertThat(matches("겐트리 크레인", "", "유압 실린더", "이상 진동이 발생했다", "", "")).isTrue();
        assertThat(matches("겐트리 크레인", "3", "", "", "윤활 부족", "그리스를 보충했다")).isTrue();
    }

    @Test
    @DisplayName("둘째 사건이 조건을 다 갖추면 정답 — 옛 규칙은 오답으로 셌다")
    void secondEvent() {
        assertThat(matches("겐트리 크레인", "", "감속기", "누유가 확인되었다", "", "")).isTrue();
        assertThat(matches("겐트리 크레인", "", "", "누유가 확인되었다", "", "")).isTrue();
    }

    @Test
    @DisplayName("조건이 두 사건에 나뉘어 있으면 오답 — 옛 규칙은 정답으로 셌다")
    void splitAcrossEvents() {
        // 원인은 첫 사건(윤활 부족), 조치는 둘째 사건(개스킷 교체)
        assertThat(matches("겐트리 크레인", "", "", "", "윤활 부족", "개스킷을 신품으로 교체했다")).isFalse();
        // 부품은 첫 사건, 증상은 둘째 사건
        assertThat(matches("겐트리 크레인", "", "유압 실린더", "누유가 확인되었다", "", "")).isFalse();
    }

    @Test
    @DisplayName("다른 설비·다른 호기는 오답")
    void wrongEquipmentOrUnit() {
        assertThat(matches("지게차", "", "", "누유가 확인되었다", "", "")).isFalse();
        assertThat(matches("겐트리 크레인", "5", "", "누유가 확인되었다", "", "")).isFalse();
    }

    @Test
    @DisplayName("첫 사건 틀은 본문 맨 앞에서만 — 둘째 사건 문장을 첫 사건으로 잡지 않는다")
    void firstEventAnchored() {
        String pattern = EventLabelPattern.of("겐트리 크레인", "", "감속기", "", "", "", true);
        assertThat(Pattern.compile(pattern).matcher(LONG).find()).isFalse();
    }

    @Test
    @DisplayName("정규식 메타 문자는 글자로")
    void quote() {
        assertThat(EventLabelPattern.quote("CO2 용접기 (A.1)")).isEqualTo("CO2 용접기 \\(A\\.1\\)");
    }
}
