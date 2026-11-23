package com.muxiao.Venus.Link;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.common.ui.compose.LinkScreenKt;

import java.util.Locale;

/**
 * ADB 抓取前台服务：先配对本机 adbd，再轮询 logcat 提取抽卡 URL。
 * 配对码须在系统「配对码」弹窗保持打开时经通知输入——弹窗关闭则配对端口立即失效，
 * 应用内对话框流程必然报 unable to start pairing client（Ascent 同款原因）。
 * 命中后广播 URL 并自动收尾。
 */
public class AdbCaptureService extends Service {
    private static final String TAG = "AdbCaptureService";
    private static final String STOP_SERVICE_ACTION = "com.muxiao.Venus.Link.STOP_ADB_CAPTURE";
    private static final String CHANNEL_ID_WATCH = "AdbCaptureService";
    private static final String CHANNEL_ID_PAIRING = "AdbPairingService";
    /** 与 CaptureService 隔离的通知 id。 */
    private static final int NOTIFICATION_ID = 50641;
    /** 轮询间隔（对齐 Ascent）。 */
    private static final long POLL_INTERVAL_MS = 300;
    /** 探针只取日志尾部，避免一次性 dump 过大。 */
    private static final int LOGCAT_TAIL_LINES = 4000;
    /** 连续失败阈值：300ms × 34 ≈ 10s，瞬时抖动不误判断连。 */
    private static final int DISCONNECT_THRESHOLD = 34;

    public static final String ACTION_ADB_GET_AUTHKEY = "com.muxiao.Venus.Link.ADB_GET_AUTHKEY";
    public static final String ACTION_ADB_STATUS = "com.muxiao.Venus.Link.ADB_STATUS";
    /** startForegroundService 附加 action：启动配对模式。 */
    public static final String ACTION_START_PAIRING = "com.muxiao.Venus.Link.START_ADB_PAIRING";
    /** 状态 extra：复用 LinkScreenKt 的 VPN_STATUS_*。 */
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PAIR_PORT = "extra_pair_port";
    public static final String EXTRA_PAIR_CODE = "extra_pair_code";
    public static final String EXTRA_CONNECT_PORT = "extra_connect_port";

    /** RemoteInput 结果 key。 */
    static final String REPLY_KEY_CODE = "reply_pair_code";
    static final String REPLY_KEY_PORT = "reply_pair_port";
    static final String REPLY_KEY_CONNECT_PORT = "reply_connect_port";
    /** 通知动作 id。 */
    static final String ACTION_REPLY_CODE = "com.muxiao.Venus.Link.REPLY_PAIR_CODE";
    static final String ACTION_REPLY_PORT = "com.muxiao.Venus.Link.REPLY_PAIR_PORT";
    static final String ACTION_REPLY_CONNECT_PORT = "com.muxiao.Venus.Link.REPLY_CONNECT_PORT";
    static final String ACTION_REPLY_SKIP = "com.muxiao.Venus.Link.REPLY_PAIR_SKIP";

    /** UI 侧服务运行态。 */
    public static volatile boolean running = false;
    /** mDNS 最新发现的配对端口（null=未发现）。 */
    static volatile String discoveredPairPort = null;

