package com.gp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@Configuration
@EnableWebSecurity
@Profile("cloud")
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/css/**", "/js/**", "/img/**", "/images/**",
                    "/favicon.ico", "/error", "/access-denied.html"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo
                    .oidcUserService(oidcUserService())
                )
                .defaultSuccessUrl("/", true)
                .failureUrl("/access-denied.html?error=true")
            )
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessHandler((req, res, auth) -> {
                    String authDomain = readAuthDomain();
                    res.sendRedirect(authDomain != null ? authDomain + "/logout.do" : "/");
                })
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**")
            );
        return http.build();
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            OidcUser user  = delegate.loadUser(userRequest);
            String email   = user.getAttribute("user_name");
            if (email == null || email.isBlank()) email = user.getEmail();
            if (email == null) email = user.getName();

            log.info("[SSO] Login attempt by {}", email);

            if (!isUserAllowed(email)) {
                log.warn("[SSO] Access denied for user: {}", email);
                throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied",
                        "You are not authorized to access this application.", null));
            }

            log.info("[SSO] Access granted for {}", email);
            recordKnownUser(email);
            return user;
        };
    }

    /** Reads auth_domain from VCAP_SERVICES p-identity credentials using simple string scan. */
    private static String readAuthDomain() {
        try {
            String vcap = System.getenv("VCAP_SERVICES");
            if (vcap == null) return null;
            int idx = vcap.indexOf("\"auth_domain\"");
            if (idx < 0) return null;
            int colon = vcap.indexOf(':', idx);
            int q1    = vcap.indexOf('"', colon + 1) + 1;
            int q2    = vcap.indexOf('"', q1);
            String domain = vcap.substring(q1, q2).trim();
            return domain.isEmpty() ? null : domain;
        } catch (Exception e) {
            log.debug("[SSO] Could not read auth_domain: {}", e.getMessage());
            return null;
        }
    }

    /** Appends email to known-users.txt so admins can pick from a list of real users. */
    private static void recordKnownUser(String email) {
        try {
            File f = new File(GreenplumAgentApplication.resolveDataDir(), "known-users.txt");
            List<String> existing = f.exists()
                ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)
                : new java.util.ArrayList<>();
            String norm = email.trim().toLowerCase();
            boolean alreadyKnown = existing.stream().anyMatch(l -> l.trim().equalsIgnoreCase(norm));
            if (!alreadyKnown) {
                existing.add(norm);
                Files.write(f.toPath(), existing, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("[SSO] Could not record known user: {}", e.getMessage());
        }
    }

    /**
     * Checks whether the given email is in the file-based allowlist.
     * File: {AGENT_DATA_DIR}/allowed-users.txt — one email per line, # = comment.
     * If the file does not exist or is empty, all authenticated users are allowed.
     */
    static boolean isUserAllowed(String email) {
        if (email == null || email.isBlank()) return false;
        File allowlistFile = new File(GreenplumAgentApplication.resolveDataDir(), "allowed-users.txt");
        if (!allowlistFile.exists()) return true;
        try {
            List<String> lines = Files.readAllLines(allowlistFile.toPath(), StandardCharsets.UTF_8);
            boolean hasEntries = lines.stream()
                .anyMatch(l -> !l.trim().isEmpty() && !l.trim().startsWith("#"));
            if (!hasEntries) return true;
            return lines.stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .anyMatch(l -> l.equalsIgnoreCase(email));
        } catch (Exception e) {
            log.warn("[SSO] Could not read allowed-users.txt, failing open: {}", e.getMessage());
            return true;
        }
    }
}
