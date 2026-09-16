package com.DOCKin.ai.quota;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * 사용자별·종류별 <b>하루 외부 호출 한도.</b> Redis에 있고, 날이 바뀌면 키가 사라진다.
 *
 * <h3>왜 있는가</h3>
 * {@code /api/ai/*} 세 경로는 전부 FastAPI로 나가는 비용 호출인데 한도가 아무 데도 없었다.
 * 번역 호출이 하루 12,000~36,000건으로 가정돼 있고({@code SERVICE-SCALE-ASSUMPTIONS.md} 3-3),
 * 그 가정은 "정상 사용"이다. 한 사용자가 연타하거나 클라이언트가 재시도 루프에 빠지면
 * 가정 밖의 비용이 나간다. 이 클래스는 <b>비용 상한</b>이지 트래픽 제어가 아니다 —
 * ADR-0001의 분산락을 "트래픽 때문"이라 설명하면 안 되는 것과 같은 이유로, 이것도 그렇다.
 *
 * <h3>왜 Redis인가 — {@code JwtBlacklist}와 같은 이유</h3>
 * 인메모리 카운터는 재배포하면 0이 된다. 하루 한도가 재배포마다 풀리면 한도가 아니다.
 * Redis는 이미 락·블랙리스트로 있으므로 저장소를 새로 들이지 않는다.
 *
 * <h3>호출 전에 올린다</h3>
 * {@code INCR}(원자적 +1)를 먼저 하고, 나온 값이 상한을 넘으면 FastAPI를 부르지 않는다.
 * FastAPI가 실패해도 1로 센다. "성공한 것만 세기"는 사용자에게 공정하지만 검사와 증가가
 * 갈려 동시 요청이 상한을 살짝 넘길 수 있고, 실패 연타를 못 막는다. 실패까지 세는 쪽이
 * 단순하고 비용 상한이라는 목적에 맞다.
 *
 * <h3>Redis가 죽으면 연다 — 블랙리스트와 반대</h3>
 * {@code JwtBlacklist}는 Redis 장애에 401로 닫는다 — 열면 로그아웃한 토큰이 통하기 때문이다.
 * 여기는 연다. 번역·챗봇은 "있으면 좋은 것"이고(ADR-0008 D5) 한도 검사가 안 된다고
 * 그 기능을 막을 이유가 없다. 단, WARN을 남겨 열린 채로 지나간 시간이 로그에 보이게 한다.
 *
 * <h3>날짜 경계는 서버 시간대의 자정</h3>
 * {@link Clock} 빈을 쓴다 — 출근의 "오늘"({@code AttendanceService})과 같은 시계다.
 * 키에 날짜가 들어가고 TTL은 자정까지라, 자정이 지나면 새 키가 0에서 시작한다.
 *
 * <h3>상한값은 가정이다</h3>
 * {@code ai.quota.daily.*}의 기본값은 측정에서 나온 것이 아니다. 가정 문서의
 * 챗봇 1인 주 1회·번역 메시지의 10~30%에서 "정상 사용은 하루 수십 건"이라는 자릿수만 가져왔다.
 * 실측이 생기면 그 분포에서 다시 정한다.
 */
@Slf4j
@Component
public class AiQuota {

    private static final String KEY_PREFIX = "quota:ai:";

    private final RedissonClient redissonClient;
    private final Clock clock;
    private final Map<AiQuotaKind, Long> limits = new EnumMap<>(AiQuotaKind.class);

    public AiQuota(RedissonClient redissonClient,
                   Clock clock,
                   @Value("${ai.quota.daily.chatbot:100}") long chatbot,
                   @Value("${ai.quota.daily.worklog-translate:100}") long worklogTranslate,
                   @Value("${ai.quota.daily.rt-translate:2000}") long rtTranslate) {
        this.redissonClient = redissonClient;
        this.clock = clock;
        limits.put(AiQuotaKind.CHATBOT, chatbot);
        limits.put(AiQuotaKind.WORKLOG_TRANSLATE, worklogTranslate);
        limits.put(AiQuotaKind.RT_TRANSLATE, rtTranslate);
    }

    /**
     * 오늘 카운터를 1 올리고, 상한을 넘었으면 던진다. <b>외부 호출 전에</b> 부른다.
     *
     * @throws AiQuotaExceededException 상한 초과. 이 호출도 이미 1로 세어졌다
     */
    public void consume(AiQuotaKind kind, String userId) {
        long limit = limits.get(kind);
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        long used;
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(key(kind, userId, today));
            used = counter.incrementAndGet();
            if (used == 1) {
                // 첫 호출이 TTL을 건다. 이후 호출은 건드리지 않는다 — 매번 걸면 자정이 밀린다.
                counter.expire(untilMidnight(now));
            }
        } catch (RuntimeException e) {
            log.warn("AI 한도 카운터를 쓸 수 없어 검사 없이 통과시킵니다. kind={}, userId={}, cause={}",
                    kind.key(), userId, e.toString());
            return;
        }
        if (used > limit) {
            log.warn("AI 한도 초과. kind={}, userId={}, used={}, limit={}", kind.key(), userId, used, limit);
            throw new AiQuotaExceededException(kind, untilMidnight(now).toSeconds());
        }
    }

    private static String key(AiQuotaKind kind, String userId, LocalDate day) {
        return KEY_PREFIX + kind.key() + ":" + userId + ":" + day;
    }

    private static Duration untilMidnight(ZonedDateTime now) {
        ZonedDateTime midnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT)
                .atZone(now.getZone());
        // 자정 정각이면 다음 자정까지 24시간이다. 0이 되는 경우는 없다.
        return Duration.between(now, midnight);
    }
}
