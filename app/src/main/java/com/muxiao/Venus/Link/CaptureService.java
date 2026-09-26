package com.muxiao.Venus.Link;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.muxiao.Venus.R;

import java.io.FileDescriptor;

/**
 * 本地 VPN 抓包服务（复刻自 SRGachaLink，GPLv3，其 C 层源自 NetGuard）。
 * 链路：游戏流量 → VpnService TUN（白名单星铁各渠道包）→ Unix socketpair 双向中转 →
 * netguard JNI 用户态协议栈转发到真实网络；转发过程中每包旁路镜像给 PacketAnalyzer。
 * 仅需要星铁的 KCP 链接，authkey 捕获到即广播 ACTION_GET_AUTHKEY 并可停止。
 */
@SuppressLint("VpnServicePolicy")
public class CaptureService extends VpnService {
    static {
        // 加载 netguard JNI 库（app/src/main/cpp，CMake 目标名 netguard）
        System.loadLibrary("netguard");
    }

    private static final String TAG = "CaptureService";
    private static final String STOP_SERVICE_ACTION = "com.muxiao.Venus.Link.STOP_CAPTURE";
    public static final String ACTION_GET_AUTHKEY = "com.muxiao.Venus.Link.GET_AUTHKEY";
    /** 捕获失败广播（PacketAnalyzer 发出）：extra "reason" 说明原因（key_missing=云端密钥表仍未收录该版本） */
    public static final String ACTION_CAPTURE_FAILED = "com.muxiao.Venus.Link.CAPTURE_FAILED";
    /** 密钥表更新中广播（PacketAnalyzer 缺键时发出，抓包继续，重放机制衔接；拿到键后由成功广播覆盖） */
    public static final String ACTION_CAPTURE_UPDATING = "com.muxiao.Venus.Link.CAPTURE_UPDATING";
    private static final String CHANNEL_ID = "CaptureService";
    private static final int MAX_PACKET_SIZE = 8192;

    /** 星铁国服 / B服 / 国际服包名（白名单：仅这些应用的流量进入 VPN） */
    private static final String[] ALLOWED_APPS = {
            "com.miHoYo.hkrpg",
            "com.miHoYo.hkrpg.bilibili",
            "com.HoYoverse.hkrpgoversea"
    };

    /** 渠道标识（随 authkey 广播带给 UI 拼 URL） */
    public static final String CHANNEL_CN = "cn";
    public static final String CHANNEL_BILI = "bilibili";
    public static final String CHANNEL_GLOBAL = "global";

    /** UDP 客户端源端口 → 渠道包名（isAddressAllowed 在新会话建立时记录，authkey 判渠道用） */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> PORT_PACKAGE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** UI 侧判断服务运行态（onCreate/onDestroy 维护） */
    public static volatile boolean running = false;

    /** 最近一次捕获的渠道（null=本次运行尚未捕获；PacketAnalyzer 成功时写入，LinkFragment 回显用） */
    public static volatile String capturedChannel = null;

