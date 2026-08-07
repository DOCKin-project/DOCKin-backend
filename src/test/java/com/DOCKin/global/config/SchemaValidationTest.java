package com.DOCKin.global.config;

import com.DOCKin.global.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

/**
 * 검증: 지금 DB가 엔티티와 일치하는가?
 *
 * <h3>왜 필요한가</h3>
 * 스키마는 {@code db/migration/V*.sql}이 만들고 엔티티는 그것을 매핑한다. 둘은 사람이 따로
 * 고치는 것이라 <b>어긋날 수 있다</b> — 엔티티에 필드를 추가하고 마이그레이션을 빠뜨리는 식이다.
 *
 * <p>운영 설정({@code ddl-auto=validate})이 기동 시 이를 잡지만, <b>기동해봐야 안다</b>는 뜻이기도 하다.
 * 이 테스트는 그 검사를 빌드로 앞당긴다.
 *
 * <h3>Flyway를 켜둔 채로 본다</h3>
 * 마이그레이션을 끄고 검증하면 "이미 만들어져 있는 DB에서만" 맞다는 뜻이 된다.
 * 켜두면 <b>기동 경로 그대로</b> — 마이그레이션이 만든 스키마를 매핑이 검증하는 — 순서를 본다.
 * 빈 DB에서는 V1/V2가 전부 만들고 그 위에서 검증이 돌며, 이미 적용된 DB에서는 Flyway가 통과만 한다.
 *
 * <h3>이 테스트가 하지 않는 것</h3>
 * {@code validate}는 <b>엔티티가 요구하는 것이 DB에 있는지</b>만 본다.
 * 반대 방향 -- DB에만 있고 엔티티에는 없는 <b>잉여 테이블·컬럼은 잡지 못한다.</b>
 * (실제로 벤치마크 테스트가 남긴 {@code bench_leave_balance}가 그런 예다.)
 * 그쪽은 사람이 주기적으로 봐야 한다.
 *
 * <h3>실패하면</h3>
 * DB를 지우고 다시 만들라는 뜻이 아니다. <b>무엇이 어긋났는지</b>가 예외 메시지에 나오므로,
 * 그것이 의도한 변경이면 마이그레이션을 쓰고 아니면 엔티티를 고친다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        // 이 테스트의 전부다. 어긋나면 컨텍스트가 뜨지 않는다.
        "spring.jpa.hibernate.ddl-auto=validate",
        // 기동 경로와 같은 순서를 보기 위해 켜둔다. Spring Boot가 Flyway를 먼저 실행한다.
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
class SchemaValidationTest extends PostgresTestSupport {

    @Test
    @DisplayName("모든 엔티티 매핑이 실제 테이블과 일치한다")
    void 스키마_일치() {
        // 컨텍스트가 떴다는 것이 곧 검증 통과다.
        // validate가 실패하면 이 메서드에 도달하지 못한다.
    }
}
