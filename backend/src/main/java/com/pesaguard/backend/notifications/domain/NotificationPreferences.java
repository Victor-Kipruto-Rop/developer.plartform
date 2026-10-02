package com.pesaguard.backend.notifications.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One user's notification preferences.
 *
 * <p>Defaults to everything on. A new account that has never expressed a
 * preference must still hear about a revoked key, and an empty preference set
 * meaning "nothing" would be a dangerous default.
 *
 * <p>Safety is enforced by <em>refusing to store</em> an unsafe preference
 * rather than overriding one at send time. Silently ignoring what a user asked
 * for is worse than telling them their request was not accepted: it leaves them
 * believing they are covered when they are not.
 */
public final class NotificationPreferences {

    private final UUID userId;
    private final Map<NotificationCategory, Set<NotificationChannel>> channels;

    private NotificationPreferences(UUID userId,
            Map<NotificationCategory, Set<NotificationChannel>> channels) {
        this.userId = userId;
        this.channels = channels;
    }

    /**
     * Default preferences: every category, every channel.
     *
     * <p>Everything enabled, because a user who has not configured anything
     * should receive security mail rather than none.
     */
    public static NotificationPreferences defaultsFor(UUID userId) {
        EnumMap<NotificationCategory, Set<NotificationChannel>> enabled =
                new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : NotificationCategory.values()) {
            enabled.put(category, EnumSet.allOf(NotificationChannel.class));
        }
        return new NotificationPreferences(userId, enabled);
    }

    public UUID getUserId() {
        return userId;
    }

    /** Whether a category may have its channels turned off. */
    public boolean allowsDisablingEmail(NotificationCategory category) {
        return category.allowsDisablingEmail();
    }

    /**
     * Builds a preference set, rejecting any that would silence a mandatory
     * event.
     *
     * @param disabled categories and channels the user asked to switch off
     * @throws UnsafePreferenceException if the request would silence a mandatory
     *         event
     */
    public static NotificationPreferences of(UUID userId,
            Map<NotificationCategory, Set<NotificationChannel>> disabled) {
        EnumMap<NotificationCategory, Set<NotificationChannel>> off =
                new EnumMap<>(NotificationCategory.class);
        for (Map.Entry<NotificationCategory, Set<NotificationChannel>> entry
                : disabled.entrySet()) {
            for (NotificationChannel channel : entry.getValue()) {
                if (channel == NotificationChannel.IN_APP) {
                    // In-app is the durable record; it can never be switched off.
                    throw new UnsafePreferenceException(
                            "In-app notifications cannot be disabled: they are the only "
                                    + "durable record that a notification was raised.");
                }
                if (entry.getKey().hasMandatoryEvents()) {
                    throw new UnsafePreferenceException(
                            "Email cannot be disabled for " + entry.getKey()
                                    + " notifications, because that would silence a security-critical "
                                    + "event such as a revoked credential.");
                }
            }
            off.put(entry.getKey(), EnumSet.copyOf(entry.getValue()));
        }

        EnumMap<NotificationCategory, Set<NotificationChannel>> enabled =
                new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : NotificationCategory.values()) {
            EnumSet<NotificationChannel> remaining = EnumSet.allOf(NotificationChannel.class);
            remaining.removeAll(off.getOrDefault(category, EnumSet.noneOf(NotificationChannel.class)));
            enabled.put(category, remaining);
        }
        return new NotificationPreferences(userId, enabled);
    }

    /**
     * Channels a specific event should be sent on.
     *
     * <p>Mandatory events are added to email whatever the stored preference says.
     * This is the second line of defence behind the rejection in {@link #of}: the
     * preference simply cannot express the unsafe state, and if one is ever
     * written by other means it still cannot silence a revocation.
     */
    public Set<NotificationChannel> channelsFor(NotificationType type) {
        Set<NotificationChannel> configured =
                channels.getOrDefault(type.category(),
                        EnumSet.allOf(NotificationChannel.class));
        if (!type.mandatory()) {
            return EnumSet.copyOf(configured);
        }
        EnumSet<NotificationChannel> effective = EnumSet.copyOf(configured);
        effective.add(NotificationChannel.EMAIL);
        effective.add(NotificationChannel.IN_APP);
        return effective;
    }

    /** Whether a specific event may be sent on a channel. */
    public boolean isEnabled(NotificationType type, NotificationChannel channel) {
        return channelsFor(type).contains(channel);
    }

    /** Raised when a preference would silence a mandatory event. */
    public static class UnsafePreferenceException extends IllegalArgumentException {
        public UnsafePreferenceException(String message) {
            super(message);
        }
    }

    /**
     * Rebuilds preferences from stored enabled channels, re-validating.
     *
     * <p>Validation runs again on read as well as on write, so a row written by
     * any other route still cannot express an unsafe preference.
     */
    public static NotificationPreferences ofEnabled(UUID userId,
            Map<NotificationCategory, Set<NotificationChannel>> enabled) {
        EnumMap<NotificationCategory, Set<NotificationChannel>> disabled =
                new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : NotificationCategory.values()) {
            Set<NotificationChannel> on = enabled.get(category);
            EnumSet<NotificationChannel> off = EnumSet.allOf(NotificationChannel.class);
            if (on != null) {
                off.removeAll(on);
            }
            disabled.put(category, off);
        }
        return of(userId, disabled);
    }

    /** The effective enabled channels per category, for persisting. */
    public Map<NotificationCategory, Set<NotificationChannel>> enabledByCategory() {
        EnumMap<NotificationCategory, Set<NotificationChannel>> result =
                new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : NotificationCategory.values()) {
            result.put(category, EnumSet.copyOf(
                    channels.getOrDefault(category, EnumSet.allOf(NotificationChannel.class))));
        }
        return result;
    }}
