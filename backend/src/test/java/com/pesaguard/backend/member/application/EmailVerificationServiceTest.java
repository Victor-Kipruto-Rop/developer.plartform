package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.security.TestProperties;

class EmailVerificationServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void deliversSixDigitOtpAndConfirmsIt() {
        Fixture fixture = fixtureAt(ISSUED_AT);
        fixture.service().issue(fixture.account());

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        org.mockito.Mockito.verify(fixture.mailSender()).send(message.capture());
        String code = message.getValue().getText().replaceAll("(?s).*code is: (\\d{6}).*", "$1");

        when(fixture.accountRepository().findByEmailForUpdate("developer@example.com"))
                .thenReturn(Optional.of(fixture.account()));
        UserAccount confirmed = fixture.service().confirm("developer@example.com", code);

        assertThat(confirmed.isEmailVerified()).isTrue();
        assertThat(code).hasSize(6).matches("\\d{6}");
        assertThat(message.getValue().getText()).contains("expires in 10 minutes");
    }

    @Test
    void rejectsExpiredVerificationToken() {
        Fixture issued = fixtureAt(ISSUED_AT);
        issued.service().issue(issued.account());

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        org.mockito.Mockito.verify(issued.mailSender()).send(message.capture());
        String code = message.getValue().getText().replaceAll("(?s).*code is: (\\d{6}).*", "$1");
        when(issued.accountRepository().findByEmailForUpdate("developer@example.com"))
                .thenReturn(Optional.of(issued.account()));

        EmailVerificationService expiredService = service(
                issued.accountRepository(), issued.mailSender(), ISSUED_AT.plusSeconds(11 * 60));
        assertThatThrownBy(() -> expiredService.confirm("developer@example.com", code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("code has expired");
        assertThat(issued.account().isEmailVerified()).isFalse();
    }

    @Test
    void wrongCodeIsRejectedAndCountsAgainstTheAttemptLimit() {
        Fixture fixture = fixtureAt(ISSUED_AT);
        fixture.service().issue(fixture.account());
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        org.mockito.Mockito.verify(fixture.mailSender()).send(message.capture());
        String issuedCode = message.getValue().getText().replaceAll("(?s).*code is: (\\d{6}).*", "$1");
        String wrongCode = issuedCode.equals("000000") ? "000001" : "000000";
        when(fixture.accountRepository().findByEmailForUpdate("developer@example.com"))
                .thenReturn(Optional.of(fixture.account()));

        assertThatThrownBy(() -> fixture.service().confirm("developer@example.com", wrongCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("incorrect");

        assertThat(fixture.account().getEmailVerificationAttempts()).isEqualTo(1);
        assertThat(fixture.account().isEmailVerified()).isFalse();
    }

    @Test
    void loginChallengeReturnsTheExistingCodesExpiryAndResendWindow() {
        Fixture fixture = fixtureAt(ISSUED_AT);
        EmailVerificationService.VerificationChallenge issued = fixture.service().issue(fixture.account());
        when(fixture.accountRepository().findByEmailForUpdate("developer@example.com"))
                .thenReturn(Optional.of(fixture.account()));

        EmailVerificationService.VerificationChallenge challenge =
                fixture.service().issueIfCooldownElapsed("developer@example.com");

        assertThat(challenge).isEqualTo(issued);
        assertThat(challenge.issuedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.expiresAt()).isEqualTo(ISSUED_AT.plusSeconds(10 * 60));
        assertThat(challenge.resendAvailableAt()).isEqualTo(ISSUED_AT.plusSeconds(60));
        org.mockito.Mockito.verify(fixture.mailSender()).send(any(SimpleMailMessage.class));
    }

    private Fixture fixtureAt(Instant now) {
        UserAccountRepository accountRepository = mock(UserAccountRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        UserAccount account = UserAccount.create("developer@example.com", "Developer", "password-hash");
        when(accountRepository.saveAndFlush(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new Fixture(accountRepository, mailSender, account,
                service(accountRepository, mailSender, now));
    }

    private EmailVerificationService service(
            UserAccountRepository accountRepository,
            JavaMailSender mailSender,
            Instant now) {
        return new EmailVerificationService(
                accountRepository,
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                Clock.fixed(now, ZoneOffset.UTC),
                mailSender,
                TestProperties.platform(),
                "no-reply@example.com");
    }

    private record Fixture(
            UserAccountRepository accountRepository,
            JavaMailSender mailSender,
            UserAccount account,
            EmailVerificationService service) {
    }
}
