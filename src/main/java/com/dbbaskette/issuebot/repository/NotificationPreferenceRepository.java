package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.model.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Notification.Category> {}
