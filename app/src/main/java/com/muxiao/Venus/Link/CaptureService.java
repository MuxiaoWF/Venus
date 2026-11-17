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
* 链路：游戏流量 → VpnService TUN（白名单星铁各渠道包）→ Unix socketpair 双向中转 → netguard JNI 用户态协议栈
* 转发到真实网络；转发过程中每包旁路镜像给 PacketAnalyzer 过滤分析，命中 KCP 链接里的 authkey 即广播 ACTION_GET_AUTHKEY。
* <p>
* 云游戏凭证不能走此路（曾把 com.miHoYo.cloudgames.Nap 加入白名单实测）：云游戏 API 全走 HTTPS，纯转发型 VPN
* 不做 TLS 中间人看不到请求头，串流/DNS 通道亦无明文泄露（见 docs/module-network-auth.md §7），故改用 WebView 抓取。
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
    /** 前台通知 id（固定值）：曾用 this.hashCode()，服务重建后 id 变化会残留「僵尸通知」；与任务体系 50629+ 段隔离 */
    private static final int NOTIFICATION_ID = 50640;
    private static final int MAX_PACKET_SIZE = 8192;
    /**
     * VPN 接口 MTU：与普通 VPN 的常规值（1500）对齐。C 层 get_mtu()（app/src/main/cpp/netguard/ip.c，决定 TUN 读缓冲
     * 与下游 TCP MSS）必须与本值同步修改，否则会出现分段超出 TUN MTU 或指纹不一致。
     */
    private static final int VPN_MTU = 1500;

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
    /** 收尾中（stopCapture 幂等闸）：转发线程据此区分「正常关闭」与「隧道意外死亡」 */
    private volatile boolean stopping = false;
    /** 本轮抓包的旁路分析器（含独立分析线程，stopCapture 时停止） */
    private PacketAnalyzer analyzer;
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
        // 判据只看 pfd：VPN 一旦建立就不能重入。此前还要求 tunnelThread!=null，可一旦 native 隧道线程
        // 先退出（该字段被置空）就会放行重入——重绑 socketpair 后 native 仍持旧 fd，隧道立刻变黑洞
        // （游戏断网、登录报网络错误），且只能杀进程恢复。
        if (pfd != null) {
            Log.i(TAG, "already running, ignore duplicate start");
            return START_STICKY;
        }
        startVpn();
        stopping = false; // 新一轮抓包：清掉可能残留的收尾标志（上次启动失败可能置过位）
        startCapture();
        initNative();
        startNative();
        // 每次启动抓包时检查星铁初始密钥表更新（HsrKeyStore 内部节流：距上次成功拉取
        // 不足 24h 直接跳过，正常情况零请求；拉取/合并均在后台线程池）
        HsrKeyStore.checkForUpdateAsync(getApplicationContext(), false);
        // START_STICKY：进程被系统回收后系统会以 null intent 重新拉起（startVpn 会用已授权的
        // VpnService 重新 establish）。显式声明意图，行为与父类默认一致但更明确——
        // 注意这里只覆盖「被系统杀掉」的情况，用户主动停止走 stopCapture
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        stopping = false;
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

    /**
     * 收尾：停分析线程 → 停 native 隧道 → 关 socketpair → 关 VPN → 撤前台通知。
     * 幂等（转发线程死亡时也会调到这里），重复调用只生效一次。
     */
    private synchronized void stopCapture() {
        if (stopping) return;
        stopping = true;
        capturedChannel = null;
        if (analyzer != null) {
            analyzer.stop();
            analyzer = null;
        }
        stopNative();
        stopForward();
        stopVpn();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startVpn() {
        if (pfd != null) return;
        VpnService.Builder builder = new VpnService.Builder();
        builder.setMtu(VPN_MTU);
        builder.addAddress("10.1.10.1", 32);
        builder.addRoute("0.0.0.0", 0);
        // 声明 DNS 服务器（普通 VPN 均有，DNS-less 的 VPN 网络是可观测的反常指纹）：
        // 优先继承当前网络的 DNS，取不到时兜底公共 DNS，保证非空
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network active = cm == null ? null : cm.getActiveNetwork();
            android.net.LinkProperties lp = active == null ? null : cm.getLinkProperties(active);
            java.util.List<java.net.InetAddress> dns =
                    lp == null ? null : lp.getDnsServers();
            // 只声明 IPv4 DNS：netguard 仅转发 IPv4（cpp 下无 ip6.c），隧道内 IPv6 服务器
            // （尤其 fe80:: 链路本地地址）是不可达的死地址，会导致解析轮询超时，
            // SDK 连通性自检失败后误报"检测到代理"
            java.util.List<java.net.InetAddress> v4 = new java.util.ArrayList<>();
            if (dns != null)
                for (java.net.InetAddress d : dns)
                    if (d instanceof java.net.Inet4Address)
                        v4.add(d);
            if (v4.isEmpty()) {
                builder.addDnsServer(java.net.InetAddress.getByName("223.5.5.5"));
                Log.i(TAG, "vpn dns: fallback 223.5.5.5 (raw=" + dns + ")");
            } else {
                for (java.net.InetAddress d : v4)
                    builder.addDnsServer(d);
                int dropped = (dns == null ? 0 : dns.size()) - v4.size();
                Log.i(TAG, "vpn dns: inherited v4 " + v4
                        + (dropped > 0 ? " (dropped " + dropped + " non-ipv4: " + dns + ")" : ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "fail to configure vpn dns", e);
        }
        // 应用白名单：只放行本机实际安装的渠道（未安装渠道传给系统也会被拒绝），
        // 最小化 VPN 配置暴露给系统服务的包名集合，并留痕本次实际放行列表便于排查
        StringBuilder allowed = new StringBuilder();
        for (String app : ALLOWED_APPS) {
            try {
                getPackageManager().getPackageInfo(app, 0);
                builder.addAllowedApplication(app);
                if (allowed.length() > 0) allowed.append(", ");
                allowed.append(app);
            } catch (NameNotFoundException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Log.i(TAG, "vpn allowed apps: " + allowed);
        // 白名单为空时 VpnService 会捕获全设备所有应用的流量（会话表瞬间爆满，
        // 新建连接被 netguard 丢弃），必须留痕定位
        if (allowed.length() == 0)
            Log.w(TAG, "vpn allowed apps EMPTY! VPN will capture ALL apps' traffic"
                    + " (check manifest <queries> package visibility)");
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

    /**
     * 建立 TUN 与 netguard 之间的双向转发：TUN 出向 → socketpair → netguard（jni_run 阻塞读 localPfd），
     * netguard 回包 → socketpair → TUN；两条线程各持一个方向，转发的同时把每包镜像给 PacketAnalyzer（旁路，不影响转发）。
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
        analyzer.start();
        this.analyzer = analyzer;
        forward(pfd, remotePfd, analyzer);
        forward(remotePfd, pfd, analyzer);
    }

    private void forward(ParcelFileDescriptor src, ParcelFileDescriptor dst,
                         PacketAnalyzer analyzer) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            boolean abnormal = false;
            try (ParcelFileDescriptor.AutoCloseInputStream in =
                         new ParcelFileDescriptor.AutoCloseInputStream(src);
                 ParcelFileDescriptor.AutoCloseOutputStream out =
                         new ParcelFileDescriptor.AutoCloseOutputStream(dst)) {
                while (!stopping) {
                    int length = in.read(buffer, 0, MAX_PACKET_SIZE);
                    // -1 = 对端已关闭（socketpair/tunnel 死了）。旧实现写成 `length <= 0 → continue`，
                    // 会在 EOF 上空转成 100% CPU 死循环：转发已停、VPN 接口还挂着，游戏侧就是彻底的黑洞。
                    if (length < 0) {
                        Log.w(TAG, "forward stream EOF, tunnel is gone");
                        abnormal = true;
                        break;
                    }
                    if (length == 0) continue;
                    out.write(buffer, 0, length);
                    try {
                        // 旁路镜像绝不允许影响转发：分析侧任何异常（含 OOM）只丢这一包
                        analyzer.offerIpPacket(buffer, length);
                    } catch (Throwable mirrorFailure) {
                        Log.w(TAG, "mirror packet failed", mirrorFailure);
                    }
                }
            } catch (Exception e) {
                // 正常停止时流关闭会抛 IOException；其余异常意味着转发线程提前死亡
                // （隧道黑洞、游戏侧表现为断网重连并反复触发系统网络状态事件），必须留痕排查
                if (e instanceof java.io.IOException)
                    Log.i(TAG, "forward thread exit (stream closed)", e);
                else {
                    Log.w(TAG, "forward thread died unexpectedly", e);
                    abnormal = true;
                }
            }
            if (abnormal && !stopping)
                onTunnelBroken();
        }, "CaptureForward");
        t.start();
    }

    /**
     * 转发线程非正常退出：立即收尾释放 VPN。否则 VPN 接口还在、流量全进黑洞，
     * 游戏侧表现为「开着 VPN 但一直网络错误」，且怎么重试都不恢复（服务状态仍显示抓包中）。
     */
    private void onTunnelBroken() {
        if (stopping) return;
        Log.e(TAG, "tunnel broken, stopping capture to release vpn");
        // 不在转发线程里同步收尾：stopCapture 会 join 隧道线程，分开线程避免互等
        new Thread(this::stopCapture, "CaptureTeardown").start();
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
        Thread t = tunnelThread;
        if (t == null) return;
        jni_stop(jni_context);
        // 有界等待：隧道线程可能卡在 socketpair 写入（对端转发线程已死），无限 join 会让收尾永远做不完，
        // VPN 接口也就永远不释放——那正是「游戏一直网络错误」的成因之一
        long deadline = System.currentTimeMillis() + 1500;
        while (t.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Log.i(TAG, "tunnel thread is interrupted");
                break;
            }
        }
        tunnelThread = null;
        if (t.isAlive())
            Log.w(TAG, "tunnel thread still alive after stop, skip jni_clear");
        else
            jni_clear(jni_context);
        Log.i(TAG, "stop native");
    }

    // JNI 导出函数（C 侧符号：Java_com_muxiao_Venus_Link_CaptureService_jni_*）：仅保留本服务实际使用的入口，
    // C 侧同符号导出（get_mtu/get_stats/pcap/socks5）在未声明时成为死导出，无副作用。
    private native long jni_init(int sdk);

    private native void jni_start(long context, int loglevel);

    private native void jni_run(long context, int tun, boolean fwd53, int rcode);

    private native void jni_stop(long context);

    private native void jni_clear(long context);

    private native void jni_done(long context);

    /* JNI 回调：C 侧经 GetMethodID 反射调用，勿改名（混淆需 keep）。
     * 以下方法仅在 native 侧被引用，Java 层无调用点：@SuppressWarnings 消除 IDE 的 unused 告警（方法级精确标注）。 */

    @SuppressWarnings("unused") // Called from native code
    private void nativeExit(String reason) {
        Log.w(TAG, "Native exit reason=" + reason);
    }

    @SuppressWarnings("unused") // Called from native code
    private void nativeError(int error, String message) {
        Log.w(TAG, "Native error " + error + ": " + message);
    }

    @SuppressWarnings({"unused", "EmptyMethod"}) // Called from native code
    private void logPacket(CaptureModels.Packet packet) {
    }

    @SuppressWarnings({"unused", "EmptyMethod"}) // Called from native code
    private void dnsResolved(CaptureModels.ResourceRecord rr) {
    }

    @SuppressWarnings({"unused", "SameReturnValue"}) // Called from native code；恒 false 为 C 层契约占位
    private boolean isDomainBlocked(String name) {
        return false;
    }

    /** getConnectionOwnerUid 的反射 Method（只查一次，结果含 null 进程内缓存） */
    private static java.lang.reflect.Method connectionOwnerUidMethod;
    private static boolean connectionOwnerUidResolved;
    /** 连续失败计数与暂停截止时刻：部分 ROM 上该方法恒抛异常，见 getUidQ 注释 */
    private static int connectionOwnerUidFailures;
    private static long connectionOwnerUidSkipUntil;
    private static long connectionOwnerUidWarnAt;
    private static final int CONNECTION_OWNER_MAX_FAILURES = 64;

    @SuppressWarnings("unused") // Called from native code
    private int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        // Android 10+ 起 C 层读不到 /proc/net，由 Java 侧反射系统 API 反查连接归属（NetGuard 同款方案）。
        // 这是「每个新会话」都会走的热路径：Method 只反射查一次；失败按 60s 节流留痕。
        // 原实现每次调用都做一次 getMethod + 打一条 W：ColorOS 上该方法恒抛 InvocationTargetException，
        // 实测 2 分钟刷出 140+ 行日志并持续制造异常对象（GC 频率也随之上升）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || version != 4)
            return -1;
        long now = System.currentTimeMillis();
        if (now < connectionOwnerUidSkipUntil)
            return -1;
        java.lang.reflect.Method method = resolveConnectionOwnerUid();
        if (method == null)
            return -1;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Object result = method.invoke(cm, protocol,
                    new java.net.InetSocketAddress(saddr, sport),
                    new java.net.InetSocketAddress(daddr, dport));
            if (result instanceof Integer) {
                connectionOwnerUidFailures = 0;
                return (int) result;
            }
        } catch (Throwable t) {
            connectionOwnerUidFailures++;
            if (connectionOwnerUidFailures >= CONNECTION_OWNER_MAX_FAILURES) {
                // 连续失败到这个量级说明该 ROM 上永远拿不到：暂停 5 分钟再试，期间不再产生异常对象
                connectionOwnerUidFailures = 0;
                connectionOwnerUidSkipUntil = now + 5 * 60_000L;
                Log.w(TAG, "getConnectionOwnerUid disabled for 5min: " + t);
            } else if (now - connectionOwnerUidWarnAt > 60_000L) {
                connectionOwnerUidWarnAt = now;
                Log.w(TAG, "getConnectionOwnerUid unavailable: " + t);
            }
        }
        return -1;
    }

    /** 反射查找 getConnectionOwnerUid（Android 10+ 才有）：只查一次，找不到就缓存 null 不再重试 */
    private static java.lang.reflect.Method resolveConnectionOwnerUid() {
        if (!connectionOwnerUidResolved) {
            connectionOwnerUidResolved = true;
            try {
                connectionOwnerUidMethod = ConnectivityManager.class.getMethod(
                        "getConnectionOwnerUid",
                        int.class, java.net.InetSocketAddress.class, java.net.InetSocketAddress.class);
            } catch (Throwable ignored) {
                connectionOwnerUidMethod = null;
            }
        }
        return connectionOwnerUidMethod;
    }

    @SuppressWarnings("unused") // Called from native code
    private CaptureModels.Allowed isAddressAllowed(CaptureModels.Packet packet) {
        // 新 UDP/TCP 会话建立时记录「客户端源端口 → 包名」，供 PacketAnalyzer 判定 authkey 渠道
        if (packet.protocol == 17)
            recordPortPackage(packet.sport, packet.uid);
        return new CaptureModels.Allowed();
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

    @SuppressWarnings({"unused", "EmptyMethod"}) // Called from native code
    private void accountUsage(CaptureModels.Usage usage) {
    }

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
                // 常驻 + 不重复提醒：抓包进度对用户无意义，但通知必须存在——它是「本服务在前台运行」的
                // 唯一可视凭据，也是各 ROM 后台清理器判断能否回收的依据（撤掉通知等于自降优先级）
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                // Android 12+ 默认会延迟 ~10s 展示 FGS 通知；抓包是秒级交互，延迟展示期间
                // 用户切回应用看不到任何运行状态，且部分 ROM 会在通知缺失窗口内回收服务
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        try {
            // 固定通知 id：曾用 this.hashCode()，服务被杀后重建会换 id，旧通知残留成"僵尸通知"
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
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