    private ParcelFileDescriptor pfd;
    private ParcelFileDescriptor remotePfd;
    private ParcelFileDescriptor localPfd;
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (STOP_SERVICE_ACTION.equals(intent.getAction()))
                stopCapture();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();
        if (pfd != null && tunnelThread != null) {
            // 重复 startForegroundService：不能重绑 socketpair，否则 native 隧道仍持旧 fd，永远收不到包
            Log.i(TAG, "already running, ignore duplicate start");
            return super.onStartCommand(intent, flags, startId);
        }
        startVpn();
        startCapture();
        initNative();
        startNative();
        // 每次启动抓包时检查星铁初始密钥表更新（HsrKeyStore 内部节流：距上次成功拉取
        // 不足 24h 直接跳过，正常情况零请求；拉取/合并均在后台线程池）
        HsrKeyStore.checkForUpdateAsync(getApplicationContext(), false);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        IntentFilter filter = new IntentFilter(STOP_SERVICE_ACTION);
        ContextCompat.registerReceiver(this, stopReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            unregisterReceiver(stopReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    private void stopCapture() {
        capturedChannel = null;
        stopNative();
        stopForward();
        stopVpn();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /* ===== VPN ===== */

    private void startVpn() {
        if (pfd != null) return;
        VpnService.Builder builder = new VpnService.Builder();
        builder.setMtu(MAX_PACKET_SIZE);
        builder.addAddress("10.1.10.1", 32);
        builder.addRoute("0.0.0.0", 0);
        // 应用白名单：不存在的包名抛 NameNotFoundException，逐个忽略
        for (String app : ALLOWED_APPS) {
            try {
                builder.addAllowedApplication(app);
            } catch (NameNotFoundException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        try {
            pfd = builder.establish();
            localPfd = pfd;
        } catch (Exception e) {
            // 建立失败（未授权/系统限制等）直接自毁，不能让异常炸掉服务进程
            Log.e(TAG, "fail to start vpn", e);
            stopSelf();
        }
    }

    private void stopVpn() {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            pfd = null;
        }
    }

    /* ===== 旁路转发 ===== */

    /**
     * 建立 TUN 与 netguard 之间的双向转发：
     * TUN 出向 → socketpair → netguard（jni_run 阻塞读 localPfd）；
     * netguard 回包 → socketpair → TUN。两条线程各持一个方向，
     * 转发的同时把每包镜像给 PacketAnalyzer（旁路，不影响转发）。
     */
    private void startCapture() {
        if (pfd == null) return;
        PORT_PACKAGE.clear();
        capturedChannel = null;
        try {
            // Unix socketpair：fd1↔fd2 互为对端，dup 成 PFD 后原始句柄关闭
            FileDescriptor fd1 = new FileDescriptor();
            FileDescriptor fd2 = new FileDescriptor();
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, fd1, fd2);
            remotePfd = ParcelFileDescriptor.dup(fd1);
            localPfd = ParcelFileDescriptor.dup(fd2);
            Os.close(fd1);
            Os.close(fd2);
        } catch (Exception e) {
            Log.e(TAG, "fail to create socketpair", e);
            stopCapture();
            return;
        }
        PacketAnalyzer analyzer = new PacketAnalyzer(this);
        forward(pfd, remotePfd, analyzer);
        forward(remotePfd, pfd, analyzer);
    }

    private void forward(ParcelFileDescriptor src, ParcelFileDescriptor dst, PacketAnalyzer analyzer) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            try (ParcelFileDescriptor.AutoCloseInputStream in =
                         new ParcelFileDescriptor.AutoCloseInputStream(src);
                 ParcelFileDescriptor.AutoCloseOutputStream out =
                         new ParcelFileDescriptor.AutoCloseOutputStream(dst)) {
                while (true) {
                    int length = in.read(buffer, 0, MAX_PACKET_SIZE);
                    if (length <= 0) continue;
                    out.write(buffer, 0, length);
                    // 旁路镜像：复制一份交分析器（不能把内部缓冲直接交出去被改写）
                    byte[] packet = new byte[length];
                    System.arraycopy(buffer, 0, packet, 0, length);
                    analyzer.handleIpPacket(packet, length);
                }
            } catch (Exception ignored) {
                // 流关闭即线程自然退出（停止流程会依次关闭两端）
            }
        }, "CaptureForward");
        t.start();
    }

    private void stopForward() {
        try {
            if (localPfd != null) localPfd.close();
            if (remotePfd != null) remotePfd.close();
        } catch (Exception ignored) {
        }
        localPfd = null;
        remotePfd = null;
    }

    /* ===== netguard JNI ===== */

    private final Object jni_lock = new Object();
    private long jni_context;
    private Thread tunnelThread;

    private void initNative() {
        if (jni_context != 0L) {
            Log.w(TAG, "context reset");
            jni_stop(jni_context);
            synchronized (jni_lock) {
                jni_done(jni_context);
                jni_context = 0;
            }
        }
        jni_context = jni_init(Build.VERSION.SDK_INT);
        Log.i(TAG, "init native");
    }

    private void startNative() {
        if (localPfd != null && tunnelThread == null) {
            jni_start(jni_context, Log.ERROR);
            tunnelThread = new Thread(() -> {
                Log.i(TAG, "tunnel thread is running");
                // 此服务仅抓包、不做 DNS 重定向转发，故 fwd53 固定为 false
                jni_run(jni_context, localPfd.getFd(), false, 3);
                Log.i(TAG, "tunnel thread exited");
                tunnelThread = null;
            }, "CaptureTunnel");
            tunnelThread.start();
            Log.i(TAG, "start native");
        }
    }

    private void stopNative() {
        if (tunnelThread != null) {
            jni_stop(jni_context);
            while (tunnelThread != null && tunnelThread.isAlive()) {
                try {
                    tunnelThread.join();
                } catch (InterruptedException e) {
                    Log.i(TAG, "tunnel thread is interrupted");
                }
            }
            tunnelThread = null;
            jni_clear(jni_context);
            Log.i(TAG, "stop native");
        }
    }

