package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.service.ui.IssueNextAction;
import org.springframework.data.domain.Page;

/** The bell and its preview share this immutable read, including their read watermark. */
public record NotificationSnapshot(Page<Group> groups, long unreadActionGroupCount,
                                   long highestVisibleEventId) {
    public record Group(String key, Notification latest, long unreadCount, long throughId,
                        IssueNextAction action, boolean critical) {
        public boolean actionable() { return critical || action != null && action.actionRequired(); }
        public String categoryLabel() {
            if (latest.getCategory() == null) return "Legacy";
            String name = latest.getCategory().name().toLowerCase(java.util.Locale.ROOT);
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }
}
