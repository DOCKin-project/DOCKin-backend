package com.DOCKin.DOCKin_spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트가 실제로 뜨는지 보는 스모크 테스트.
 *
 * <h3>왜 환경변수 가드가 붙어 있는가</h3>
 * 다른 테스트들과 달리 이것은 <b>전체 컨텍스트</b>를 띄운다. 즉 DB, Redis, JWT 시크릿,
 * FastAPI 주소가 모두 있어야 한다({@code application.properties}가 {@code ${DB_HOST}},
 * {@code ${JWT_SECRET}} 등을 기본값 없이 참조한다). 가드가 없으면 CI에서 이 한 개 때문에
 * 전체 빌드가 빨간불이 된다 -- 실제로 그 상태였다.
 *
 * <p>{@code DB_PASSWORD}를 대표 신호로 쓰는 것은 이 저장소의 다른 DB 테스트와 맞춘 규약이다.
 * 이 변수 하나만 있고 나머지가 없으면 여전히 실패하지만, 그런 환경은 실수이므로 드러나는 편이 낫다.
 *
 * <h3>CI가 잃는 것</h3>
 * 컨텍스트 로딩 실패(빈 순환, 설정 누락)는 <b>CI에서 잡히지 않는다.</b> 로컬 실행에서만 걸린다.
 * 이걸 CI로 옮기려면 서비스 컨테이너로 PostgreSQL을 띄워야 하는데, 그러면 {@code DB_PASSWORD}가
 * 존재하게 되어 10만 청크 벤치마크까지 함께 깨어난다. 백로그 P0-7 참고.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+",
		disabledReason = "전체 컨텍스트에 필요한 외부 의존(DB/Redis/JWT/FastAPI)이 없어 건너뜁니다.")
class DocKinSpringApplicationTests {

	@Test
	void contextLoads() {
	}

}
