package com.muxiao.Venus.Link;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.muxiao.Venus.BuildConfig;
import com.muxiao.Venus.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADB 抓取抽卡链接：本机 adb 客户端经 mDNS 配对本机 adbd，再从 logcat 过滤 WebView 打印的抽卡 URL。
 * 仅覆盖原神/绝区零（白名单不含 hkrpg，星铁走 {@link CaptureService} 的 VPN 方案）；
 * 无需 root，但需开启无线调试。思路参考 4o3F/Ascent（GPLv3），AOSP adb 产物压缩存于 assets，
 * 首次使用时由本类解压到应用私有目录执行。
 */
public final class AdbGachaCapture {
    private static final String TAG = "AdbGachaCapture";

    /** 系统无线调试的 mDNS 服务类型（adbd 内置广播，见 AOSP adb mdns.cpp） */
    public static final String MDNS_PAIRING_TYPE = "_adb-tls-pairing._tcp";
    public static final String MDNS_CONNECT_TYPE = "_adb-tls-connect._tcp";

    /** 抽卡 URL 白名单（对齐 Ascent 的 grep 域名单）；命中行还需含 gacha。 */
    private static final Pattern WISH_URL_PATTERN = Pattern.compile(
            "https://(?:webstatic(?:-sea|-os)?|hk4e-api(?:-os)?|api-(?:os-)?takumi|gs)\\.(?:mihoyo|hoyoverse)\\.com/\\S+",
            Pattern.CASE_INSENSITIVE);
    private static final String GACHA_TAG = "gacha";

    /** 端口发现回调：port=null 表示超时/失败，回调恰好一次。 */
    public interface PortCallback {
        void onPortDiscovered(@Nullable String port);
    }

    /** mDNS 发现句柄，cancel 幂等。 */
    public interface Discovery {
        void cancel();
    }

    private AdbGachaCapture() {
    }

