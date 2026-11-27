package com.DOCKin.rag.service;

/**
 * 검색 평가 정답 라벨의 사건 정규식(#219). {@link SearchQualityEvalTest#relevant}가 PostgreSQL {@code ~}로 쓴다.
 *
 * <p>생성 본문({@code CorpusSeedGeneratorTest.bodyKo})의 사건 틀:
 * <pre>
 *   첫 사건  : 본문 맨 앞  {설비} {호기}호기 {부품}에서 {증상}. 점검 결과 {원인}이(가) 원인으로 확인되었다. {조치}.
 *   둘째 사건: 긴 본문만   {설비} {호기}호기 {부품}에서도 {증상}. 이쪽은 {원인}이(가) 원인으로 확인되었다. {조치}.
 * </pre>
 * 조건이 빈 슬롯은 마침표 없는 아무 글자({@code [^.]+})로 둔다. 슬롯 값에는 마침표가 없으므로 빈 슬롯이
 * 다음 문장으로 넘어가 다른 사건의 슬롯과 섞이지 않는다.
 *
 * <p>테스트 컨테이너를 띄우는 {@link SearchQualityEvalTest} 밖에 둔 이유: DB 없이 단위 테스트로 확인하려고.
 * 이 꼴(문자 클래스·이스케이프·{@code ^})에서는 PostgreSQL ARE와 {@code java.util.regex}가 같게 동작한다.
 */
final class EventLabelPattern {

    /** 빈 슬롯. */
    static final String ANY_SLOT = "[^.]+";

    private EventLabelPattern() {
    }

    /** 빈 문자열인 조건은 와일드카드다. 호기가 비면 숫자 아무거나. */
    static String of(String equipment, String unit, String part, String symptom, String cause, String action,
                     boolean first) {
        String u = unit.isEmpty() ? "[0-9]+" : quote(unit);
        return (first ? "^" : "")
                + slot(equipment) + " " + u + "호기 " + slot(part)
                + (first ? "에서 " : "에서도 ") + slot(symptom) + "\\. "
                + (first ? "점검 결과 " : "이쪽은 ") + slot(cause) + "[이가] 원인으로 확인되었다\\. "
                + slot(action) + "\\.";
    }

    private static String slot(String value) {
        return value.isEmpty() ? ANY_SLOT : quote(value);
    }

    /** 정규식 메타 문자를 글자로. */
    static String quote(String s) {
        return s.replaceAll("([\\\\.^$|?*+()\\[\\]{}])", "\\\\$1");
    }
}
