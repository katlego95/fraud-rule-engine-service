package com.fraudengine.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /**
     * Injected everywhere a timestamp is needed so that tests substitute a fixed clock instead of
     * racing wall time. Velocity windows read the event's own occurredAt, never this clock; this
     * exists for evaluation timestamps, which must still be replayable.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