    /** 内置 adb 路径：从 assets 解压 libadb.so 到 filesDir 并赋可执行位；不支持的 ABI 返回 null。 */
    @Nullable
    public static synchronized String adbPath(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), "adb");
        File adb = new File(dir, "libadb.so");
        if (adb.isFile() && adb.canExecute() && markerMatches(dir))
            return adb.getAbsolutePath();
        // 版本不符或残留半成品：清理后重新解压（升级 adb 二进制时不会沿用旧文件）
        deleteQuietly(adb);
        deleteQuietly(new File(dir, "version"));
        for (String abi : Build.SUPPORTED_ABIS) {
            if (!"arm64-v8a".equals(abi) && !"armeabi-v7a".equals(abi)) continue;
            if (extractAdb(context, abi, adb)) {
                writeMarker(dir);
                return adb.getAbsolutePath();
            }
        }
        return null;
    }

    /** 快速判断是否已解压且版本匹配（不触发解压，可在主线程调用）。 */
    public static boolean isAdbReady(@NonNull Context context) {
        File adb = new File(context.getFilesDir(), "adb/libadb.so");
        return adb.isFile() && adb.canExecute() && markerMatches(adb.getParentFile());
    }

    /** 后台确保 adb 就绪，结果回调切回主线程，避免首次解压卡 UI。 */
    public static void ensureAdb(@NonNull Context context, @NonNull Runnable onReady,
                                 @NonNull Runnable onFailed) {
        if (isAdbReady(context)) {
            onReady.run();
            return;
        }
        new Thread(() -> {
            boolean ok = adbPath(context) != null;
            new Handler(Looper.getMainLooper()).post(ok ? onReady : onFailed);
        }, "AdbExtract").start();
    }

    private static boolean markerMatches(@Nullable File dir) {
        if (dir == null) return false;
        File marker = new File(dir, "version");
        if (!marker.isFile()) return false;
        try (FileInputStream in = new FileInputStream(marker)) {
            byte[] data = new byte[32];
            int n = in.read(data);
            return n > 0 && new String(data, 0, n, StandardCharsets.US_ASCII).trim()
                    .equals(String.valueOf(BuildConfig.VERSION_CODE));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMarker(@NonNull File dir) {
        try (FileOutputStream out = new FileOutputStream(new File(dir, "version"))) {
            out.write(String.valueOf(BuildConfig.VERSION_CODE).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.w(TAG, "write adb marker failed", e);
        }
    }

    private static boolean extractAdb(@NonNull Context context, @NonNull String abi, @NonNull File dest) {
        File dir = dest.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        File tmp = new File(dir, "libadb.so.tmp");
        try (InputStream in = context.getAssets().open("adb/" + abi + "/libadb.bin");
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            Log.w(TAG, "extract libadb failed: " + abi, e);
            deleteQuietly(tmp);
            return false;
        }
        if (!tmp.renameTo(dest)) {
            deleteQuietly(tmp);
            return false;
        }
        if (!dest.setExecutable(true, false)) {
            deleteQuietly(dest);
            return false;
        }
        return true;
    }

    /**
     * 执行 adb 命令，超时/非零退出/失败输出均抛 RuntimeException。
     * HOME 指向应用私有目录，adb 密钥才会落在 $HOME/.android/adbkey*；
     * stdout/stderr 合并为单流避免互锁，守护线程超时强杀进程。
     */
    @NonNull
    public static String runAdb(@NonNull Context context, long timeoutMs, @NonNull String... args) {
        return runAdb(context, timeoutMs, true, args);
    }

    @NonNull
    private static String runAdb(@NonNull Context context, long timeoutMs,
                                 boolean checkFailedOutput, @NonNull String... args) {
        String adb = adbPath(context);
        if (adb == null)
            throw new IllegalStateException(context.getString(R.string.adb_error_missing_binary));
        String[] cmd = new String[args.length + 1];
        cmd[0] = adb;
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("adb " + args[0] + " exec failed", e);
        }
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            if (!waitForExit(process, timeoutMs)) {
                timedOut.set(true);
                destroyForcibly(process);
            }
        }, "AdbWatchdog");
        watchdog.start();
        String output;
        try {
            output = readAll(process.getInputStream());
            watchdog.join(1_000);
        } catch (IOException e) {
            destroyForcibly(process);
            throw new RuntimeException("adb " + args[0] + " io failed", e);
        } catch (InterruptedException e) {
            destroyForcibly(process);
            Thread.currentThread().interrupt();
            throw new RuntimeException("adb " + args[0] + " interrupted");
        }
        String trimmed = output.trim();
        if (timedOut.get())
            throw new RuntimeException("adb " + args[0] + " timeout");
        if (process.exitValue() != 0)
            throw new RuntimeException(firstMeaningfulLine(output, "exit " + process.exitValue()));
        // 该启发式仅适用于 pair/connect 这类短命令；logcat 轮询必须用 lenient 版
        if (checkFailedOutput && trimmed.toLowerCase(Locale.ROOT).contains("failed"))
            throw new RuntimeException(firstMeaningfulLine(output, "failed"));
        return output;
    }

    /** 不做 failed 启发式检查的 adb 执行（logcat 轮询专用）。 */
    @NonNull
    public static String runAdbLenient(@NonNull Context context, long timeoutMs, @NonNull String... args) {
        return runAdb(context, timeoutMs, false, args);
    }

    /**
     * 等进程退出：{@code Process#waitFor(long, TimeUnit)} 与 {@code Process#destroyForcibly()} 都是 API 26 起，
     * 本应用 minSdk 24；低版本改用「守护线程阻塞 {@code waitFor()} + 带超时 latch」，不用轮询（不烧 CPU）。
     *
     * @return true 表示进程已结束（含「被中断后主动杀掉」的情形）；false 表示超时且已被强杀
     */
    private static boolean waitForExit(@NonNull Process process, long timeoutMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                destroyForcibly(process);
                Thread.currentThread().interrupt();
                return true;
            }
        }
        // API 24/25 的 waitFor() 没有超时重载：交给守护线程阻塞等，本线程用带超时的 latch 等结果。
        // 既不空转 CPU，也保留强杀语义（超时 → destroy 后立即返回 false）。
        CountDownLatch exited = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exited.countDown();
            }
        }, "AdbWait");
        waiter.setDaemon(true);
        waiter.start();
        try {
            if (exited.await(timeoutMs, TimeUnit.MILLISECONDS)) return true;
            destroyForcibly(process);
            return false;
        } catch (InterruptedException ignored) {
            destroyForcibly(process);
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** {@code Process#destroyForcibly()} 是 API 26 起，低版本退化为 {@code destroy()}（adbd 侧仍会回收）。 */
    private static void destroyForcibly(@NonNull Process process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly();
        else process.destroy();
    }

    /** 清理残留：删不掉不影响后续流程（下次解压会重写同名文件），仅留日志便于排查权限问题。 */
    private static void deleteQuietly(@NonNull File file) {
        if (!file.delete() && file.exists())
            Log.w(TAG, "delete failed: " + file.getAbsolutePath());
    }

    /** 对 Ascent「pair/connect 前先 start-server」的封装：幂等，已运行的 adb server 直接复用。 */
    public static void ensureServer(@NonNull Context context) {
        runAdb(context, 30_000, "start-server", context.getApplicationInfo().dataDir);
    }

    /** 配对本机 adbd（SPAKE2/证书交换由 adb 完成）；须在系统「配对码」弹窗打开期间调用。 */
    public static void pair(@NonNull Context context, @NonNull String port, @NonNull String code) {
        ensureServer(context);
        // 参数形态对齐 Ascent：pair 127.0.0.1:<port> <code> <dataDir>
        String out = runAdb(context, 60_000, "pair", "127.0.0.1:" + port, code,
                context.getApplicationInfo().dataDir);
        if (out.toLowerCase(Locale.ROOT).contains("failed"))
            throw new RuntimeException(firstMeaningfulLine(out, "pair failed"));
    }

    /** 连接本机 adbd 并校验 devices 列表。 */
    public static void connect(@NonNull Context context, @NonNull String port) {
        String out = runAdb(context, 30_000, "connect", "127.0.0.1:" + port);
        String devices = runAdb(context, 15_000, "devices");
        // 就绪判定：devices 含 127.0.0.1 且无 offline
        if (!devices.contains("127.0.0.1") || devices.toLowerCase(Locale.ROOT).contains("offline"))
            throw new RuntimeException(firstMeaningfulLine(out + "\n" + devices, "connect not ready"));
    }

    /** mDNS 发现配对端口（自动回填对话框）；找到第一个实例即回调并停止。 */
    @NonNull
    public static Discovery discoverPairingPort(@NonNull Context context,
                                                long timeoutMs,
                                                @NonNull PortCallback callback) {
        return discoverPort(context, MDNS_PAIRING_TYPE, timeoutMs, callback);
    }

    /** 阻塞发现连接端口（无线调试开启期间常驻广播）；超时返回 null。 */
    @Nullable
    public static String discoverConnectPortBlocking(@NonNull Context context, long timeoutMs) {
        return discoverPortBlocking(context, MDNS_CONNECT_TYPE, timeoutMs);
    }

    /** 阻塞发现配对端口：服务重启丢失 pairingMode 时的一次快速兜底。 */
    @Nullable
    public static String discoverPairingPortBlocking(@NonNull Context context, long timeoutMs) {
        return discoverPortBlocking(context, MDNS_PAIRING_TYPE, timeoutMs);
    }

    @Nullable
    private static String discoverPortBlocking(@NonNull Context context,
                                               @NonNull String serviceType,
                                               long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> found = new AtomicReference<>();
        Discovery discovery = discoverPort(context, serviceType, timeoutMs, port -> {
            found.set(port);
            latch.countDown();
        });
        try {
            // 稍长等待，确保回调落定；超时与否不影响结论——found 为空即视为未发现
            if (!latch.await(timeoutMs + 1_000, TimeUnit.MILLISECONDS))
                Log.d(TAG, "mdns " + serviceType + " discovery timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        discovery.cancel();
        return found.get();
    }

    /** 原始 mDNS（对齐 Ascent）：自组 PTR 查询并解析 SRV 端口，绕过 ROM 的 NsdManager 差异。 */
    @NonNull
    private static Discovery discoverPort(@NonNull Context context,
                                          @NonNull String serviceType,
                                          long timeoutMs,
                                          @NonNull PortCallback callback) {
        AtomicBoolean done = new AtomicBoolean(false);
        Handler handler = new Handler(Looper.getMainLooper());
        MulticastSocket[] socketRef = new MulticastSocket[1];
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("VenusAdbMdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock failed", e);
            lock = null;
        }
        final WifiManager.MulticastLock finalLock = lock;
        Thread thread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            // try-with-resources：成功 / 超时 / cancel() 外部关闭 / 异常四条路径都由它收口。
            // close() 自带「离开全部已加入的组」语义，无需再单独 leaveGroup。
            try (MulticastSocket socket = new MulticastSocket(null)) {
                // 先落 ref 再 bind：cancel() 才能在任意时刻关掉它，让 receive 立刻抛异常退出
                socketRef[0] = socket;
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(5353));
                InetAddress group = InetAddress.getByName("224.0.0.251");
                socket.joinGroup(group);
                socket.setSoTimeout(250);
                socket.setTimeToLive(255);
                byte[] query = buildPtrQuery(serviceType);
                DatagramPacket request = new DatagramPacket(query, query.length, group, 5353);
                long nextQuery = 0;
                while (!done.get() && System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextQuery) {
                        socket.send(request);
                        nextQuery = System.currentTimeMillis() + 1_000;
                    }
                    try {
                        byte[] buf = new byte[4096];
                        DatagramPacket response = new DatagramPacket(buf, buf.length);
                        socket.receive(response);
                        int port = parseSrvPort(response.getData(), response.getLength(), serviceType);
                        if (port > 0) {
                            finishDiscovery(done, handler, callback, String.valueOf(port));
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "mdns discovery failed for " + serviceType, e);
            } finally {
                if (finalLock != null) {
                    try { finalLock.release(); } catch (Exception ignored) {}
                }
                finishDiscovery(done, handler, callback, null);
            }
        }, "AdbMdns-" + serviceType);
        thread.start();
        return () -> {
            if (!done.compareAndSet(false, true)) return;
            thread.interrupt();
            MulticastSocket socket = socketRef[0];
            if (socket != null) socket.close();
        };
    }

    private static void finishDiscovery(AtomicBoolean done, Handler handler,
                                        PortCallback callback, @Nullable String port) {
        if (!done.compareAndSet(false, true)) return;
        handler.post(() -> callback.onPortDiscovered(port));
    }

    private static byte[] buildPtrQuery(@NonNull String serviceType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 1);   // transaction id
        writeShort(out, 0);   // standard query
        writeShort(out, 1);   // QDCOUNT
        writeShort(out, 0);   // ANCOUNT
        writeShort(out, 0);   // NSCOUNT
        writeShort(out, 0);   // ARCOUNT
        for (String label : (serviceType + ".local").split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
        writeShort(out, 12);  // PTR
        writeShort(out, 1);   // IN
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    /** 解析 DNS 响应中的 SRV 记录，只认目标服务类型，取第一个有效端口。 */
    private static int parseSrvPort(byte[] data, int length, @NonNull String serviceType) {
        if (length < 12) return -1;
        int qd = ((data[4] & 0xff) << 8) | (data[5] & 0xff);
        int an = ((data[6] & 0xff) << 8) | (data[7] & 0xff);
        int pos = 12;
        int[] end = new int[1];
        for (int i = 0; i < qd && pos < length; i++) {
            readDnsName(data, length, pos, end);
            pos = end[0] + 4;
        }
        String wanted = serviceType.toLowerCase(Locale.ROOT);
        for (int i = 0; i < an && pos + 10 <= length; i++) {
            String name = readDnsName(data, length, pos, end);
            pos = end[0];
            int type = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
            int rdlen = ((data[pos + 8] & 0xff) << 8) | (data[pos + 9] & 0xff);
            pos += 10;
            if (type == 33 && rdlen >= 6 && pos + 6 <= length
                    && name.toLowerCase(Locale.ROOT).contains(wanted)) {
                int port = ((data[pos + 4] & 0xff) << 8) | (data[pos + 5] & 0xff);
                if (port > 0) return port;
            }
            pos += rdlen;
        }
        return -1;
    }

    private static String readDnsName(byte[] data, int length, int offset, int[] end) {
        StringBuilder name = new StringBuilder();
        int pos = offset;
        boolean jumped = false;
        int terminal = offset;
        for (int hops = 0; hops < 64 && pos < length; hops++) {
            int len = data[pos] & 0xff;
            if ((len & 0xc0) == 0xc0) {
                if (pos + 1 >= length) break;
                int ptr = ((len & 0x3f) << 8) | (data[pos + 1] & 0xff);
                if (!jumped) {
                    terminal = pos + 2;
                    jumped = true;
                }
                pos = ptr;
                continue;
            }
            if (len == 0) {
                if (!jumped) terminal = pos + 1;
                break;
            }
            if (pos + 1 + len > length) break;
            if (name.length() > 0) name.append('.');
            name.append(new String(data, pos + 1, len, StandardCharsets.US_ASCII));
            pos += 1 + len;
        }
        end[0] = Math.max(terminal, pos);
        return name.toString();
    }

    /** 取 logcat 中最后一条同时含 gacha 与白名单域名的行，并截取其中的 URL。 */
    @Nullable
    public static String extractWishUrl(@Nullable String logcat) {
        if (logcat == null || logcat.isEmpty()) return null;
        String best = null;
        for (String line : logcat.split("\n")) {
            if (!line.toLowerCase(Locale.ROOT).contains(GACHA_TAG)) continue;
            Matcher matcher = WISH_URL_PATTERN.matcher(line);
            if (matcher.find()) best = matcher.group();
        }
        return best;
    }

    /**
     * 读尽流并按 UTF-8 解码。
     */
    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(8192);
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(buffer)) > 0)
                sb.append(buffer, 0, n);
        }
        return sb.toString();
    }

    /** 取第一行非空输出做错误消息；跳过 logcat banner 行。 */
    private static String firstMeaningfulLine(String output, String fallback) {
        for (String line : output.trim().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("---------")) continue;
            return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
        }
        return fallback;
    }
}
