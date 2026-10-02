package com.pesaguard.backend.security.authentication;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;

@Component
public class PasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_UTF8_BYTES = 72;

    public void validate(String password, String email) {
        validate(password, email, MIN_LENGTH, MAX_UTF8_BYTES);
    }

    public void validate(String password, String email, int minimumLength, int maximumUtf8Bytes) {
        int codePointCount = password.codePointCount(0, password.length());
        int utf8Bytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int effectiveMinimum = Math.max(MIN_LENGTH, minimumLength);
        int effectiveMaximum = Math.min(MAX_UTF8_BYTES, maximumUtf8Bytes);
        if (codePointCount < effectiveMinimum || utf8Bytes > effectiveMaximum) {
            throw invalid();
        }
        String localPart = email.substring(0, email.indexOf('@'));
        if (password.equalsIgnoreCase(email) || password.equalsIgnoreCase(localPart)) {
            throw invalid();
        }
    }

    public void validateMaximumLength(String password) {
        validateMaximumLength(password, MAX_UTF8_BYTES);
    }

    public void validateMaximumLength(String password, int maximumUtf8Bytes) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > Math.min(MAX_UTF8_BYTES, maximumUtf8Bytes)) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                "WEAK_PASSWORD",
                "Password does not meet the organization credential policy.");
    }
}
