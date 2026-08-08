package com.DOCKin.global.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 검증: <b>저장소의 마이그레이션이 로컬 DB에 실제로 적용되어 있는가?</b>
 *
 * <h3>왜 필요한가 — 이틀간 밀린 것을 아무도 몰랐다</h3>
 * V3·V4가 커밋된 뒤에도 로컬 DB의 {@code flyway_schema_history}에는 V1·V2뿐이었다(P2-15-9).
 * Flyway는 앱 기동 시 자동으로 돌지만, 도는 것은 <b>그 jar 안에 든 파일까지</b>다.
 * 로컬 앱은 사람이 {@code docker build}로 만든 이미지였고 그 이미지가 옛것이었으므로,
 * Flyway는 <b>정상 동작하면서 옛 상태를 유지했다.</b> 실패가 아니라서 신호가 없었다.
 *
 * <p>{@code compose.yaml}의 {@code build:}를 되살려 이미지가 밀리는 경로는 줄였지만,
 * 그것은 <b>밀리지 않게 하는</b> 쪽이다. 이 테스트는 <b>밀렸을 때 말해 주는</b> 쪽이라
 * 역할이 겹치지 않는다.
 *
 * <h3>왜 앱이 아니라 테스트인가 — 관측 지점의 문제다</h3>
 * 이 검사를 앱 안에 넣으면 <b>같은 사고를 절대 볼 수 없다.</b> 옛 이미지의 앱 입장에서는
 * "jar에 V1·V2, DB에 V1·V2"로 완벽히 일치했다. 어긋난 것은 <b>저장소와 DB 사이</b>이고,
 * 앱은 저장소를 모른다.
 *
 * <p>즉 이 비교는 <b>작업 트리를 읽을 수 있는 쪽</b>에서만 성립한다. 그래서 테스트다.
 *
 * <h3>왜 새 환경변수 스위치를 두지 않았는가</h3>
 * 이 저장소에는 {@code DB_PASSWORD}로 켜지는 로컬 전용 테스트가 이미 여럿 있고,
 * CI 워크플로가 <b>"그 스위치가 이름과 실제가 어긋난 상태"</b>라고 스스로 적어 두었다.
 * 여기에 스위치를 하나 더 만들면 <b>"켜는 것을 사람이 기억해야 하는 장치"</b>가 되는데,
 * 그건 이 테스트가 잡으려는 병("이미지 다시 만드는 것을 사람이 기억해야 한다")과 같은 병이다.
 *
 * <p>그래서 <b>조건 자체를 스위치로 쓴다.</b>
 *
 * <ul>
 *   <li>{@code .env}가 있는가 — {@code .gitignore}에 있으므로 개발 머신에만 존재한다</li>
 *   <li>{@code localhost:5432}에 붙는가 — 로컬 DB가 떠 있을 때만 참이다</li>
 * </ul>
 *
 * 개발 머신에서는 둘 다 자연히 참이고 CI에서는 둘 다 자연히 거짓이다.
 * <b>따로 켤 것이 없다.</b>
 *
 * <h3>CI에서 건너뛰는 것이 맞다</h3>
 * CI의 DB는 Testcontainers가 매번 새로 만들므로 <b>정의상 밀릴 수 없다.</b>
 * 거기서 이 검사는 항상 통과하고, 항상 통과하는 검사는 아무것도 검증하지 않는다.
 * 이 테스트가 의미를 갖는 유일한 자리는 <b>오래 살아 있는 개발 DB</b>다.
 *
 * <p>빈 스키마에서 마이그레이션이 도는지는 {@code FlywayMigrationTest}가 본다.
 * 그쪽과 이쪽은 <b>서로 다른 것을 본다</b> — 저쪽은 SQL이 맞는가, 이쪽은 그것이 도달했는가다.
 *
 * @see com.DOCKin.rag.repository.FlywayMigrationTest
 */
class LocalMigrationDriftTest {

    /**
     * {@code .env}의 {@code DB_HOST}는 {@code compose.yaml}의 서비스 이름이라
     * 호스트에서는 해석되지 않는다. 포트가 5432로 공개되어 있으므로 여기서는 localhost로 붙는다
     * ({@code CorpusSeedGeneratorTest}, {@code BruteForceSearchBenchmarkTest}와 같은 방식).
     */
    private static final String URL = "jdbc:postgresql://localhost:5432/dockindb";

    /** 운영과 같은 위치. {@code application.properties}의 기본 {@code locations}와 같아야 한다. */
    private static final String LOCATIONS = "classpath:db/migration";

