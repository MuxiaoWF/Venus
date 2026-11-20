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

    /** 上一次进度通知的内容签名（见 {@link #updateProgressNotification}）。 */
    private volatile String lastProgressSignature;

    private static final String CHANNEL_WORK = "venus_work_channel";

    /**
     * 错误通知渠道，{@code IMPORTANCE_LOW}：只落通知栏，不响铃、不振动。
     *
     * <p>ID 带 {@code _low} 后缀是刻意的：渠道的重要性一经创建就**不能**由程序修改
     * （{@code createNotificationChannel} 对已存在渠道只更新名称与描述，importance 被忽略）。
     * 沿用旧 ID 等于让这次降级对存量用户完全无效——他们升级后仍会被每一条失败通知响铃振动，
     * 所以必须换新 ID，旧渠道在 {@link #ensureChannels()} 里删除。
     */
    private static final String CHANNEL_ERROR = "venus_error_channel_low";

    /**
     * 人机验证通知渠道，保持 {@code IMPORTANCE_DEFAULT}。
     *
     * <p>与错误通知分开：它是功能件而非提醒件——验证没做，后台那一轮就停在「待验证」，
     * 用户必须点进来才能续跑（见 {@link #sendCaptchaNotification}）。命中 1034 本身是低频事件，
     * 一次提示音不算打扰；降到 LOW 反而会让后台任务静默卡住。
     */
    private static final String CHANNEL_CAPTCHA = "venus_captcha_channel";

    private static final String CHANNEL_PROGRESS = "venus_progress_channel";

    /**
     * 旧版错误通知渠道（{@code IMPORTANCE_DEFAULT} + 响铃振动），仅用于升级后删除。
     *
     * <p>不删会怎样：系统设置页里并排出现「Venus 错误」与「Venus 错误」两个条目，
     * 用户无法分辨该关哪一个，而应用实际只用新的那个。
     */
    private static final String CHANNEL_ERROR_LEGACY = "venus_error_channel";

    public Notification(Context context) {
        this.context = context;
    }

    // ==================== 运行形态 ====================

    /**
     * 应用内（前台）运行的层数：>0 表示当前这一轮任务由首页按钮驱动、用户正对着界面。
     *
     * <p>这是「要不要发系统通知」的唯一判据。任务模块（BBSDaily/BBSGameDaily/…）在失败时一律
     * 调 {@code sendErrorNotification}，它们无从知道自己是前台跑的还是后台跑的；用户明确要求
     * 「非后台运行不该有通知」，于是把判断收口到这里：首页运行期间置位，期间所有系统通知直接丢弃，
     * 状态与错误交给列表和对话框。后台服务 / 定时 Worker 从不置位，行为不变。
     *
     * <p>用计数而非布尔：异常路径可能重复收尾，减成负数会让后续运行永久静音。
     */
    private static final java.util.concurrent.atomic.AtomicInteger foregroundRuns =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 首页开始一轮任务：此后的系统通知一律抑制，直到 {@link #endForegroundRun()}。 */
    public static void beginForegroundRun() {
        foregroundRuns.incrementAndGet();
    }

    /** 首页这一轮结束（成功/失败/取消都要调，放 finally）。 */
    public static void endForegroundRun() {
        if (foregroundRuns.get() > 0) foregroundRuns.decrementAndGet();
    }

    /** 当前是否处于应用内运行。 */
    public static boolean inForegroundRun() {
        return foregroundRuns.get() > 0;
    }

    /**
     * 按需创建四个通知渠道（任务/错误/人机验证/进度），仅 Android O+ 生效、进程内只建一次；
     * 顺带删除已弃用的旧错误渠道（见 {@link #CHANNEL_ERROR_LEGACY}）。
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

            NotificationChannel errorChannel = new NotificationChannel(CHANNEL_ERROR, context.getString(R.string.notification_channel_error), NotificationManager.IMPORTANCE_LOW);
            errorChannel.setDescription(context.getString(R.string.notification_channel_error_desc));
            // LOW 本身就不响不震，这两行是把意图钉死在代码里：将来谁改 importance 也看得见约束
            errorChannel.setSound(null, null);
            errorChannel.enableVibration(false);

            NotificationChannel captchaChannel = new NotificationChannel(CHANNEL_CAPTCHA, context.getString(R.string.notification_channel_captcha), NotificationManager.IMPORTANCE_DEFAULT);
            captchaChannel.setDescription(context.getString(R.string.notification_channel_captcha_desc));

            NotificationChannel progressChannel = new NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notification_channel_progress), NotificationManager.IMPORTANCE_LOW);
            progressChannel.setDescription(context.getString(R.string.notification_channel_progress_desc));
            progressChannel.setSound(null, null);

            nm.createNotificationChannel(workChannel);
            nm.createNotificationChannel(errorChannel);
            nm.createNotificationChannel(captchaChannel);
            nm.createNotificationChannel(progressChannel);
            // 清掉旧版本留下的响铃振动错误渠道：已存在的渠道无法降级，只能弃用后删除，
            // 否则设置页会同时列出新旧两个「Venus 错误」，用户不知道该关哪个
            nm.deleteNotificationChannel(CHANNEL_ERROR_LEGACY);
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
        // 应用内运行期间不发系统通知：用户就在界面上，进度看列表、错误走对话框，
        // 再往通知栏丢一条只是噪声（「只有后台运行才该有通知」）。
        if (inForegroundRun()) return;
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
                // 同一条通知会被反复重发（每个任务失败一次就 notify 一次，多个游戏/账号连发），
                // 不加这个每次都会重新响铃 + 振动，一轮跑下来能响七八次。
                .setOnlyAlertOnce(true)
                .setContentIntent(createHomePendingIntent())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSmallIcon(R.drawable.ic_error)
                // 失败通知一律静默落通知栏：去掉原来的 DEFAULT_SOUND | DEFAULT_VIBRATE。
                // 渠道已是 IMPORTANCE_LOW，这里再显式 setSilent 一次，防止将来渠道被改动时静默失效。
                .setSilent(true);
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
     * 人机验证提示通知，点击直达验证流程：内容 Intent 带 {@link Constants#ACTION_HANDLE_CAPTCHA}
     * 打开 MainActivity，由其置位 captcha_pending 并自动弹框。
     *
     * <p>两点刻意设计：
     * <ul>
     *   <li><b>不受通知开关约束</b>：本通知是任务从「挂起」恢复执行的唯一入口，随开关一起关掉等于让后台
     *       静默卡在待验证、什么也不做。它是功能件，不是提醒件。</li>
     *   <li><b>独立通知 ID</b>：与错误通知分开，等待验证期间其它任务报错不会把它顶掉。</li>
     * </ul>
     */
    public void sendCaptchaNotification(String title, String content) {
        // 应用内运行：验证 UI 由 HomeFragment 直接挂在首页，不需要「点通知去验证」这条路
        if (inForegroundRun()) return;
        ensureChannels();
        NotificationManager nm = notificationManager();
        if (nm == null) return;

        Intent intent = new Intent(context, com.muxiao.Venus.MainActivity.class);
        intent.setAction(Constants.ACTION_HANDLE_CAPTCHA);
        intent.putExtra("navigate_to", "home");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_CAPTCHA)
                .setContentTitle(title)
                .setContentText(content + "\n" + context.getString(R.string.notif_captcha_tap_hint))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(content + "\n" + context.getString(R.string.notif_captcha_tap_hint)))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_error)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
        nm.notify(Constants.NOTIFICATION_ID_CAPTCHA, builder.build());
    }

    /** 撤销人机验证通知：验证结束（成功/失败/被放弃）后调用，避免残留一条点了没反应的通知。 */
    public void dismissCaptchaNotification() {
        NotificationManager nm = notificationManager();
        if (nm != null) nm.cancel(Constants.NOTIFICATION_ID_CAPTCHA);
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
        // 一律按「内容签名」去重：多账号/多游戏并发回调时，同一个进度会被连着上报好几次
        // （例如两条任务同时报 COMPLETED），每次都 notify 会让通知栏文字反复闪、也白跑一次 IPC。
        String signature = title + '|' + content + '|' + progress + '/' + max;
        if (signature.equals(lastProgressSignature)) return;
        lastProgressSignature = signature;
        builder.setContentTitle(title)
                .setContentText(content)
                .setProgress(max, progress, false);
        NotificationManager nm = notificationManager();
        if (nm != null) nm.notify(Constants.NOTIFICATION_ID_PROGRESS, builder.build());
    }

    /**
     * 本轮结束的「结果」通知：先撤销进度通知，再以独立 ID 发一条可点可划的普通通知。
     *
     * <p>早期实现复用 {@link Constants#NOTIFICATION_ID_PROGRESS}——进度通知是前台 Service 的 ongoing 通知，
     * 用 `cancel + notify` 覆盖成非 ongoing 后，`stopForeground(STOP_FOREGROUND_REMOVE)` 在某些机型只清掉
     * 它记录的那条，覆盖后的通知会一直留在通知栏里关不掉。独立 ID 后两者互不干扰。
     */
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
        if (nm == null) return;
        nm.cancel(Constants.NOTIFICATION_ID_PROGRESS);
        nm.notify(Constants.NOTIFICATION_ID_RESULT, completed.build());
        // 本轮结束，清掉进度签名：下一轮的第一次更新必须能真正发出去
        lastProgressSignature = null;
    }

    /** 撤销进度通知（NOTIFICATION_ID_PROGRESS）：每一轮结束都必须调用——它是 ongoing，用户自己划不掉。 */
    public void cancelProgressNotification() {
        lastProgressSignature = null;
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
