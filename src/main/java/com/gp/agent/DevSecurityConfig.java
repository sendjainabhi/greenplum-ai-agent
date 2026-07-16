package com.gp.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Permissive security for local development (non-cloud profile).
 * All requests are allowed without authentication. SSO is not available locally.
 */
@Configuration
@Profile("!cloud")
public class DevSecurityConfig {

    @Bean
    SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/**"))
            .headers(headers -> headers.frameOptions(fo -> fo.sameOrigin()))
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );
        return http.build();
    }
}
