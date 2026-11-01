package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({CartProperties.class, IdempotencyProperties.class, PaymentProperties.class,
        MessagingRetentionProperties.class, CancellationProperties.class})
public class ApplicationConfiguration {

    /** Injected so time-dependent logic stays deterministic in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
