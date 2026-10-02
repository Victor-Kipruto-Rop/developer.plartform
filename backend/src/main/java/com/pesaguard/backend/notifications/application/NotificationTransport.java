package com.pesaguard.backend.notifications.application;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

/**
 * Sends a notification on one channel.
 *
 * <p>The seam where a real transport attaches. Implementations must be honest
 * about failure: an implementation that reports success without sending turns a
 * missed security notification into a silent lie.
 */
public interface NotificationTransport {

    NotificationChannel channel();

    /**
     * Attempts delivery.
     *
     * @return success, or a failure reason. Never throws for an expected failure;
     *         a thrown exception is treated as a failure by the caller either way.
     */
    DeliveryAttemptResult send(Notification notification, String recipient);

    /**
     * The outcome of one attempt.
     *
     * @param success whether it was handed over
     * @param error a bounded, non-sensitive failure reason; null on success
     */
    record DeliveryAttemptResult(boolean success, String error) {

        public static DeliveryAttemptResult delivered() {
            return new DeliveryAttemptResult(true, null);
        }

        /**
         * A failure worth retrying: a transport fault, not a bad address.
         *
         * <p>Distinguished from {@link #permanentFailure(String)} because
         * retrying an invalid address forever helps nobody.
         */
        public static DeliveryAttemptResult transientFailure(String error) {
            return new DeliveryAttemptResult(false, error);
        }

        /** A failure that will not improve: a malformed or unknown address. */
        public static DeliveryAttemptResult permanentFailure(String error) {
            return new DeliveryAttemptResult(false, error);
        }

        public boolean isTransient() {
            return !success && error != null && !error.toLowerCase(java.util.Locale.ROOT)
                    .contains("invalid address");
        }
    }
}