    @Test
    @DisplayName("저장소의 마이그레이션이 로컬 DB에 전부 적용되어 있다")
    void 밀린_마이그레이션이_없다() {
        Flyway flyway = localFlywayOrSkip();

        List<MigrationInfo> pending = Arrays.stream(flyway.info().pending())
                // 버전 있는 것만 본다. db/seed의 repeatable은 seed 프로파일에서만 locations에
                // 들어가므로 여기서는 애초에 해석되지 않지만, application.properties가
                // ignore-migration-patterns=repeatable:missing으로 느슨하게 푼 범위와
                // 같은 선을 긋는다 -- 두 곳이 다른 기준을 갖지 않게 한다.
                .filter(info -> info.getVersion() != null)
                .toList();

        if (pending.isEmpty()) {
            return;
        }

        fail("""
                로컬 DB에 적용되지 않은 마이그레이션이 %d개 있다: %s

                Flyway는 앱 기동 시 자동으로 돌지만 도는 것은 그 jar 안에 든 파일까지다.
                이미지가 옛것이면 Flyway는 정상 동작하면서 옛 상태를 유지한다 -- 실패가 아니므로
                이 테스트가 없으면 아무 신호도 나오지 않는다(P2-15-9).

                적용하려면:
                  ./gradlew bootJar && docker compose up -d --build dockin-app

                DB를 건드리고 싶지 않다면 컨테이너를 내려 두면 이 검사는 건너뛴다."""
                .formatted(pending.size(), versions(pending)));
    }

    @Test
    @DisplayName("로컬 DB가 저장소보다 앞서 있지 않다")
    void DB가_저장소보다_앞서지_않는다() {
        Flyway flyway = localFlywayOrSkip();

        // MISSING_SUCCESS  적용됐는데 파일이 없다 (마이그레이션을 지웠거나 옛 브랜치다)
        // FUTURE_SUCCESS   적용된 버전이 파일의 최대 버전보다 크다 (옛 브랜치로 되돌아왔다)
        //
        // 이 상태에서 앱을 띄우면 Flyway가 기동을 거부한다 --
        // "Detected applied migration not resolved locally". 실제로 그렇게 확인했다(P2-15-9).
        // 기동해 봐야 아는 것을 테스트가 먼저 말해 주는 것이 이 검사의 값이다.
        List<MigrationInfo> ahead = Arrays.stream(flyway.info().all())
                .filter(info -> info.getVersion() != null)
                .filter(info -> info.getState() == MigrationState.MISSING_SUCCESS
                        || info.getState() == MigrationState.FUTURE_SUCCESS)
                .toList();

        if (ahead.isEmpty()) {
            return;
        }

        fail("""
                로컬 DB에는 적용됐는데 저장소에 없는 마이그레이션이 %d개 있다: %s

                이 상태로는 앱이 뜨지 않는다 -- Flyway가 "Detected applied migration not
                resolved locally"로 기동을 거부한다. 옛 브랜치에 있거나 마이그레이션 파일을
                지운 것이다. 브랜치를 확인하고, 정말 되돌리려는 것이라면 flyway repair가 필요하다."""
                .formatted(ahead.size(), versions(ahead)));
    }

    private static String versions(List<MigrationInfo> infos) {
        return infos.stream()
                .map(info -> "V" + info.getVersion() + " (" + info.getDescription() + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * 로컬 DB에 붙은 Flyway를 준다. 붙을 수 없으면 <b>실패가 아니라 skip</b>이다.
     *
     * <p>{@code migrate()}가 아니라 {@code info()}만 쓴다 — 이 테스트는 상태를 <b>읽기만</b> 한다.
     * 테스트가 개발 DB의 스키마를 바꾸기 시작하면 그 자체가 사고의 원인이 된다.
     */
    private static Flyway localFlywayOrSkip() {
        Map<String, String> env = readDotEnv();
        Assumptions.assumeFalse(env.isEmpty(),
                ".env가 없어 로컬 DB 검사를 건너뜁니다 (CI에서는 정상입니다).");

        String user = env.get("DB_USERNAME");
        String password = env.get("DB_PASSWORD");
        Assumptions.assumeTrue(user != null && password != null,
                ".env에 DB_USERNAME/DB_PASSWORD가 없어 로컬 DB 검사를 건너뜁니다.");

        try (Connection probe = DriverManager.getConnection(URL, user, password)) {
            Assumptions.assumeTrue(probe.isValid(3), "로컬 DB에 붙지 못해 건너뜁니다.");
        } catch (SQLException e) {
            Assumptions.abort("로컬 DB(localhost:5432)에 접속할 수 없어 건너뜁니다: " + e.getMessage());
        }

        return Flyway.configure()
                .dataSource(URL, user, password)
                .locations(LOCATIONS)
                .load();
    }

    /**
     * 프로젝트 루트의 {@code .env}를 읽는다. 없으면 빈 맵이다.
     *
     * <p>Gradle이 테스트를 프로젝트 디렉터리에서 실행하므로 상대 경로로 닿는다.
     * 이 파일을 읽는 것이 곧 "개발 머신인가"의 판정이다 — {@code .gitignore}에 있어
     * 체크아웃만으로는 생기지 않는다.
     */
    private static Map<String, String> readDotEnv() {
        Path dotEnv = Path.of(System.getProperty("user.dir"), ".env");
        if (!Files.isRegularFile(dotEnv)) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                // 주석 처리된 키가 실제로 있다(.env의 DB_HOST). 살아 있는 줄만 읽는다.
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            return Map.of();
        }
        return values;
    }
}
