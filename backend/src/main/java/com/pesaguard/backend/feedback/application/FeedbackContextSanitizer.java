package com.pesaguard.backend.feedback.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.feedback.api.FeedbackContextRequest;

@Component
public class FeedbackContextSanitizer {

    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(?:authorization|cookie|password|passwd|secret|token|api[_-]?key|credential|signature)\\s*[:=]");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)\\b(?:Bearer\\s+[A-Za-z0-9._~+/=-]+|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|(?:pg_(?:test|live)_|whsec_)[A-Za-z0-9_-]{12,})\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    public SanitizedContext sanitize(FeedbackContextRequest context) {
        if (context == null) {
            return SanitizedContext.empty();
        }
        rejectSecrets(context.pageUrl(), "page URL");
        rejectSecrets(context.route(), "route");
        rejectSecrets(context.browser(), "browser");
        rejectSecrets(context.operatingSystem(), "operating system");
        rejectSecrets(context.applicationVersion(), "application version");
        rejectSecrets(context.frontendVersion(), "frontend version");
        rejectSecrets(context.requestId(), "request identifier");
        rejectSecrets(context.correlationId(), "correlation identifier");

        return new SanitizedContext(stripQueryAndFragment(context.pageUrl(), "page URL"),
                stripQueryAndFragment(context.route(), "route"),
                clean(context.browser()), clean(context.operatingSystem()),
                clean(context.applicationVersion()), clean(context.frontendVersion()),
                safeIdentifier(context.requestId(), "request identifier"),
                safeIdentifier(context.correlationId(), "correlation identifier"));
    }

    private static void rejectSecrets(String value, String field) {
        if (value != null && (SECRET_FIELD.matcher(value).find() || SECRET_VALUE.matcher(value).find())) {
            throw invalidContext("Sensitive values are not accepted in feedback " + field + ".");
        }
    }

    private static String stripQueryAndFragment(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        try {
            URI uri = new URI(cleaned);
            if (uri.getRawUserInfo() != null
                    || (uri.isAbsolute() && !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())))
                    || (uri.isAbsolute() && uri.getHost() == null)) {
                throw invalidContext("Feedback " + field + " must not contain credentials or an unsafe scheme.");
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = uri.isAbsolute() ? "/" : cleaned.startsWith("/") ? "/" : "";
            }
            if (uri.isAbsolute()) {
                return uri.getScheme().toLowerCase() + "://" + uri.getRawAuthority() + path;
            }
            return path;
        } catch (URISyntaxException invalid) {
            throw invalidContext("Feedback " + field + " is invalid.");
        }
    }

    private static String safeIdentifier(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        if (!IDENTIFIER.matcher(cleaned).matches()) {
            throw invalidContext("Feedback " + field + " contains unsupported characters.");
        }
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static BusinessException invalidContext(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FEEDBACK_CONTEXT", message);
    }

    public record SanitizedContext(String pageUrl, String route, String browser,
            String operatingSystem, String applicationVersion, String frontendVersion,
            String requestId, String correlationId) {
        static SanitizedContext empty() {
            return new SanitizedContext(null, null, null, null, null, null, null, null);
        }
    }
}
