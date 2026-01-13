package com.habitflow.auth.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {
    
    @Bean
    public Counter userRegistrationCounter(MeterRegistry registry) {
        return Counter.builder("auth.user.registrations")
                .description("Total number of user registrations")
                .tag("service", "auth-service")
                .register(registry);
    }
    
    @Bean
    public Counter userLoginCounter(MeterRegistry registry) {
        return Counter.builder("auth.user.logins")
                .description("Total number of user logins")
                .tag("service", "auth-service")
                .register(registry);
    }
    
    @Bean
    public Counter userLoginFailedCounter(MeterRegistry registry) {
        return Counter.builder("auth.user.logins.failed")
                .description("Total number of failed login attempts")
                .tag("service", "auth-service")
                .register(registry);
    }
}