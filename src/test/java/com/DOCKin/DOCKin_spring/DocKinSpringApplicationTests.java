package com.DOCKin.DOCKin_spring;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트가 실제로 뜨는지 보는 스모크 테스트.
 *
 * <h3>이 테스트만 요구 사항이 넓다</h3>
 * 다른 테스트들은 {@code @DataJpaTest} 슬라이스라 DB만 있으면 되지만, 이것은 <b>전체 컨텍스트</b>를
 * 띄운다. {@code application.properties}가 {@code ${DB_HOST}}, {@code ${JWT_SECRET}},
 * {@code ${AWS_ACCESS_KEY}} 등을 <b>기본값 없이</b> 참조하므로 그 값들이 모두 있어야 한다.
 *
 * <p>그래서 {@link ContainerTestSupport}는 DB 접속 정보만이 아니라 JWT·S3·FastAPI 값까지 채운다.
 * 도입할 때 이 셋이 하나씩 순서대로 드러났다 -- 슬라이스 테스트는 전부 통과하는데
 * 이 테스트만 실패하는 형태였다. 빠진 설정을 가장 먼저 잡아내는 자리라는 뜻이다.
 *
 * <h3>이전에는 CI에서 돌지 않았다</h3>
 * 환경변수 가드로 막혀 있어 <b>빈 순환이나 설정 누락을 CI가 잡지 못했다.</b>
 * 지금은 컨테이너가 DB를 주므로 매 실행마다 돈다.
 */
@SpringBootTest
class DocKinSpringApplicationTests extends ContainerTestSupport {

	@Test
	void contextLoads() {
	}

}
