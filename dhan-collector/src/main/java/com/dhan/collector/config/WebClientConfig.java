package com.dhan.collector.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final DhanProperties properties;
    private final RateLimiterRegistry rateLimiterRegistry;

    @Bean
    public WebClient dhanWebClient() {
        return WebClient.builder()
                .baseUrl(properties.getApi().getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("access-token", properties.getApi().getAccessToken())
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB buffer
                .build();
    }

    @Bean
    public RateLimiter dhanRateLimiter() {
        return rateLimiterRegistry.rateLimiter("dhan-api");
    }
}
