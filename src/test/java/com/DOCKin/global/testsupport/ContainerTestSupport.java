package com.DOCKin.global.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 앱이 뜨는 데 필요한 인프라 컨테이너를 <b>단 한 번</b> 띄우고 모두가 공유한다.
 *
 * <h3>왜 필요한가 — CI가 아무것도 검증하지 않고 있었다</h3>
 * DB가 필요한 테스트 13개가 전부 {@code DB_PASSWORD} 부재로 스스로 skip했다.
 * 즉 <b>스키마 검증도, Flyway도, 벡터 매핑도, 락 동작도 CI에서 한 번도 확인된 적이 없다.</b>
 * 테스트가 1개에서 20개로 늘어나는 동안 그랬다(백로그 P0-7).
 *
 * <p>"측정으로 결정한다"를 표방하는 저장소에서 <b>검증이 자동화되어 있지 않은 것</b>은
 * 그 자체로 모순이다. 이 클래스가 그것을 없앤다.
 *
 * <h3>왜 pgvector 이미지인가</h3>
 * 공식 {@code postgres} 이미지에는 pgvector 확장이 없다. {@code V1__pgvector_and_document_chunks.sql}이
 * {@code CREATE EXTENSION vector}로 시작하므로 <b>Flyway가 첫 줄에서 실패한다.</b>
 * 운영과 같은 {@code pgvector/pgvector:pg17}을 쓰면 그 문제가 없고,
 * 동시에 <b>CI가 운영과 같은 DB에서 검증</b>하게 된다.
 *
 * <p>{@code asCompatibleSubstituteFor("postgres")}가 필요한 이유는 Testcontainers가
 * 이미지 이름으로 호환성을 검사하기 때문이다. 없으면 실행 시점에 거부당한다.
 *
 * <h3>왜 static 초기화인가 — 컨테이너는 하나여야 한다</h3>
 * {@code @Testcontainers} + {@code @Container}를 인스턴스 필드로 쓰면 <b>테스트 클래스마다
 * 컨테이너가 새로 뜬다.</b> 이 저장소는 기동 시 Flyway가 23개 테이블을 만들므로
 * 클래스 수만큼 그 비용이 반복된다.
 *
 * <p>static 블록에서 한 번 시작하고 <b>멈추지 않는다.</b> Testcontainers의 Ryuk 컨테이너가
 * JVM 종료를 감지해 정리하므로 직접 끄는 것보다 안전하다 — 테스트가 중간에 죽어도 남지 않는다.
 *
 * <h3>비-DB 설정도 여기서 준다</h3>
 * {@code application.properties}가 {@code JWT_SECRET}, {@code AI_SERVER_URL} 등을
 * <b>기본값 없이</b> 참조한다. DB만 줘서는 컨텍스트가 뜨지 않으므로 함께 채운다.
 * 별도 프로파일이나 테스트용 {@code application.properties}를 두지 않은 것은,
 * <b>테스트 클래스패스의 같은 이름 파일이 운영 설정을 통째로 가려</b> 무엇이 적용됐는지
 * 알기 어려워지기 때문이다. 여기 모아두면 한 곳만 보면 된다.
 *
 * <h3>왜 Redis도 여기 있는가 — 이름이 {@code PostgresTestSupport}로 충분하던 때가 끝났다</h3>
 * P2-11-5가 DB를 컨테이너로 옮긴 뒤, {@code @SpringBootTest} 두 개
 * ({@code DocKinSpringApplicationTests}, {@code ActuatorEndpointTest})가 <b>전체 컨텍스트</b>를
 * 요구하게 됐다. 그 컨텍스트에는 {@code RedissonConfig}가 있고 그것은 빈 생성 시점에
 * {@code localhost:6379}로 <b>실제로 접속한다.</b> DB만 주고 Redis를 안 주면 컨텍스트가
 * 뜨지 않는다 — CI가 2026-08-07부터 그 이유로 빨간불이었다(백로그 P2-16).
 *
 * <p>즉 이 클래스가 주는 것은 "테스트용 DB"가 아니라 <b>"앱이 뜨는 데 필요한 인프라"</b>다.
 * 이름을 {@code ContainerTestSupport}로 바꾼 것은 그래서다.
 */
public abstract class ContainerTestSupport {

    /** 운영과 같은 이미지. 태그를 고정하는 것은 CI 결과가 재현 가능해야 하기 때문이다. */
    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

    /** {@code compose.yaml}의 DOCKin-Redis와 같은 이미지. 여기서도 운영과 맞춘다. */
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static final int REDIS_PORT = 6379;

    protected static final PostgreSQLContainer<?> POSTGRES;

