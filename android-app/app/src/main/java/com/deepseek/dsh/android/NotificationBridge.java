package com.deepseek.dsh.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public final class NotificationBridge {

    public static final String EVENT_PREFIX = "DSH_SCHEDULE_EVENT ";
    private static final String TAG = "DshNotify";
    private static final String CHANNEL_ID = "dsh_schedule_events_v1";
    private static final int NOTIFICATION_ID_BASE = 10000;
    private static final int MAX_RECENT_EVENTS = 64;

    private final Context context;
    private final NotificationManager notificationManager;
    private final Set<String> recentEvents = new LinkedHashSet<>();

    public NotificationBridge(Context context) {
        this.context = context.getApplicationContext();
        notificationManager = this.context.getSystemService(NotificationManager.class);
        if (notificationManager != null
                && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DSH 后台任务", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("DSH schedule 与后台任务完成状态");
            channel.setShowBadge(true);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void observe(String line) {
        if (line == null) {
            return;
        }
        int start = line.indexOf(EVENT_PREFIX);
        if (start < 0) {
            return;
        }
        try {
            JSONObject event = new JSONObject(line.substring(start + EVENT_PREFIX.length()).trim());
            if (!"schedule.finished".equals(event.optString("event"))) {
                return;
            }
            String taskId = bounded(event.optString("taskId"), 160);
            String title = bounded(event.optString("title"), 120);
            String status = bounded(event.optString("status"), 32);
            String sessionId = bounded(event.optString("sessionId"), 160);
            String summary = bounded(event.optString("summary"), 600);
            if (taskId.isEmpty() || sessionId.isEmpty()) {
                Log.w(TAG, "schedule event missing taskId or sessionId");
                return;
            }
            if (!"completed".equals(status) && !"failed".equals(status)) {
                Log.w(TAG, "unknown schedule status: " + status);
                return;
            }
            String messageId = bounded(event.optString("messageId"), 160);
            String eventKey = taskId + ":" + messageId + ":" + status;
            if (!recentEvents.add(eventKey)) {
                return;
            }
            if (recentEvents.size() > MAX_RECENT_EVENTS) {
                Iterator<String> iterator = recentEvents.iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            publish(taskId, title, status, sessionId, summary);
        } catch (Exception e) {
            Log.w(TAG, "invalid schedule event: " + e.getClass().getSimpleName());
        }
    }

    private void publish(String taskId, String title, String status,
                         String sessionId, String summary) {
        if (notificationManager == null) {
            return;
        }
        boolean failed = "failed".equals(status);
        String displayTitle = (failed ? "DSH 任务失败 · " : "DSH 任务完成 · ")
                + (title.isEmpty() ? taskId : title);
        String text = summary.isEmpty()
                ? (failed ? "执行失败，点击查看详情" : "后台任务已完成，点击查看结果")
                : summary;
        int notificationId = NOTIFICATION_ID_BASE + (taskId.hashCode() & 0x3fffffff);
        Intent open = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_OPEN_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
                .putExtra(MainActivity.EXTRA_TASK_ID, taskId);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(displayTitle)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .build();
        notificationManager.notify(notificationId, notification);
        Log.i(TAG, "notification posted id=" + notificationId + " task=" + taskId
                + " session=" + sessionId + " status=" + status);
    }

    private static String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
