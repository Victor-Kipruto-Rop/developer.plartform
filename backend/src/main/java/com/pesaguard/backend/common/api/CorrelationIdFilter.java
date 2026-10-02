package com.pesaguard.backend.common.api;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "request_id";
    private static final String REMOTE_ADDRESS_MDC_KEY = "remote_address";
    private static final String USER_AGENT_MDC_KEY = "user_agent";
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID requestId = parseOrCreate(request.getHeader(RequestContext.REQUEST_ID_HEADER));
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(RequestContext.REQUEST_ID_HEADER, requestId.toString());
        MDC.put(MDC_KEY, requestId.toString());
        MDC.put(REMOTE_ADDRESS_MDC_KEY, truncate(request.getRemoteAddr(), MAX_IP_LENGTH));
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null) {
            MDC.put(USER_AGENT_MDC_KEY, truncate(userAgent, MAX_USER_AGENT_LENGTH));
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(REMOTE_ADDRESS_MDC_KEY);
            MDC.remove(USER_AGENT_MDC_KEY);
        }
    }

    static UUID currentRequestId() {
        String value = MDC.get(MDC_KEY);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String currentRemoteAddress() {
        return MDC.get(REMOTE_ADDRESS_MDC_KEY);
    }

    static String currentUserAgent() {
        return MDC.get(USER_AGENT_MDC_KEY);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private UUID parseOrCreate(String value) {
        if (StringUtils.hasText(value)) {
            try {
                return UUID.fromString(value.trim());
            } catch (IllegalArgumentException ignored) {
                // Generate a trusted identifier rather than reflecting arbitrary client input.
            }
        }
        return UUID.randomUUID();
    }
}
