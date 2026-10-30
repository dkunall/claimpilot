package com.claimpilot.shared.config;

import com.claimpilot.product.domain.rating.RatingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RatingEngine ratingEngine() {
        return RatingEngine.standard();
    }
}