    // JNI 导出函数（C 侧符号：Java_com_muxiao_Venus_Link_CaptureService_jni_*）。
    // 仅保留本服务实际使用的入口；C 侧同符号导出（get_mtu/get_stats/pcap/socks5）
    // 在未声明时成为死导出，无副作用。
    private native long jni_init(int sdk);

    private native void jni_start(long context, int loglevel);

    private native void jni_run(long context, int tun, boolean fwd53, int rcode);

    private native void jni_stop(long context);

    private native void jni_clear(long context);

    private native void jni_done(long context);

    /* ===== JNI 回调（C 侧经 GetMethodID 反射调用，勿改名；混淆需 keep） ===== */
    /* 以下方法仅在 native 侧被引用，Java 层无调用点：@SuppressWarnings 消除
     * IDE 的 unused 告警（方法级精确标注，不影响类内其他死代码检查）。 */

    @SuppressWarnings("unused") // Called from native code
    private void nativeExit(String reason) {
        Log.w(TAG, "Native exit reason=" + reason);
    }

    @SuppressWarnings("unused") // Called from native code
    private void nativeError(int error, String message) {
        Log.w(TAG, "Native error " + error + ": " + message);
    }

    @SuppressWarnings("unused") // Called from native code
    private void logPacket(eu.faircode.netguard.Packet packet) {
    }

    @SuppressWarnings("unused") // Called from native code
    private void dnsResolved(eu.faircode.netguard.ResourceRecord rr) {
    }

    @SuppressWarnings("unused") // Called from native code
    private boolean isDomainBlocked(String name) {
        return false;
    }

    @SuppressWarnings("unused") // Called from native code
    private int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        // Android 10+ 起 C 层读不到 /proc/net，由 Java 侧反射系统 API 反查连接归属（NetGuard 同款方案）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && version == 4) {
            try {
                java.lang.reflect.Method method = ConnectivityManager.class.getMethod(
                        "getConnectionOwnerUid",
                        int.class, java.net.InetSocketAddress.class, java.net.InetSocketAddress.class);
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Object result = method.invoke(cm, protocol,
                        new java.net.InetSocketAddress(saddr, sport),
                        new java.net.InetSocketAddress(daddr, dport));
                if (result instanceof Integer) {
                    return (int) result;
                }
            } catch (Throwable t) {
                Log.w(TAG, "getConnectionOwnerUid unavailable: " + t);
            }
        }
        return -1;
    }

    @SuppressWarnings("unused") // Called from native code
    private eu.faircode.netguard.Allowed isAddressAllowed(eu.faircode.netguard.Packet packet) {
        // 新 UDP 会话建立时记录「客户端源端口 → 渠道包名」，供 PacketAnalyzer 判定 authkey 渠道
        if (packet.protocol == 17)
            recordPortPackage(packet.sport, packet.uid);
        return new eu.faircode.netguard.Allowed();
    }

    private void recordPortPackage(int sport, int uid) {
        if (sport <= 0 || uid <= 0) return;
        try {
            String[] pkgs = getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return;
            for (String pkg : pkgs) {
                for (String app : ALLOWED_APPS) {
                    if (app.equals(pkg)) {
                        PORT_PACKAGE.put(sport, pkg);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 查询客户端源端口所属的渠道包名（无记录返回 null）。PacketAnalyzer 调用。 */
    public static String packageForPort(int clientPort) {
        return PORT_PACKAGE.get(clientPort);
    }

    /** 设备上恰好只安装一个渠道包时返回该包名，否则返回 null。PacketAnalyzer 兜底判定用。 */
    public static String soleInstalledApp(@NonNull Context context) {
        String found = null;
        for (String app : ALLOWED_APPS) {
            try {
                context.getPackageManager().getPackageInfo(app, 0);
                if (found != null) return null; // 已装不止一个
                found = app;
            } catch (Exception ignored) {
            }
        }
        return found;
    }

    @SuppressWarnings("unused") // Called from native code
    private void accountUsage(eu.faircode.netguard.Usage usage) {
    }

    /* ===== 前台服务 ===== */

    private void startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.vpn_capture_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        android.app.Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_link)
                .setContentTitle(getString(R.string.vpn_capture_notification_title))
                .setContentText(getString(R.string.vpn_capture_notification_text))
                .build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        try {
            ServiceCompat.startForeground(this, this.hashCode(), notification, type);
        } catch (Exception e) {
            Log.e(TAG, "fail to start foreground service", e);
            stopCapture();
        }
    }

    /** UI 工具：启动/停止抓包服务（供 LinkFragment 调用） */
    public static void start(@NonNull Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, CaptureService.class));
    }

    public static void stop(@NonNull Context context) {
        Intent intent = new Intent(STOP_SERVICE_ACTION);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
