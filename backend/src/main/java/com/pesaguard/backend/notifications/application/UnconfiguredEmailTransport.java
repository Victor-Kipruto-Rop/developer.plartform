package com.pesaguard.backend.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

/**
 * Email transport.
 *
 * <p><b>No email is sent.</b> There is no SMTP dependency, no mail
 * configuration, and no provider in this build, so this reports every attempt as
 * failed rather than pretending to have delivered.
 *
 * <p>That is the whole point of the design. An implementation that logged the
 * mail and returned success would make a revoked-credential notification look
 * delivered to the developer, to the audit log, and to any test asserting on
 * delivery state — while no email had left the building. A notification subsystem
 * that lies about delivery is worse than one that does not exist, because it
 * removes the evidence that the message was never sent.
 *
 * <p>Wiring a real transport means replacing this class. Nothing else changes:
 * the retry policy, per-channel state, and preference handling are transport
 * agnostic.
 */
@Component
public class UnconfiguredEmailTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredEmailTransport.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryAttemptResult send(Notification notification, String recipient) {
        // Logged without the recipient address: a notification body plus a user's
        // email is exactly the pairing that must not end up in a log aggregator.
        log.warn("email transport is not configured; notification type={} notificationId={} "
                + "was NOT delivered", notification.type(), notification.id());
        return DeliveryAttemptResult.transientFailure(
                "email transport is not configured");
    }
}