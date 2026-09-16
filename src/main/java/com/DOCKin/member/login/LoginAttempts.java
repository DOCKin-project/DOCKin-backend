package com.DOCKin.member.login;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 사원번호별 <b>로그인 실패 횟수.</b> Redis에 있고, 창이 지나면 키가 사라진다 (백로그 P2-18-12, ADR-0009 여섯째 용도).
 *
 * <h3>왜 있는가</h3>
 * 비밀번호 정책이 {@code @NotBlank}뿐이라 짧은 비밀번호가 가능하고, 로그인에 시도 제한이 없었다
 * (P2-18-8이 "시도 제한도 없다"로 남긴 것). bcrypt가 한 번에 수십 ms라 처리량으로도 막히지 않는다 —
 * 2코어에서 초당 수십 번이면 네 자리 숫자는 몇 분이다. 이 클래스가 그 벽이다.
 *
 * <h3>축은 사원번호뿐 — IP는 안 쓴다</h3>
 * 현장은 근로자 1,500명이 NAT 하나로 나온다({@code SERVICE-SCALE-ASSUMPTIONS.md} 3-1). IP로 세면
 * 한 사람의 오타가 사이트 전체를 잠근다. 계정별로만 세고, IP 축은 있다면 nginx의 {@code limit_req}
 * 자리다 — 거기도 NAT 때문에 값을 넉넉히 줘야 한다.
 *
 * <p>계정별의 대가는 <b>잠금 DoS</b>다 — 남의 사원번호로 열 번 틀리면 그 사람이 10분 막힌다. 창을 짧게
 * 두고 영구 잠금을 하지 않는 것이 그 대가를 감당하는 방법이다. 사원번호를 알아야 하는데 로그인 응답으로는
 * 못 알아낸다(P2-18-8): 없는 사원번호도 <b>같은 키로 센다.</b> 있는 계정만 세면 429가 나는 쪽이 "있는
 * 계정"이라 열거가 다시 열린다.
 *
 * <h3>bcrypt 전에 본다</h3>
 * {@link #check}는 비밀번호를 대조하기 전에 부른다. 막힌 요청이 bcrypt를 태우면 시도 제한이 CPU 소모
 * 공격의 도구가 된다. 맞는 비밀번호로도 막힌 동안은 429다 — 200과 429로 갈리면 그게 곧 정답 확인이다.
 *
 * <h3>Redis가 죽으면 닫는다 — 503</h3>
 * {@code AiQuota}는 열고 {@code JwtBlacklist}는 닫는데, 여기는 닫는다. 근거는 "열면 브루트포스가
 * 통한다"만이 아니다 — <b>닫아도 잃는 게 없다.</b> 블랙리스트가 닫힘이라 Redis가 죽으면 인증이 전부
 * 401이고, 로그인을 열어 봐야 받은 토큰을 쓸 데가 없다. 반대로 열면 장애 중 브루트포스로 얻은 토큰이
 * 복구 뒤에 유효해진다. 가용성 이득 0, 보안 손실 양수. 블랙리스트 정책이 바뀌면 이 결정도 같이 본다.
 *
 * <h3>고정 창 — 10회 / 10분</h3>
 * 첫 실패가 TTL을 걸고, 창 안의 실패는 그 TTL을 건드리지 않는다({@code AiQuota}와 같은 방식). 슬라이딩
 * 창은 실패마다 시각을 저장해야 해서 무겁고, 여기서 막으려는 것은 "분당 몇 번"의 정밀도가 아니라
 * "하루에 몇천 번"의 자릿수다. 상한 10은 07:00 출근길에 오타를 대여섯 번 내는 사람이 안 걸리는 선이고
 * (정상 최대의 1.5배 이상 — P2-19의 첫째 축), 공격자에게는 계정당 하루 1,440회다. 값은
 * {@code application.properties}.
 */
@Slf4j
@Component
public class LoginAttempts {

    private static final String KEY_PREFIX = "login:fail:";

    private final RedissonClient redissonClient;
    private final long max;
    private final Duration window;

    public LoginAttempts(RedissonClient redissonClient,
                         @Value("${login.attempts.max:10}") long max,
                         @Value("${login.attempts.window-minutes:10}") long windowMinutes) {
        this.redissonClient = redissonClient;
        this.max = max;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    /**
     * 막혀 있으면 던진다. <b>비밀번호를 대조하기 전에</b> 부른다.
     *
     * @throws LoginAttemptsExceededException 창 안의 실패가 상한에 닿았다. {@code Retry-After}는 창이 끝날 때까지
     * @throws BusinessException              {@code LOGIN_UNAVAILABLE} — Redis를 못 쓴다. 열지 않는다
     */
    public void check(String userId) {
        long failed;
        long remainingMs;
        try {
            RAtomicLong counter = counter(userId);
            failed = counter.get();
            remainingMs = failed >= max ? counter.remainTimeToLive() : 0;
        } catch (RuntimeException e) {
            throw unavailable(userId, e);
        }
        if (failed >= max) {
            // TTL이 없는 키(-1)는 생기지 않지만, 생겼다면 창 길이로 답한다. 0으로 답하면 클라이언트가 바로 재시도한다.
            long retryAfter = remainingMs > 0 ? (remainingMs + 999) / 1000 : window.toSeconds();
            log.warn("로그인 시도 제한. userId={}, failed={}, retryAfter={}s", userId, failed, retryAfter);
            throw new LoginAttemptsExceededException(retryAfter);
        }
    }

    /** 비밀번호가 틀렸다(또는 사원번호가 없다 — 둘을 가르지 않는다). 창 안의 첫 실패가 TTL을 건다. */
    public void failed(String userId) {
        try {
            RAtomicLong counter = counter(userId);
            long failed = counter.incrementAndGet();
            if (failed == 1) {
                counter.expire(window);
            }
        } catch (RuntimeException e) {
            throw unavailable(userId, e);
        }
    }

    /** 로그인 성공. 창 안의 실패를 지운다 — 본인이 맞았으므로 이전 오타를 들고 갈 이유가 없다. */
    public void succeeded(String userId) {
        try {
            counter(userId).delete();
        } catch (RuntimeException e) {
            throw unavailable(userId, e);
        }
    }

    private RAtomicLong counter(String userId) {
        return redissonClient.getAtomicLong(KEY_PREFIX + userId);
    }

    private static BusinessException unavailable(String userId, RuntimeException cause) {
        // 닫힘은 조용하면 안 된다. 503이 나가는 동안 Redis가 죽어 있었다는 것이 로그에 있어야 한다.
        log.error("로그인 시도 카운터를 쓸 수 없어 로그인을 막습니다(닫힘). userId={}, cause={}", userId, cause.toString());
        return new BusinessException(ErrorCode.LOGIN_UNAVAILABLE);
    }
}
