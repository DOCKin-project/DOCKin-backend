package com.DOCKin.ai.quota;

/**
 * 외부(FastAPI) 호출의 종류. 종류마다 카운터와 상한이 따로다.
 *
 * <p>하나로 합치지 않은 이유: {@code rt-translate}는 <b>발화 하나당 한 번</b> 호출된다.
 * 챗봇·작업일지 번역은 "버튼 한 번에 한 번"이라 자릿수가 다르다. 같은 카운터에 넣으면
 * 실시간 번역 몇 분에 챗봇이 막힌다.
 */
public enum AiQuotaKind {
    CHATBOT("chatbot"),
    WORKLOG_TRANSLATE("worklog-translate"),
    RT_TRANSLATE("rt-translate");

    private final String key;

    AiQuotaKind(String key) {
        this.key = key;
    }

    /** Redis 키와 property 이름 양쪽에 쓰는 조각. */
    public String key() {
        return key;
    }
}
