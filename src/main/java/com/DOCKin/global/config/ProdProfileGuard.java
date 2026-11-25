package com.DOCKin.global.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 운영 프로파일에 <b>개발용 스위치가 섞이면 기동을 거부한다.</b>
 *
 * <h3>왜 경고가 아니라 거부인가</h3>
 * 섞였을 때의 결과가 둘 다 조용하다.
 * <ul>
 *   <li>{@code seed}: {@code R__seed_sample.sql}이 운영 DB에 들어간다. Flyway가 히스토리에 적으므로
 *       그 뒤로는 seed를 빼면 오히려 기동이 막힌다({@code application.properties} [Flyway] 주석) — 되돌리기가 손작업이다</li>
 *   <li>{@code ai.chatbot.stub=true}: 챗봇이 모델 대신 검색 근거 목록을 "답"으로 내보낸다. 에러가 아니라 응답이 나간다</li>
 * </ul>
 * {@code StubChatGenerator}가 이미 WARN을 찍지만, 알림이 없는 지금(PRODUCTION-READINESS O2) WARN은 아무도 안 읽는다.
 * 기동 실패는 배포 스크립트의 헬스 대기에서 잡히고 이전 이미지로 돌아간다(scripts/deploy/deploy.sh).
 *
 * <p>{@code application-prod.properties}가 {@code ai.chatbot.stub=false}를 상수로 덮으므로 둘째 조건은
 * 보통 걸리지 않는다. 걸린다면 누군가 더 높은 우선순위(환경변수 {@code AI_CHATBOT_STUB}가 아니라
 * {@code ai.chatbot.stub} 그 자체, 또는 커맨드라인)로 다시 켠 것이다 — 그 경우를 위해 남긴다.
 */
@Component
@Profile("prod")
public class ProdProfileGuard {

    public ProdProfileGuard(Environment env) {
        List<String> problems = check(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("prod 프로파일과 함께 켜면 안 되는 설정: " + String.join(", ", problems));
        }
    }

    static List<String> check(Environment env) {
        List<String> problems = new ArrayList<>();
        if (env.acceptsProfiles(Profiles.of("seed"))) {
            problems.add("seed 프로파일(표본 데이터가 운영 DB에 들어간다)");
        }
        String locations = env.getProperty("spring.flyway.locations", "");
        if (locations.contains("db/seed")) {
            problems.add("spring.flyway.locations에 db/seed");
        }
        if (env.getProperty("ai.chatbot.stub", Boolean.class, false)) {
            problems.add("ai.chatbot.stub=true(챗봇이 근거 목록을 답으로 낸다)");
        }
        return problems;
    }
}