    private volatile boolean stopping = false;
    /** true=配对模式，false=监听模式。 */
    private volatile boolean pairingMode = false;
    /** 本服务实例是否已 startForeground；避免重复挂通知，也供启动超时兜底判断。 */
    private boolean foregroundPosted = false;
    private Thread worker;
    private AdbGachaCapture.Discovery pairDiscovery;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (STOP_SERVICE_ACTION.equals(intent.getAction()))
                stopCapture();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        stopping = false;
        // 服务重启后旧端口必然过期（每次重开系统弹窗都会变），不沿用上轮发现结果
        discoveredPairPort = null;
        IntentFilter filter = new IntentFilter(STOP_SERVICE_ACTION);
        ContextCompat.registerReceiver(this, stopReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService 后 5s 内必须 startForeground：配对入口会立即挂通知，
        // 其余分支（含 START_STICKY 无参重启）先挂占位通知，避免等 connect 超时而被系统杀掉
        String action = intent != null ? intent.getAction() : null;
        if (!ACTION_START_PAIRING.equals(action)) ensureForeground();
        if (ACTION_REPLY_SKIP.equals(action)) {
            // 跳过配对，直接连接（监听中到达则忽略）
            if (worker == null) {
                pairingMode = false;
                cancelPairDiscovery();
                startSetupAndWatch(null, null, null);
            }
        } else if (intent != null && intent.hasExtra(EXTRA_PAIR_CODE)) {
            onPairCodeSubmitted(intent.getStringExtra(EXTRA_PAIR_PORT),
                    intent.getStringExtra(EXTRA_PAIR_CODE));
        } else if (intent != null && intent.hasExtra(EXTRA_CONNECT_PORT)) {
            // 手输连接端口：跳过 mDNS 发现，用已配对密钥直接连接
            String connectPort = intent.getStringExtra(EXTRA_CONNECT_PORT);
            if (connectPort != null && !connectPort.isEmpty() && worker == null) {
                pairingMode = false;
                cancelPairDiscovery();
                startSetupAndWatch(null, null, connectPort.trim());
            }
        } else if (intent != null && intent.hasExtra(EXTRA_PAIR_PORT)) {
            // 仅端口更新：刷新配对通知，继续等码
            onPairCodeSubmitted(intent.getStringExtra(EXTRA_PAIR_PORT), null);
        } else if (ACTION_START_PAIRING.equals(action) && worker == null && !pairingMode) {
            startForegroundPairing();
        } else if (worker == null && !pairingMode) {
            // 无参启动（START_STICKY 重启）：走已配对复用路径
            startSetupAndWatch(null, null, null);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopCaptureInternal();
        try {
            unregisterReceiver(stopReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============================== 配对模式 ==============================

    private void startForegroundPairing() {
        startForegroundPairingNotification(null);
        pairingMode = true;
        discoveredPairPort = null;
        broadcastStatus(LinkScreenKt.VPN_STATUS_RUNNING, getString(R.string.adb_status_pairing));
        // 系统「配对码」弹窗打开期间 adbd 才广播该 mDNS 服务
        cancelPairDiscovery();
        pairDiscovery = AdbGachaCapture.discoverPairingPort(this, 15_000, port -> {
            if (port == null) {
                // 不终止流程：通知里仍可手输端口
                Log.w(TAG, "pairing port discovery failed/timeout");
                return;
            }
            discoveredPairPort = port;
            Log.i(TAG, "pairing port discovered: " + port);
            // 回填端口，用户只需填码
            if (pairingMode)
                startForegroundPairingNotification(port);
        });
    }

    /** 配对码提交：port 为空时用 mDNS 最新发现结果，丢失结果再快速重发现一次。 */
    private void onPairCodeSubmitted(String port, String code) {
        if (code == null || code.isEmpty()) {
            // 仅端口更新：刷新通知继续等码（任何状态下都受理）
            if (port != null && !port.isEmpty()) {
                discoveredPairPort = port;
                pairingMode = true;
                startForegroundPairingNotification(port);
                broadcastStatus(LinkScreenKt.VPN_STATUS_RUNNING, getString(R.string.adb_status_pairing));
            }
            return;
        }
        String effectivePort = port != null && !port.isEmpty() ? port : discoveredPairPort;
        pairingMode = false;
        cancelPairDiscovery();
        if (effectivePort == null || effectivePort.isEmpty()) {
            // 服务重启丢失发现结果：快速重发现（用户通常仍停留在系统弹窗）
            effectivePort = AdbGachaCapture.discoverPairingPortBlocking(this, 8_000);
        }
        if (effectivePort == null || effectivePort.isEmpty()) {
            broadcastStatus(LinkScreenKt.VPN_STATUS_WARN, getString(R.string.adb_error_no_pair_port));
            stopCapture();
            return;
        }
        discoveredPairPort = effectivePort;
        startSetupAndWatch(effectivePort, code, null);
    }

    private void cancelPairDiscovery() {
        if (pairDiscovery != null) {
            pairDiscovery.cancel();
            pairDiscovery = null;
        }
    }

    // ============================== 监听模式 ==============================

    private void startSetupAndWatch(String pairPort, String pairCode, String connectPort) {
        worker = new Thread(() -> setupAndWatch(pairPort, pairCode, connectPort), "AdbCaptureSetup");
        worker.start();
    }

    /** 配对（可选）→ 发现或手输连接端口 → connect → 清日志 → 轮询；失败即报错收尾。 */
    private void setupAndWatch(String pairPort, String pairCode, String connectPort) {
        try {
            if (pairCode != null && !pairCode.isEmpty()) {
                if (pairPort == null || pairPort.isEmpty())
                    throw new RuntimeException(getString(R.string.adb_error_no_pair_port));
                broadcastStatus(LinkScreenKt.VPN_STATUS_RUNNING, getString(R.string.adb_status_pairing));
                // 须保持系统配对弹窗打开：此时 adbd 才广播 pairing 服务并接受握手
                AdbGachaCapture.pair(this, pairPort, pairCode);
            } else {
                // 已配对过的复用路径：adbd 已信任本机密钥
                broadcastStatus(LinkScreenKt.VPN_STATUS_RUNNING, getString(R.string.adb_status_connecting));
                AdbGachaCapture.ensureServer(this);
            }
            // 手输端口优先；未手输才走 mDNS 发现（对齐 Ascent 的连接页）
            String effectiveConnectPort = connectPort != null && !connectPort.isEmpty()
                    ? connectPort : AdbGachaCapture.discoverConnectPortBlocking(this, 15_000);
            if (effectiveConnectPort == null || effectiveConnectPort.isEmpty())
                throw new RuntimeException(getString(R.string.adb_error_no_connect_port));
            AdbGachaCapture.connect(this, effectiveConnectPort);
            // 进入监听才切换通知，避免失败前显示「假已连接」
            broadcastStatus(LinkScreenKt.VPN_STATUS_RUNNING, getString(R.string.adb_status_watching));
            // 探针须在 logcat -c 之前：dump 现存日志统计白名单命中
            probeLogcat();
            AdbGachaCapture.runAdb(this, 15_000, "shell", "logcat -c");
            startForegroundWatch();
            watchLoop();
        } catch (RuntimeException e) {
            Log.e(TAG, "adb capture failed", e);
            String msg = tools.describeError(e);
            // "unable to start pairing client" 意为配对端口未监听（弹窗已关/端口过期）
            if (msg != null && msg.contains("pairing client"))
                msg = getString(R.string.adb_pair_client_hint);
            broadcastStatus(LinkScreenKt.VPN_STATUS_WARN,
                    getString(R.string.adb_status_failed, msg));
            stopCapture();
        }
    }

    /** 探针：dump 现存日志统计关键域名命中，写进 logcat 供排查（如全 none 则游戏未打印 URL）。 */
    private void probeLogcat() {
        try {
            String logcat = AdbGachaCapture.runAdbLenient(this, 15_000,
                    "shell", "logcat -d -t " + LOGCAT_TAIL_LINES);
            int lines = logcat.isEmpty() ? 0 : logcat.split("\n").length;
            String[] keys = {"hk4e", "hkrpg", "webstatic", "api-takumi", "gacha",
                    "mihoyo.com", "hoyoverse.com"};
            StringBuilder hits = new StringBuilder();
            for (String k : keys) {
                int c = countOccurrences(logcat.toLowerCase(Locale.ROOT), k);
                if (c > 0) hits.append(k).append('=').append(c).append(' ');
            }
            boolean banner = logcat.contains("beginning of");
            Log.i(TAG, "probe: lines=" + lines + " banner=" + banner
                    + " hits: " + (hits.length() == 0 ? "none" : hits.toString().trim()));
        } catch (RuntimeException e) {
            Log.w(TAG, "probe failed", e);
        }
    }

    private static int countOccurrences(String s, String key) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(key, idx)) >= 0) {
            count++;
            idx += key.length();
        }
        return count;
    }

    /** 轮询主循环：命中即广播并收尾；连续失败则判定断连收尾。 */
    private void watchLoop() {
        int consecutiveFailures = 0;
        while (!stopping && !Thread.currentThread().isInterrupted()) {
            String logcat;
            try {
                // 全量 dump（对齐 Ascent）：只受日志缓冲区大小限制，不因 tail 截断漏掉 URL；
                // 日志内容不可控，只能用 lenient 版，存活与否只看退出码和超时
                logcat = AdbGachaCapture.runAdbLenient(this, 10_000,
                        "shell", "logcat -d");
            } catch (RuntimeException e) {
                Log.w(TAG, "logcat poll failed, consecutive=" + (consecutiveFailures + 1), e);
                consecutiveFailures++;
                // 连续失败即宣告断开，避免永远挂着「监听中」
                if (consecutiveFailures >= DISCONNECT_THRESHOLD) {
                    broadcastStatus(LinkScreenKt.VPN_STATUS_WARN,
                            getString(R.string.adb_status_disconnected));
                    stopCapture();
                    return;
                }
                if (sleepPollInterval()) return;
                continue;
            }
            consecutiveFailures = 0;
            String url = AdbGachaCapture.extractWishUrl(logcat);
            if (url != null) {
                Log.i(TAG, "wish url captured, len=" + url.length());
                Intent intent = new Intent(ACTION_ADB_GET_AUTHKEY);
                intent.setPackage(getPackageName());
                intent.putExtra(EXTRA_URL, url);
                sendBroadcast(intent);
                stopCapture();
                return;
            }
            if (sleepPollInterval()) return;
        }
    }

    /** 可中断的轮询间隔 sleep：返回 true 表示线程已被中断（应退出循环）。 */
    private boolean sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private void broadcastStatus(int state, String message) {
        Intent intent = new Intent(ACTION_ADB_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    // ============================== 通知与收尾 ==============================

    /** 两步配对通知（同 Ascent）：端口未知先手输端口，发现后换成输码；另有连接端口手输入口。 */
    private void startForegroundPairingNotification(String prefillPort) {
        ensureChannel(CHANNEL_ID_PAIRING, getString(R.string.adb_pair_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        boolean portKnown = prefillPort != null && !prefillPort.isEmpty();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_PAIRING)
                .setSmallIcon(R.drawable.ic_link)
                .setContentTitle(getString(R.string.adb_pair_notification_title))
                .setContentText(portKnown
                        ? getString(R.string.adb_pair_notification_port_found, prefillPort)
                        : getString(R.string.adb_pair_notification_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // 点击回到应用（便于重开系统配对弹窗）
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), pendingFlags()));

        if (portKnown) {
            Intent codeIntent = new Intent(this, AdbPairingReplyReceiver.class)
                    .setAction(ACTION_REPLY_CODE);
            RemoteInput codeInput = new RemoteInput.Builder(REPLY_KEY_CODE)
                    .setLabel(getString(R.string.adb_pair_code_hint))
                    .build();
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_link, getString(R.string.adb_pair_action_code),
                    PendingIntent.getBroadcast(this, 1, codeIntent, replyFlags()))
                    .addRemoteInput(codeInput).build());
        } else {
            Intent portIntent = new Intent(this, AdbPairingReplyReceiver.class)
                    .setAction(ACTION_REPLY_PORT);
            RemoteInput portInput = new RemoteInput.Builder(REPLY_KEY_PORT)
                    .setLabel(getString(R.string.adb_pair_port_hint))
                    .build();
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_link, getString(R.string.adb_pair_action_port),
                    PendingIntent.getBroadcast(this, 2, portIntent, replyFlags()))
                    .addRemoteInput(portInput).build());
        }

