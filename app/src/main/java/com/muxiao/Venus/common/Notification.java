package com.muxiao.Venus.common;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import com.muxiao.Venus.R;

public class Notification {
    private final Context context;
    private static final int NOTIFICATION_ID_WORK = 50628;
    private static final int NOTIFICATION_ID_ERROR = 50629;
    public Notification(Context context) {
        this.context = context;
    }

    /**
     * 发送系统通知
     *
     * @param title   通知标题
     * @param content 通知内容
     * @param isError 是否为错误通知
     */
    private void sendSystemNotification(String title, String content, boolean isError) {
        // 检查设置中的通知开关
        SharedPreferences prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        boolean notificationEnabled = prefs.getBoolean("notification_switch", false);

        // 只有当通知开启时才发送通知
        if (notificationEnabled) {
            // 发送系统通知的实现
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            // 创建点击通知时要启动的Intent
            Intent intent = new Intent(context, com.muxiao.Venus.MainActivity.class);
            intent.setAction(Long.toString(System.currentTimeMillis())); // 确保Intent唯一性
            // 添加参数，以便MainActivity知道需要切换到HomeFragment
            intent.putExtra("navigate_to", "home");
            // 使用FLAG_ACTIVITY_REORDER_TO_FRONT确保不创建新实例，而是将现有实例带到前台
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            // 创建PendingIntent
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags);

            // 创建通知渠道 (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CharSequence name = "Venus 通知";
                String description = "Venus 应用通知";
                NotificationChannel channel;
                if (isError) {
                    channel = new NotificationChannel("venus_error_channel", name + "错误", NotificationManager.IMPORTANCE_DEFAULT);
                    channel.setDescription(description + "错误");
                } else {
                    channel = new NotificationChannel("venus_work_channel", name + "任务", NotificationManager.IMPORTANCE_LOW);
                    channel.setDescription(description + "任务");
                    channel.setSound(null, null);
                }
                notificationManager.createNotificationChannel(channel);
            }
            // 构建通知
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, isError ? "venus_error_channel" : "venus_work_channel")
                    .setContentTitle(title)
                    .setContentText(content)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent); // 设置点击意图
            if (isError) {
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT).setSmallIcon(R.drawable.ic_error_notification).setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
                // 使用固定ID发送通知，确保只会有一个通知
                notificationManager.notify(NOTIFICATION_ID_ERROR, builder.build());
            } else {
                builder.setPriority(NotificationCompat.PRIORITY_LOW).setSmallIcon(R.drawable.ic_notification).setSound(null);
                notificationManager.notify(NOTIFICATION_ID_WORK, builder.build());
            }
        }
    }

    /**
     * 发送普通通知的便捷方法
     *
     * @param title   通知标题
     * @param content 通知内容
     */
    public void sendNormalNotification(String title, String content) {
        sendSystemNotification(title, content, false);
    }

    /**
     * 发送错误通知的便捷方法
     *
     * @param title   通知标题
     * @param content 通知内容
     */
    public void sendErrorNotification(String title, String content) {
        sendSystemNotification(title, content, true);
    }
    
    /**
     * 检查通知权限是否已启用
     *
     * @return true 表示已启用，false 表示未启用
     */
    public boolean areNotificationsEnabled() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // 检查 Android M (API 23) 及以上版本的通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return notificationManager.areNotificationsEnabled();
        }
        // 对于低于 Android M 的版本，默认返回 true
        return true;
    }
    
    /**
     * 跳转到应用的通知设置页面
     */
    public void goToNotificationSettings() {
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } else {
            intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("app_package", context.getPackageName());
            intent.putExtra("app_uid", context.getApplicationInfo().uid);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
