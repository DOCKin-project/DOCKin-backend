package com.DOCKin.rag.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문에서 "어느 설비의 몇 호기"를 뽑는다 — 호기 필터 실험(#170 후속, {@code docs/eval/search-quality/UNIT-FILTER-EXPERIMENT.md}).
 *
 * <h3>측정 전용이다</h3>
 * 설비 이름 사전이 평가 코퍼스의 슬롯 목록(12종)과 평가 질문의 영어 표기로 닫혀 있다. 실데이터의 별칭
 * ("크레인", "용접기", "컴프레서")은 하나도 모른다. 그래서 이 파서가 맞히는 비율은 <b>상한</b>이고,
 * 운영에 넣으려면 별칭 사전이나 LLM 추출이 필요하다 — 실험이 답하려는 것은 "필터가 효과가 있는가"이지
 * "이 파서가 쓸 만한가"가 아니다.
 *
 * <h3>호기와 설비가 둘 다 있을 때만 잡는다</h3>
 * 호기만 있으면 어느 설비인지 모르고, 설비만 있으면 호기 필터가 아니다. 숫자만 보고 잡으면
 * "CO2 용접기"의 2를 호기로 읽는다 — 그래서 호기 표지(호기·No.·số)가 붙은 숫자만 본다.
 */
final class UnitQueryParser {

    record Machine(String equipment, String unit) {}

    /** 한국어 설비명(정답 조건 표기) ← 언어별 표기. 긴 것부터 맞춘다("xe nâng người…"가 "xe nâng"보다 먼저). */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("CO2 용접기", List.of("CO2 용접기", "CO2 welder", "máy hàn CO2")),
            Map.entry("겐트리 크레인", List.of("겐트리 크레인", "gantry crane", "cần trục giàn")),
            Map.entry("무인 도장설비", List.of("무인 도장설비", "automated painting equipment", "thiết bị sơn tự động")),
            Map.entry("플라즈마 절단기", List.of("플라즈마 절단기", "plasma cutter", "máy cắt plasma")),
            Map.entry("고소작업대", List.of("고소작업대", "aerial work platform", "xe nâng người làm việc trên cao")),
            Map.entry("유압 프레스", List.of("유압 프레스", "hydraulic press", "máy ép thủy lực")),
            Map.entry("블라스팅 장비", List.of("블라스팅 장비", "blasting equipment", "thiết bị phun cát")),
            Map.entry("이송 컨베이어", List.of("이송 컨베이어", "transfer conveyor", "băng tải vận chuyển")),
            Map.entry("공기압축기", List.of("공기압축기", "air compressor", "máy nén khí")),
            Map.entry("집진 설비", List.of("집진 설비", "dust collector", "thiết bị hút bụi")),
            Map.entry("지게차", List.of("지게차", "forklift", "xe nâng")),
            Map.entry("발전기", List.of("발전기", "generator", "máy phát điện")));

    /** (표기, 한국어 설비명)을 표기 길이 내림차순으로. */
    private static final List<Map.Entry<String, String>> BY_LENGTH = ALIASES.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(a -> Map.entry(a.toLowerCase(Locale.ROOT), e.getKey())))
            .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
            .toList();

    /** 호기 표지가 붙은 숫자. 한국어 "3호기", 영어 "No. 3"·"No 3"·"#3", 베트남어 "số 3". */
    private static final Pattern UNIT = Pattern.compile(
            "(\\d+)\\s*호기|\\bno\\.?\\s*(\\d+)|#\\s*(\\d+)|\\bsố\\s*(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private UnitQueryParser() {
    }

    static Optional<Machine> parse(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        String equipment = null;
        for (var e : BY_LENGTH) {
            if (lower.contains(e.getKey())) {
                equipment = e.getValue();
                break;
            }
        }
        Matcher m = UNIT.matcher(query);
        String unit = null;
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    unit = m.group(g);
                    break;
                }
            }
        }
        return equipment != null && unit != null ? Optional.of(new Machine(equipment, unit)) : Optional.empty();
    }
}