        // mDNS 连接端口发现失败时的手输入口（Ascent 连接页同款能力）
        Intent connectIntent = new Intent(this, AdbPairingReplyReceiver.class)
                .setAction(ACTION_REPLY_CONNECT_PORT);
        RemoteInput connectInput = new RemoteInput.Builder(REPLY_KEY_CONNECT_PORT)
                .setLabel(getString(R.string.adb_connect_port_hint))
                .build();
        builder.addAction(new NotificationCompat.Action.Builder(
                R.drawable.ic_link, getString(R.string.adb_pair_action_connect_port),
                PendingIntent.getBroadcast(this, 4, connectIntent, replyFlags()))
                .addRemoteInput(connectInput).build());

        // 已配对过的直接连接入口
        Intent skipIntent = new Intent(this, AdbPairingReplyReceiver.class).setAction(ACTION_REPLY_SKIP);
        builder.addAction(new NotificationCompat.Action.Builder(
                R.drawable.ic_link, getString(R.string.adb_pair_action_skip),
                PendingIntent.getBroadcast(this, 3, skipIntent, pendingFlags())).build());

        postForeground(builder);
    }

    /** 监听通知：静默低优先级，与 CaptureService 一致。 */
    private void startForegroundWatch() {
        ensureChannel(CHANNEL_ID_WATCH, getString(R.string.adb_capture_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_WATCH)
                .setSmallIcon(R.drawable.ic_link)
                .setContentTitle(getString(R.string.adb_capture_notification_title))
                .setContentText(getString(R.string.adb_capture_notification_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        postForeground(builder);
    }

    private void postForeground(NotificationCompat.Builder builder) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), type);
            foregroundPosted = true;
        } catch (Exception e) {
            Log.e(TAG, "fail to start foreground service", e);
            stopCapture();
        }
    }

    /** 未挂过前台通知时先挂监听占位通知，满足 5s 时限；已挂过则保持当前（配对）通知。 */
    private void ensureForeground() {
        if (!foregroundPosted) startForegroundWatch();
    }

    private void ensureChannel(String id, String name, int importance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(id, name, importance);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    }

    /** RemoteInput 须用 FLAG_MUTABLE：IMMUTABLE 会导致 bad notification 且异步抛错无法捕获。 */
    private int replyFlags() {
        return PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    }

    /** 幂等收尾：停发现/轮询 → 撤前台通知 → 停服务。 */
    private synchronized void stopCapture() {
        if (stopping) return;
        stopping = true;
        pairingMode = false;
        cancelPairDiscovery();
        stopForegroundCompat();
        stopSelf();
    }

    /** onDestroy 里的幂等收尾（不重复 stopSelf） */
    private void stopCaptureInternal() {
        stopping = true;
        pairingMode = false;
        cancelPairDiscovery();
        Thread t = worker;
        if (t != null && t.isAlive())
            t.interrupt();
        stopForegroundCompat();
    }

    private void stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    // ============================== UI 工具 ==============================

    /** 进入配对模式（通知栏输入配对码）。 */
    public static void startPairing(@NonNull Context context) {
        Intent intent = new Intent(context, AdbCaptureService.class)
                .setAction(ACTION_START_PAIRING);
        ContextCompat.startForegroundService(context, intent);
    }

    /** 停止抓取（应用内广播）。 */
    public static void stop(@NonNull Context context) {
        Intent intent = new Intent(STOP_SERVICE_ACTION);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
