package com.habitflow.habits.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {
    
    @Bean
    public Counter habitsCreatedCounter(MeterRegistry registry) {
        return Counter.builder("habits.created")
                .description("Total number of habits created")
                .tag("service", "habit-service")
                .register(registry);
    }
    
    @Bean
    public Counter habitsCompletedCounter(MeterRegistry registry) {
        return Counter.builder("habits.completed")
                .description("Total number of habit completions")
                .tag("service", "habit-service")
                .register(registry);
    }
    
    @Bean
    public Counter habitsDeletedCounter(MeterRegistry registry) {
        return Counter.builder("habits.deleted")
                .description("Total number of habits deleted")
                .tag("service", "habit-service")
                .register(registry);
    }
}