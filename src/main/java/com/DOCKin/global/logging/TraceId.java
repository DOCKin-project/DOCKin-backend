package com.DOCKin.global.logging;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 추적 ID. MDC 키 하나와 그 값의 규칙을 한곳에 모은다.
 *
 * <h3>왜 필요했는가</h3>
 * {@code traceId}는 이 저장소에 <b>이미 있었지만 절반만 쓰이고 있었다</b>(백로그 P2-11-3).
 * DTO 필드로 존재해 FastAPI로 넘어가고 {@code chat_history.trace_id}에도 남는데,
 * <b>MDC를 쓰지 않아 애플리케이션 로그에는 없었다.</b> 그래서 DB에 남은 추적 ID로
 * 그 요청이 남긴 로그를 찾아갈 수 없었다 -- 양 끝은 있는데 가운데가 비어 있는 상태다.
 *
 * <h3>값의 출처는 셋이고 우선순위가 있다</h3>
 * <ol>
 *   <li>요청 본문의 {@code traceId} -- AI 경로에만 있다. {@code chat_history}에 남는 값과
 *       같아야 하므로 <b>가장 우선</b>한다({@link #override(String)})</li>
 *   <li>{@code X-Trace-Id} 헤더 -- 프론트엔드나 게이트웨이가 이미 만든 값이 있으면 잇는다</li>
 *   <li>없으면 서버가 만든다. 추적 ID가 없는 요청이 있어서는 안 된다</li>
 * </ol>
 *
 * <h3>바깥에서 온 값을 그대로 믿지 않는다</h3>
 * 헤더와 본문은 클라이언트가 정하는 값이다. 검사 없이 MDC에 넣으면 두 가지가 열린다 --
 * <b>줄바꿈을 넣어 로그에 가짜 줄을 만들 수 있고</b>(로그 위조), 응답 헤더로 되돌려주므로
 * <b>헤더 인젝션</b>도 된다. 그래서 길이와 문자 집합을 제한하고, 어긋나면 서버가 만든 값을 쓴다.
 */
public final class TraceId {

    /** 로그 패턴의 {@code %X{traceId}}가 읽는 키. 바꾸면 application.properties도 함께 고쳐야 한다. */
    public static final String MDC_KEY = "traceId";

    /** 요청에서 읽고 응답으로 되돌려주는 헤더. 클라이언트가 자기 로그와 이어 붙일 수 있게 한다. */
    public static final String HEADER = "X-Trace-Id";

    /**
     * 허용 문자 집합. 영숫자와 {@code _ . -}만 받는다.
     * 제어문자(특히 {@code \n}, {@code \r})를 막는 것이 목적이고, 64자 상한은
     * 로그 한 줄을 추적 ID가 잡아먹지 않게 하는 것이다.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private TraceId() {
    }

    /**
     * 서버가 만드는 값. UUID에서 하이픈을 빼고 앞 16자(64비트)만 쓴다.
     *
     * <p>전체 32자를 그대로 쓰면 로그 모든 줄에 붙어 읽기 나빠진다. 반대로 8자(32비트)는
     * 하루 수십만 요청에서 생일 문제로 충돌이 실제로 난다. 64비트면 이 규모에서 충돌을 걱정하지 않아도 된다.
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 바깥에서 온 값을 그대로 써도 되는지. {@code null}·공백·제어문자·과도한 길이를 모두 거른다. */
    public static boolean isSafe(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches();
    }

    /**
     * 요청 본문에서 온 추적 ID로 덮어쓴다. AI 경로에서 호출한다.
     *
     * <p><b>덮어쓰는 이유</b> -- 그 값이 {@code chat_history.trace_id}·{@code translate_logs.trace_id}에
     * 저장되고 FastAPI로도 넘어간다. 로그가 다른 값을 쓰면 <b>DB와 로그를 이을 수 없다.</b>
     * 필터가 만든 값은 그 요청에 본문 추적 ID가 없을 때의 대비책일 뿐이다.
     *
     * <p>안전하지 않은 값이면 <b>아무것도 하지 않는다.</b> 필터가 이미 넣어둔 값이 남으므로
     * 추적 ID가 비는 일은 없다.
     */
    public static void override(String candidate) {
        if (isSafe(candidate)) {
            MDC.put(MDC_KEY, candidate);
        }
    }

    /** 현재 요청의 추적 ID. 필터 바깥(배치 스레드 등)에서는 {@code null}일 수 있다. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
