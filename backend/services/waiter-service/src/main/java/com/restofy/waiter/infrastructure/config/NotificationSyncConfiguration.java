package com.restofy.waiter.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Clock for audit timestamps and retry scheduling, the background policies and the background triggers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationDispatcherProperties.class, TableCommandReconcilerProperties.class,
        CallRateLimitProperties.class})
@EnableScheduling
public class NotificationSyncConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
