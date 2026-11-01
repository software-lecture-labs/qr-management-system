package com.restofy.qr.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class QrClockConfiguration {
    @Bean
    Clock qrClock() {
        return Clock.systemUTC();
    }
}
