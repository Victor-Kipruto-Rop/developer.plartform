package com.pesaguard.backend.security.sessions;

import java.io.IOException;
import java.util.Optional;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 512;

    private final SessionService sessionService;
    private final RestAuthenticationEntryPoint entryPoint;

    public SessionAuthenticationFilter(SessionService sessionService, RestAuthenticationEntryPoint entryPoint) {
        this.sessionService = sessionService;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith(BEARER_PREFIX)
                || authorization.length() <= BEARER_PREFIX.length()
                || authorization.length() > MAX_TOKEN_LENGTH) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadBearerTokenException());
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            entryPoint.commence(request, response, new BadBearerTokenException());
            return;
        }

        Optional<AuthenticatedUser> principal;
        try {
            principal = sessionService.authenticate(token);
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            throw exception;
        }
        if (principal.isEmpty()) {
            entryPoint.commence(request, response, new BadBearerTokenException());
            return;
        }

        AuthenticatedUser user = principal.get();
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                user.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private static final class BadBearerTokenException extends AuthenticationException {
        private BadBearerTokenException() {
            super("The bearer token is invalid or expired");
        }
    }
}
