package com.gp.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Prevent browsers from caching the main HTML page so version-bumped
        // JS/CSS query params always cause a fresh fetch.
        String uri = request.getRequestURI();
        if (uri.equals("/") || uri.equals("/index.html")) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
        }

        chain.doFilter(request, response);
    }
}
