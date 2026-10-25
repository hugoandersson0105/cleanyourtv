package com.gathin.tvhome;

// Ported from the "Clean TV for TCL G10" fork, github.com/itsmevizz/cleanyourtv (branch clean-tv-launcher, MIT),
// for the Control Center panel (control_center). Unchanged apart from this note.

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the TV's active notifications for the Control Center panel (SettingsOverlay). Android only
 * sends them to a listener the user has allowed, so the install script grants it with
 * `cmd notification allow_listener`. Ongoing notifications (playback, downloads) are left out.
 */
public class NotificationService extends NotificationListenerService {

    static final class Item {
        final String pkg;
        final CharSequence title;
        final CharSequence text;
        final long time;
        final String key;
        final PendingIntent open;

        Item(String pkg, CharSequence title, CharSequence text, long time, String key, PendingIntent open) {
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.time = time;
            this.key = key;
            this.open = open;
        }
    }

    private static final int MAX = 12;
    private static final List<Item> items = new ArrayList<>();

    /** The current notifications, newest first. */
    static synchronized List<Item> snapshot() {
        return new ArrayList<>(items);
    }

    @Override
    public void onListenerConnected() {
        synchronized (NotificationService.class) {
            items.clear();
            for (StatusBarNotification sbn : getActiveNotifications()) {
                add(sbn);
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        synchronized (NotificationService.class) {
            add(sbn);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        synchronized (NotificationService.class) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).key.equals(sbn.getKey())) items.remove(i);
            }
        }
    }

    private static void add(StatusBarNotification sbn) {
        if (sbn == null || sbn.isOngoing() || sbn.getPackageName() == null) return;
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        CharSequence title = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) return;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key.equals(sbn.getKey())) items.remove(i);
        }
        items.add(0, new Item(sbn.getPackageName(), title, text, sbn.getPostTime(), sbn.getKey(), n.contentIntent));
        while (items.size() > MAX) items.remove(items.size() - 1);
    }
}
