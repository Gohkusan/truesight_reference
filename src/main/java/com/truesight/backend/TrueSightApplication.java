package com.truesight.backend;

import com.truesight.backend.config.TrueSightProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. {@code @EnableScheduling} backs the daily re-analysis refresh (AC 8.1);
 * it is cheap to enable up front even before the scheduled job exists, since an app
 * with no {@code @Scheduled} methods just does nothing extra.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(TrueSightProperties.class)
public class TrueSightApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrueSightApplication.class, args);
    }
}
