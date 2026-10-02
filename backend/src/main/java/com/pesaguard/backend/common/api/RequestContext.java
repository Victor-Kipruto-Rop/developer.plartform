package com.pesaguard.backend.common.api;

import java.util.UUID;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class RequestContext {

    public static final String REQUEST_ID_ATTRIBUTE = RequestContext.class.getName() + ".requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    public static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value instanceof UUID requestId ? requestId : currentRequestId();
    }

    public static UUID currentRequestId() {
        UUID requestId = CorrelationIdFilter.currentRequestId();
        return requestId == null ? UUID.randomUUID() : requestId;
    }

    public static String currentRemoteAddress() {
        return CorrelationIdFilter.currentRemoteAddress();
    }

    public static String currentUserAgent() {
        return CorrelationIdFilter.currentUserAgent();
    }
}
