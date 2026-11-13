package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.Constants.Prefs.NOTIFICATION;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;

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

/**
 * 通知管理：三个渠道（任务/错误/进度），支持前台Service进度通知和错误通知。
 * 错误通知受用户设置中的通知开关控制，进度通知始终显示。
 */
public class Notification {
    private final Context context;
    private static volatile boolean channelsCreated = false;

    private static final String CHANNEL_WORK = "venus_work_channel";
    private static final String CHANNEL_ERROR = "venus_error_channel";
    private static final String CHANNEL_PROGRESS = "venus_progress_channel";

    public Notification(Context context) {
        this.context = context;
    }

    /**
     * 按需创建三个通知渠道（任务/错误/进度），仅 Android O+ 生效、进程内只建一次。
     * 用双重检查 + 类锁：创建可能来自后台任务线程与主线程；只在成功后置位，失败时保留重试机会。
     */
    private void ensureChannels() {
        if (channelsCreated) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        synchronized (Notification.class) {
            if (channelsCreated) return;
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel workChannel = new NotificationChannel(CHANNEL_WORK, context.getString(R.string.notification_channel_task), NotificationManager.IMPORTANCE_LOW);
            workChannel.setDescription(context.getString(R.string.notification_channel_task_desc));
            workChannel.setSound(null, null);

            NotificationChannel errorChannel = new NotificationChannel(CHANNEL_ERROR, context.getString(R.string.notification_channel_error), NotificationManager.IMPORTANCE_DEFAULT);
            errorChannel.setDescription(context.getString(R.string.notification_channel_error_desc));

            NotificationChannel progressChannel = new NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notification_channel_progress), NotificationManager.IMPORTANCE_LOW);
            progressChannel.setDescription(context.getString(R.string.notification_channel_progress_desc));
            progressChannel.setSound(null, null);

            nm.createNotificationChannel(workChannel);
            nm.createNotificationChannel(errorChannel);
            nm.createNotificationChannel(progressChannel);
            channelsCreated = true;
        }
    }

    /** 取通知服务；极端情况下（服务不可用）返回 null，由调用方跳过而非抛 NPE。 */
    private NotificationManager notificationManager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 点击通知跳主页（home）的 PendingIntent；action 带时间戳，避免 PendingIntent 被复用。 */
    private PendingIntent createHomePendingIntent() {
        Intent intent = new Intent(context, com.muxiao.Venus.MainActivity.class);
        intent.setAction(Long.toString(System.currentTimeMillis()));
        intent.putExtra("navigate_to", "home");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 发错误通知；force=false 时受设置中的通知开关控制，未开启直接返回。 */
    private void sendErrorSystemNotification(String title, String content, boolean force) {
        if (!force) {
            SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(NOTIFICATION, false)) return;
        }

        ensureChannels();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ERROR)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .setContentIntent(createHomePendingIntent())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_error)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
        nm.notify(Constants.NOTIFICATION_ID_ERROR, builder.build());
    }

    /** 发送错误通知（受设置中的通知开关控制，未开启不弹）。 */
    public void sendErrorNotification(String title, String content) {
        sendErrorSystemNotification(title, content, false);
    }

    /** 同上，force=true 忽略通知开关强制弹出。 */
    public void sendErrorNotification(String title, String content, boolean force) {
        sendErrorSystemNotification(title, content, force);
    }

    /**
     * 人机验证提示通知（受通知开关控制），点击直达验证流程：内容 Intent 带 {@link Constants#ACTION_HANDLE_CAPTCHA}
     * 打开 MainActivity，由其置位 captcha_pending 并自动弹框。后台任务不再主动拉起 Activity，本通知是用户
     * 进入验证的唯一入口，故附「点击」提示行；复用错误通知 ID，避免多任务触发时在通知栏堆积。
     */
    public void sendCaptchaNotification(String title, String content) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(NOTIFICATION, false)) return;

        ensureChannels();
        NotificationManager nm = notificationManager();
        if (nm == null) return;

        Intent intent = new Intent(context, com.muxiao.Venus.MainActivity.class);
        intent.setAction(Constants.ACTION_HANDLE_CAPTCHA);
        intent.putExtra("navigate_to", "home");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ERROR)
                .setContentTitle(title)
                .setContentText(content + "\n" + context.getString(R.string.notif_captcha_tap_hint))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(content + "\n" + context.getString(R.string.notif_captcha_tap_hint)))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_error)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
        nm.notify(Constants.NOTIFICATION_ID_ERROR, builder.build());
    }

    /** 取消并移除错误通知（NOTIFICATION_ID_ERROR）。 */
    public void dismissErrorNotification() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(Constants.NOTIFICATION_ID_ERROR);
    }

    /** 创建持久进度通知（前台 Service 用）。 */
    public NotificationCompat.Builder createProgressNotification(String title, String content) {
        ensureChannels();
        return new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(createHomePendingIntent())
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSound(null);
    }

    /** 更新前台进度通知的标题/内容/进度，用同一通知 ID 刷新。 */
    public void updateProgressNotification(NotificationCompat.Builder builder,
                                            String title, String content,
                                            int progress, int max) {
        builder.setContentTitle(title)
                .setContentText(content)
                .setProgress(max, progress, false);
        NotificationManager nm = notificationManager();
        if (nm != null) nm.notify(Constants.NOTIFICATION_ID_PROGRESS, builder.build());
    }

    /** 进度通知转为完成通知（改用 WORK 渠道，确保用户能看到）。 */
    public void completeProgressNotification(String title, String content) {
        ensureChannels();
        NotificationCompat.Builder completed = new NotificationCompat.Builder(context, CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(createHomePendingIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(Constants.NOTIFICATION_ID_PROGRESS);
        nm.notify(Constants.NOTIFICATION_ID_PROGRESS, completed.build());
    }

    /** 撤销进度通知（NOTIFICATION_ID_PROGRESS）：定时任务被取消/异常收尾时兜底，避免常驻。 */
    public void cancelProgressNotification() {
        NotificationManager nm = notificationManager();
        if (nm != null) nm.cancel(Constants.NOTIFICATION_ID_PROGRESS);
    }

    /** 系统级通知是否被用户关闭（Android N+ 才支持，旧版本恒 false）。 */
    public boolean areNotificationsDisabled() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return !nm.areNotificationsEnabled();
    }

    /** 跳本应用通知设置页的 Intent，兼容 Android O 前后的 Action 与 Extra。 */
    public Intent getNotificationSettingsIntent() {
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } else {
            intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("app_package", context.getPackageName());
            intent.putExtra("app_uid", context.getApplicationInfo().uid);
        }
        return intent;
    }
}