    /**
     * 분산락과 컨텍스트 기동에 필요하다. <b>설정을 주지 않는다</b> —
     * {@code compose.yaml}의 Redis도 기본 설정으로 뜨고, 이 저장소가 Redis에 기대하는 것은
     * 출근 분산락의 {@code SETNX} 수준이라 튜닝할 파라미터가 없다. DB 쪽에
     * {@code lock_timeout} 등을 준 것과 대비되는데, 그건 그 값이 없으면 테스트가
     * <b>실패가 아니라 멈추기</b> 때문이었다. 여기는 그런 것이 없다.
     */
    protected static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("dockindb")
                .withUsername("test")
                .withPassword("test")
                // compose.yaml의 DOCKin-DB와 같은 서버 파라미터. 이미지만 같고 파라미터가
                // 다르면 "운영과 같은 DB에서 검증한다"가 성립하지 않는다.
                .withCommand("postgres",
                        // 없으면 LockTimeoutVerificationTest가 실패가 아니라 '멈춘다'.
                        // 기본값 0은 무한 대기라 락을 쥔 커넥션을 영원히 기다린다.
                        // GitHub 서비스 컨테이너에 이 인자를 줄 자리가 없다는 것이
                        // P0-7이 CI에 DB를 주지 않기로 한 두 이유 중 하나였고, 여기서 해소된다.
                        "-c", "lock_timeout=5s",
                        "-c", "deadlock_timeout=1s",
                        "-c", "shared_preload_libraries=pg_stat_statements")
                // 스키마는 Flyway가 만든다. 컨테이너에 초기화 스크립트를 주면
                // 마이그레이션이 검증되지 않으므로 일부러 비워 둔다.
                .withReuse(false);
        POSTGRES.start();

        REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);
        REDIS.start();

        // shared_preload_libraries는 라이브러리를 적재할 뿐이고, 뷰를 쓰려면 확장을 만들어야 한다.
        // 없으면 HibernateBatchInsertVerificationTest가 시퀀스 호출 횟수를 관측하지 못한 채
        // 조용히 통과한다 -- 그 테스트의 존재 이유가 사라지므로 실패로 처리한다.
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "pg_stat_statements 확장을 만들지 못했다 - 배치 검증이 무의미해진다", e);
        }

        // raw JDBC로 직접 붙는 테스트들이 읽어 간다.
        // 그쪽은 스프링 컨텍스트가 없어 @DynamicPropertySource가 닿지 않는다.
        System.setProperty("test.datasource.url", POSTGRES.getJdbcUrl());
        System.setProperty("test.datasource.username", POSTGRES.getUsername());
        System.setProperty("test.datasource.password", POSTGRES.getPassword());
    }

    /**
     * 스프링이 읽을 값. {@code application.properties}의 {@code ${DB_HOST}} 계열을
     * <b>해석하는 대신 덮어쓴다</b> — 동적 프로퍼티가 우선순위가 높으므로 환경변수가 없어도 된다.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // application.properties의 ${REDIS_HOST:localhost} / ${REDIS_PORT:6379}를 덮어쓴다.
        // 기본값이 localhost:6379라 이걸 안 주면 "안 뜬다"가 아니라 "런너의 6379에 붙으려 든다" --
        // CI에 그런 것이 없으므로 Connection refused로 컨텍스트가 죽는다.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));

        // 컨텍스트 로딩에만 필요한 값들. 실제로 쓰이지 않으므로 형식만 맞춘다.
        // 다만 jwt.secret은 두 가지를 동시에 만족해야 한다 --
        // JwtUtil이 Decoders.BASE64.decode()로 먼저 푸므로 유효한 Base64여야 하고
        // (하이픈 같은 문자가 있으면 DecodingException),
        // 풀린 결과가 HS256 최소 길이인 32바이트 이상이어야 한다(Keys.hmacShaKeyFor).
        // 아래 값은 "testonly"를 6번 이은 48바이트를 Base64로 인코딩한 것이다.
        registry.add("jwt.secret", () ->
                "dGVzdG9ubHl0ZXN0b25seXRlc3Rvbmx5dGVzdG9ubHl0ZXN0b25seXRlc3Rvbmx5");
        registry.add("jwt.expiration_time", () -> "3600000");
        registry.add("jwt.refresh_expiration_time", () -> "86400000");
        registry.add("external-api.fastapi.base-url", () -> "http://localhost:9999");

        // S3Config는 @DataJpaTest 슬라이스에는 안 뜨지만 @SpringBootTest 전체 컨텍스트에는 뜬다.
        // 자격증명은 빈 생성 시점에 검증되지 않으므로 아무 값이나 되지만,
        // region은 withRegion()이 이름을 해석하므로 실재하는 리전이어야 한다.
        registry.add("AWS_ACCESS_KEY", () -> "test-access-key");
        registry.add("AWS_SECRET_KEY", () -> "test-secret-key");
        registry.add("AWS_REGION", () -> "ap-northeast-2");
        registry.add("S3_BUCKET_NAME", () -> "test-bucket");
    }

    /** raw JDBC 테스트가 쓰는 접속 정보. static 초기화를 강제하려고 이 클래스를 거치게 한다. */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /**
     * 같은 컨테이너의 <b>다른 데이터베이스</b>로 붙는 URL.
     *
     * <p>마이그레이션을 빈 DB에서 처음부터 검증하려면 별도 데이터베이스가 필요하다
     * ({@code FlywayMigrationTest}). 그러려면 관리용 {@code postgres} DB에 붙어
     * {@code CREATE DATABASE}를 던져야 하는데, {@code getJdbcUrl()}은 고정된 이름을 주므로
     * 포트만 빌려 다시 조립한다.
     */
    public static String jdbcUrlFor(String databaseName) {
        return "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getFirstMappedPort() + "/" + databaseName;
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    /**
     * 컨테이너로 붙는 raw JDBC 연결.
     *
     * <p>동시성·락 검증은 <b>스프링이 관리하지 않는 두 번째 연결</b>이 있어야 성립한다 —
     * 같은 트랜잭션 안에서는 자기 락에 걸리지 않기 때문이다. 그런 테스트들이 쓴다.
     *
     * <p>호출자가 닫아야 한다.
     */
    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }
}
