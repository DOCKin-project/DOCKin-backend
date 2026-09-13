package com.DOCKin.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// LocalDate.now()/LocalDateTime.now()를 직접 호출하면 시간 의존 로직(지각 판정 등)을
// 결정론적으로 테스트할 수 없다. Clock을 주입받아 테스트에서 Clock.fixed(...)로 대체 가능하게 한다.
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
