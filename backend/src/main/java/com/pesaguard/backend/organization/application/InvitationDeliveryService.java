package com.pesaguard.backend.organization.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Sends invitation links without logging the bearer token. */
@Service
public class InvitationDeliveryService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String acceptUrl;

    public InvitationDeliveryService(
            JavaMailSender mailSender,
            @Value("${pesaguard.identity.from-email}") String fromAddress,
            @Value("${pesaguard.identity.verification-url}") String portalUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.acceptUrl = portalUrl.replaceAll("/+$", "") + "/accept-invitation";
    }

    public void send(String email, String token, String organizationName,
            String inviterName, String role, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("You have been invited to join " + organizationName);
        message.setText(inviterName + " has invited you to join " + organizationName
                + " as a " + role + ".\n\n"
                + "Accept the invitation using this private link:\n\n"
                + acceptUrl + "?token=" + token
                + "\n\nThis invitation expires on "
                + DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(expiresAt)
                + " (UTC)."
                + " If you were not expecting this invitation, ignore this email.");
        mailSender.send(message);
    }
}
