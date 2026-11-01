package com.restofy.waiter.infrastructure.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RestClientProperties.class, ServiceUrlProperties.class})
public class RestClientConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient serviceHttpClient(RestClientProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
    }

    @Bean
    RestClientCustomizer serviceRestClientCustomizer(@Qualifier("serviceHttpClient") HttpClient httpClient,
                                                   RestClientProperties properties) {
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return builder -> builder.requestFactory(factory);
    }
}
