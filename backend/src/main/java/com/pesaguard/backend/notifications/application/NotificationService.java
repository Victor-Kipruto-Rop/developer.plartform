package com.pesaguard.backend.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.DeliveryState;
import com.pesaguard.backend.notifications.domain.NotificationCategory;
import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationPreferences;
import com.pesaguard.backend.notifications.domain.NotificationRetryPolicy;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.notifications.infrastructure.NotificationPreferenceEntity;
import com.pesaguard.backend.notifications.infrastructure.NotificationPreferenceRepository;
import com.pesaguard.backend.notifications.infrastructure.NotificationRepository;

/**
 * Raises and delivers notifications.
 *
 * <p>Delivery is attempted inline rather than on a queue thread, because a
 * notification is a side effect of something the user is already waiting on, and
 * most sends either succeed or fail immediately. Retries are the part that is
 * genuinely deferred.
 *
 * <p>Every attempt is recorded on the notification, so "was I told?" is always
 * answerable and a failure is never invisible.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferencesRepository;
    private final Map<NotificationChannel, NotificationTransport> transports;
    private final NotificationRetryPolicy retryPolicy;
    private final Clock clock;
    private final Random random = new Random();

    public NotificationService(NotificationRepository notificationRepository,
            NotificationPreferenceRepository preferencesRepository,
            List<NotificationTransport> transports, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.preferencesRepository = preferencesRepository;
        EnumMap<NotificationChannel, NotificationTransport> byChannel =
                new EnumMap<>(NotificationChannel.class);
        for (NotificationTransport transport : transports) {
            byChannel.put(transport.channel(), transport);
        }
        this.transports = Map.copyOf(byChannel);
        this.retryPolicy = NotificationRetryPolicy.defaults();
        this.clock = clock;
    }

    /**
     * Raises a notification and attempts immediate delivery.
     *
     * @param recipientAddress the email address, only ever passed to a transport
     * @return the notification with its delivery state recorded
     */
    @Transactional
    public Notification notify(UUID organizationId, UUID userId, NotificationType type,
            String subject, String body, String recipientAddress) {
        NotificationPreferences preferences = preferencesFor(userId);

        Notification notification = Notification.addressedTo(organizationId, userId, type,
                subject, body, preferences, NotificationChannel.values());
        notification = notification.withAttempt(notification.id(), clock.instant(),
                notification.deliveries());

        return deliver(notification, recipientAddress);
    }

    /**
     * Attempts every channel that is still outstanding.
     *
     * <p>Each channel is independent: a failed email does not undo a delivered
     * in-app record.
     */
    private Notification deliver(Notification notification, String recipientAddress) {
        Notification current = notification;
        for (Map.Entry<NotificationChannel, Notification.ChannelDelivery> entry
                : current.deliveries().entrySet()) {
            NotificationChannel channel = entry.getKey();
            Notification.ChannelDelivery delivery = entry.getValue();
            if (delivery.state() != DeliveryState.PENDING
                    && delivery.state() != DeliveryState.RETRY_SCHEDULED) {
                continue;
            }
            current = attempt(current, channel, recipientAddress);
        }
        return current;
    }

    private Notification attempt(Notification notification, NotificationChannel channel,
            String recipientAddress) {
        int attempt = notification.deliveries().get(channel).attempts() + 1;
        NotificationTransport transport = transports.get(channel);
        if (transport == null) {
            return notification.afterFailure(channel, "no transport registered", attempt,
                    java.time.Duration.ZERO, false, clock.instant());
        }

        try {
            NotificationTransport.DeliveryAttemptResult result = transport.send(notification,
                    recipientAddress);
            if (result.success()) {
                return notification.afterSuccess(channel, attempt, clock.instant());
            }
            // A permanent failure is not retried; retrying an invalid address just
            // burns attempts that a real fault could have used.
            boolean willRetry = result.isTransient()
                    && notification.type().isRetryable()
                    && retryPolicy.shouldRetry(attempt);
            var delay = willRetry
                    ? retryPolicy.delayFor(attempt, random.nextDouble())
                    : java.time.Duration.ZERO;
            if (!willRetry) {
                log.error("notification permanently failed type={} channel={} notificationId={}",
                        notification.type(), channel, notification.id());
            }
            return notification.afterFailure(channel, result.error(), attempt, delay, willRetry,
                    clock.instant());
        } catch (RuntimeException failure) {
            // A throwing transport must not take down the request that raised the
            // notification. The user's API call is unrelated to our mail problems.
            log.error("notification transport threw type={} channel={}", notification.type(),
                    channel, failure);
            return notification.afterFailure(channel, String.valueOf(failure.getMessage()),
                    attempt, java.time.Duration.ZERO, false, clock.instant());
        }
    }

    /**
     * The user's stored preferences, or the safe defaults.
     *
     * <p>Defaults mean everything on. A user with no stored preferences must still
     * receive security mail.
     */
    @Transactional(readOnly = true)
    public NotificationPreferences preferencesFor(UUID userId) {
        var rows = preferencesRepository.findByUserId(userId);
        if (rows.isEmpty()) {
            // No stored preference means the safe default: everything on.
            return NotificationPreferences.defaultsFor(userId);
        }
        EnumMap<NotificationCategory, java.util.Set<NotificationChannel>> enabled =
                new EnumMap<>(NotificationCategory.class);
        for (var row : rows) {
            enabled.put(row.getCategory(), row.decode());
        }
        return NotificationPreferences.ofEnabled(userId, enabled);
    }

    /**
     * Stores preferences, rejecting any that would silence a mandatory event.
     */
    @Transactional
    public NotificationPreferences updatePreferences(UUID userId,
            Map<NotificationCategory, java.util.Set<NotificationChannel>> disabled) {
        NotificationPreferences validated = NotificationPreferences.of(userId, disabled);
        for (var entry : validated.enabledByCategory().entrySet()) {
            preferencesRepository.save(new NotificationPreferenceEntity(userId,
                    entry.getKey(), entry.getValue()));
        }
        return validated;
    }

    public NotificationRetryPolicy retryPolicy() {
        return retryPolicy;
    }
}
