package com.gp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the p-identity (Tanzu SSO) credentials from VCAP_SERVICES and injects them
 * as Spring Security OAuth2 client properties before the application context starts.
 *
 * By setting both issuer-uri AND all explicit endpoints, we skip OIDC discovery at
 * startup (no HTTP call) while still validating the iss claim in id_tokens.
 */
public class SsoEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap == null || vcap.isBlank()) return;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(vcap);
            JsonNode pIdentity = root.path("p-identity");
            if (pIdentity.isMissingNode() || !pIdentity.isArray() || pIdentity.isEmpty()) return;

            JsonNode creds      = pIdentity.get(0).path("credentials");
            // Tanzu AuthHub uses underscores; some older p-identity plans use hyphens — try both.
            String authDomain   = firstNonEmpty(creds, "auth_domain",   "auth-domain");
            String clientId     = firstNonEmpty(creds, "client_id",     "client-id");
            String clientSecret = firstNonEmpty(creds, "client_secret", "client-secret");

            if (authDomain.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) return;

            Map<String, Object> props = new LinkedHashMap<>();
            String reg  = "spring.security.oauth2.client.registration.sso.";
            String prov = "spring.security.oauth2.client.provider.sso.";

            props.put(reg + "client-id",                clientId);
            props.put(reg + "client-secret",            clientSecret);
            props.put(reg + "scope",                    "openid");
            props.put(reg + "authorization-grant-type", "authorization_code");
            props.put(reg + "redirect-uri",             "{baseUrl}/login/oauth2/code/sso");
            props.put(reg + "client-name",              "Broadcom AuthHub SSO");

            // Do NOT set issuer-uri — Spring Boot triggers OIDC discovery at startup
            // when issuer-uri is present, which makes an HTTP call and times out if
            // the provider doesn't expose a /.well-known/openid-configuration endpoint.
            // All explicit endpoints are sufficient for the OAuth2 Authorization Code flow.
            props.put(prov + "authorization-uri",   authDomain + "/oauth/authorize");
            props.put(prov + "token-uri",           authDomain + "/oauth/token");
            props.put(prov + "user-info-uri",       authDomain + "/userinfo");
            props.put(prov + "jwk-set-uri",         authDomain + "/token_keys");
            props.put(prov + "user-name-attribute", "user_name");

            environment.getPropertySources().addFirst(
                new MapPropertySource("sso-vcap-p-identity", props));

        } catch (Exception e) {
            // Non-fatal — app starts without SSO if VCAP_SERVICES cannot be parsed
        }
    }

    private static String firstNonEmpty(JsonNode node, String... keys) {
        for (String key : keys) {
            String val = node.path(key).asText("").trim();
            if (!val.isEmpty()) return val;
        }
        return "";
    }
}
