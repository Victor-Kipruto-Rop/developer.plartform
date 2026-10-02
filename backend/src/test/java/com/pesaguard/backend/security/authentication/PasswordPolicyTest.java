package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPassphrase() {
        assertThatCode(() -> policy.validate("correct horse battery staple", "person@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortPasswordAndEmailEquality() {
        assertThatThrownBy(() -> policy.validate("short", "person@example.com"))
                .hasMessageContaining("Password");
        assertThatThrownBy(() -> policy.validate("person@example.com", "person@example.com"))
                .hasMessageContaining("Password");
    }
}
