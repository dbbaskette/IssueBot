package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;

/** Shared console preference, never an individual user's setting. */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Notification.Category category;
    @Column(nullable = false)
    private boolean muted;
    public NotificationPreference() {}
    public NotificationPreference(Notification.Category category, boolean muted) {
        this.category = category;
        this.muted = muted;
    }
    public Notification.Category getCategory() { return category; }
    public boolean isMuted() { return muted; }
    public void setMuted(boolean muted) { this.muted = muted; }
}
