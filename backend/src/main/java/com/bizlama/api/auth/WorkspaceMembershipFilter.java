package com.bizlama.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the workspace boundary once for every operational API request. */
final class WorkspaceMembershipFilter extends OncePerRequestFilter {

    private final WorkspaceAccessPolicy accessPolicy;

    WorkspaceMembershipFilter(WorkspaceAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        boolean apiPath = "/api".equals(path) || path.startsWith("/api/");
        boolean authPath = "/api/auth".equals(path)
                || path.startsWith("/api/auth/");
        return !apiPath || authPath;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException(
                    "A JWT identity is required for workspace access."
            );
        }

        accessPolicy.authorizeConfiguredWorkspace(jwt);
        filterChain.doFilter(request, response);
    }
}
