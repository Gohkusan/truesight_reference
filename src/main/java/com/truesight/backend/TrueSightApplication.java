package com.truesight.backend;

import com.truesight.backend.config.TrueSightProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. {@code @EnableScheduling} backs the daily re-analysis refresh (AC 8.1);
 * it is cheap to enable up front even before the scheduled job exists, since an app
 * with no {@code @Scheduled} methods just does nothing extra.
 *
 * <p>UserDetailsServiceAutoConfiguration is explicitly excluded: it fires whenever
 * spring-boot-starter-security is on the classpath and no UserDetailsService bean
 * exists, generating a random throwaway login and printing it to the console on every
 * boot. This app never uses Spring Security's UserDetailsService/form-login machinery
 * — auth is entirely our own JWT issuance in AuthService — so that auto-configuration
 * has nothing to do here except produce a misleading log line suggesting there's a
 * form-login username/password to use.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(TrueSightProperties.class)
public class TrueSightApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrueSightApplication.class, args);
    }
}
