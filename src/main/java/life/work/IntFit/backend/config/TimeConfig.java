package life.work.IntFit.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Central time source for business-date calculations.
 *
 * The business timezone is Asia/Hebron (already the authoritative zone used by
 * BankCheck logic). We expose a Clock bean so services can compute the current
 * business date via LocalDate.now(clock) and remain unit-testable with a fixed
 * Clock. The zone observes DST, so any offset must be derived from the zone,
 * never hardcoded.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hebron");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
