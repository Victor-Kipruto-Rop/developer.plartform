package com.pesaguard.backend.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

/** Delivers plain-text notification email through the configured SMTP relay. */
@Component
public class SmtpEmailTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailTransport.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailTransport(JavaMailSender mailSender,
            @Value("${pesaguard.identity.from-email}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryAttemptResult send(Notification notification, String recipient) {
        if (!validAddress(recipient)) {
            return DeliveryAttemptResult.permanentFailure("invalid address");
        }
        if (containsLineBreak(notification.subject())) {
            return DeliveryAttemptResult.permanentFailure("invalid subject");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient.trim());
        message.setSubject(notification.subject());
        message.setText(notification.body());
        try {
            mailSender.send(message);
            return DeliveryAttemptResult.delivered();
        } catch (MailException failure) {
            // Keep addresses, subjects, bodies and provider response text out of logs.
            log.warn("notification email delivery failed type={} notificationId={} cause={}",
                    notification.type(), notification.id(), failure.getClass().getSimpleName());
            return DeliveryAttemptResult.transientFailure("email delivery failed");
        }
    }

    private static boolean validAddress(String address) {
        if (address == null || address.isBlank() || containsLineBreak(address)) {
            return false;
        }
        int at = address.indexOf('@');
        return at > 0 && at == address.lastIndexOf('@') && at < address.length() - 1
                && address.indexOf(' ') < 0;
    }

    private static boolean containsLineBreak(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }
}
