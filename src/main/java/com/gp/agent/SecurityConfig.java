package com.gp.agent;

import com.gp.agent.db.AgentDao;
import com.gp.agent.db.AuditService;
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

import java.util.Arrays;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@Profile("cloud")
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final AgentDao agentDao;
    private final AuditService auditService;

    public SecurityConfig(AgentDao agentDao, Optional<AuditService> auditService) {
        this.agentDao     = agentDao;
        this.auditService = auditService.orElse(null);
    }

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
                    if (auth != null && auth.getPrincipal() instanceof OidcUser oidcUser) {
                        String email = oidcUser.getAttribute("user_name");
                        if (email == null || email.isBlank()) email = oidcUser.getEmail();
                        if (email != null && auditService != null) auditService.log(email, "LOGOUT");
                    }
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

            if (!isPermanentAdmin(email) && !agentDao.isUserAllowed(email)) {
                log.warn("[SSO] Access denied for user: {}", email);
                throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied",
                        "You are not authorized to access this application.", null));
            }

            log.info("[SSO] Access granted for {}", email);
            agentDao.addKnownUser(email);
            if (auditService != null) auditService.log(email, "LOGIN");
            return user;
        };
    }

    private static boolean isPermanentAdmin(String email) {
        String env = System.getenv("PERMANENT_ADMIN_EMAILS");
        if (env == null || env.isBlank()) return false;
        return Arrays.stream(env.split(","))
                .map(String::trim)
                .anyMatch(e -> e.equalsIgnoreCase(email));
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
}
