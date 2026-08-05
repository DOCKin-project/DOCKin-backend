package com.DOCKin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

/**
 * 검증: 지금 DB가 엔티티와 일치하는가?
 *
 * <h3>왜 필요한가</h3>
 * 이 프로젝트는 스키마를 {@code ddl-auto=update}가 만든다. 즉 <b>스키마를 적어둔 파일이 없고</b>
 * 소스 오브 트루스는 엔티티다. 그런데 {@code update}는 <b>추가만 한다</b> --
 * 컬럼 삭제, 타입 변경, {@code NOT NULL} 완화는 반영하지 않는다.
 *
 * <p>그래서 엔티티를 고쳐도 DB는 옛 모습으로 남을 수 있고, <b>비교할 파일이 없으니 아무도 모른다.</b>
 * 이 테스트가 그 비교를 대신한다. {@code ddl-auto=validate}는 매핑과 실제 테이블이 어긋나면
 * 컨텍스트 로딩을 실패시킨다.
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
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+",
        disabledReason = "PostgreSQL 접속 정보가 없어 스키마 검증을 건너뜁니다.")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/dockindb",
        "spring.datasource.username=root",
        "spring.datasource.password=${DB_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        // 이 테스트의 전부다. 어긋나면 컨텍스트가 뜨지 않는다.
        "spring.jpa.hibernate.ddl-auto=validate",
        // 검증만 하므로 마이그레이션을 다시 돌릴 필요가 없다.
        "spring.flyway.enabled=false"
})
class SchemaValidationTest {

    @Test
    @DisplayName("모든 엔티티 매핑이 실제 테이블과 일치한다")
    void 스키마_일치() {
        // 컨텍스트가 떴다는 것이 곧 검증 통과다.
        // validate가 실패하면 이 메서드에 도달하지 못한다.
    }
}
