package com.muxiao.Venus.common;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.github.gzuliyujiang.oaid.DeviceID;
import com.github.gzuliyujiang.oaid.IRegisterCallback;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class DeviceUtils {
    private final Context context;

    /**
     * 库的注册闸：全进程只触发一次 OAID 申请。
     *
     * <p>官方推荐用法是 {@code DeviceIdentifier.register(application)} 预取一次、之后统一读库内静态缓存。
     * 这里按同一思路实现，但把闸放在本类而不是 {@code VenusApplication}：保证「无论哪个使用点先碰到设备标识，
     * 都只注册一次」。分散到多处调用会重复发起申请——原实现正是如此，构造一次就 {@code getOAID} 一次。
     */
    private static final AtomicBoolean REGISTER_STARTED = new AtomicBoolean(false);

    /**
     * 当前是否已明确知道 OAID 取不到。
     *
     * <p>改读缓存后，{@code IGetter.onOAIDGetError} 那条「失败即刻回退」的路径没了，失败信号只能从注册回调拿。
     * 没有这个标记，OAID 不可用的机型要白等满 5 秒才落到 UUID 回退。
     */
    private static volatile boolean oaidUnavailable = false;

    /** 等待 OAID 就绪的上限与轮询间隔。 */
    private static final long POLL_TIMEOUT_MS = 5000;
    private static final long POLL_INTERVAL_MS = 100;

    /**
     * 设备标识最终解析结果（OAID 或回退 UUID）。缓存有两个作用：① 避免同一实例内多次调用各自阻塞最多 5 秒；
     * ② 保证 device_id 与 ext_fields 中 aaid/oaid/vaid 完全一致（原实现两次独立解析，OAID 迟到时会给出不同值）。
     */
    private volatile String resolvedDeviceId;

    public DeviceUtils(Context context) {
        this.context = context;
        ensureRegistered(context);
    }

    /**
     * 触发库的注册（全进程一次）：它会异步申请一次 OAID 并写入库内静态缓存，
     * 之后任意实例都靠 {@link DeviceID#getOAID()} 读同一份缓存，不再各自发起申请。
     */
    private static void ensureRegistered(Context context) {
        if (!REGISTER_STARTED.compareAndSet(false, true)) return;
        Context appContext = context == null ? null : context.getApplicationContext();
        if (!(appContext instanceof Application)) {
            REGISTER_STARTED.set(false);
            return;
        }
        try {
            // tryWidevine 传 false：Widevine ID 本项目用不到，官方也注明它在部分机型上会卡死或闪退
            DeviceID.register((Application) appContext, false, new IRegisterCallback() {
                @Override
                public void onComplete(String clientId, Exception error) {
                    oaidUnavailable = error != null;
                }
            });
        } catch (Throwable t) {
            // 各厂商实现是运行期动态解析的（华为/荣耀的 Ads 标识类、各家 MSA/AIDL 远程服务），
            // 类或服务不可用时抛的是 NoClassDefFoundError 一类 Error，用 Exception 兜不住，会直接崩进程。
            oaidUnavailable = true;
            REGISTER_STARTED.set(false); // 放行后续实例重试
        }
    }

    /**
     * 阻塞等待 OAID 就绪（最长 5 秒，每 100ms 轮询一次库内静态缓存）；超时、中断或已知不可用时
     * 回退到基于 Android ID 生成的稳定 UUID。
     */
    public String waitForDeviceId() {
        String cached = resolvedDeviceId;
        if (cached != null) return cached;

        String resolved = pollOaidOrFallback();
        resolvedDeviceId = resolved;
        return resolved;
    }

    /**
     * 轮询库内缓存的 OAID：纯内存读，不产生任何 IPC。
     *
     * <p>因此多个 {@code DeviceUtils} 实例同时等待也只会对应上游那一次真实申请，
     * 这与原实现「每个实例各自 {@code getOAID}、各自 bindService」有本质区别。
     */
    private String pollOaidOrFallback() {
        if (oaidUnavailable) return generateDeviceId();

        long waitedTime = 0;
        while (waitedTime < POLL_TIMEOUT_MS) {
            String cached = DeviceID.getOAID();
            if (cached != null && !cached.isEmpty()) return cached;
            // 等待期间也要能感知注册失败：回调可能在另一条线程刚把它置位
            if (oaidUnavailable) return generateDeviceId();
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return generateDeviceId();
            }
            waitedTime += POLL_INTERVAL_MS;
        }
        return generateDeviceId();
    }

    /**
     * OAID 获取失败时生成稳定设备指纹：以 Android ID + 厂商/型号为命名空间，按 NameUUID（MD5，v3 UUID）算法生成。
     */
    private String generateDeviceId() {
        @SuppressLint("HardwareIds") String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String namespace = androidId != null ? androidId : "unknown";
        String name = Build.MANUFACTURER + " " + Build.MODEL;
        UUID namespaceUUID = UUID.nameUUIDFromBytes(namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long msb = namespaceUUID.getMostSignificantBits();
        long lsb = namespaceUUID.getLeastSignificantBits();
        byte[] namespaceBytes = new byte[16];
        for (int i = 0; i < 8; i++)
            namespaceBytes[i] = (byte) (msb >>> (8 * (7 - i)));
        for (int i = 8; i < 16; i++)
            namespaceBytes[i] = (byte) (lsb >>> (8 * (15 - i)));
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] combinedBytes = new byte[namespaceBytes.length + nameBytes.length];
        System.arraycopy(namespaceBytes, 0, combinedBytes, 0, namespaceBytes.length);
        System.arraycopy(nameBytes, 0, combinedBytes, namespaceBytes.length, nameBytes.length);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] hashBytes = md.digest(combinedBytes);
        long mostSignificantBits = 0;
        long leastSignificantBits = 0;
        for (int i = 0; i < 8; i++)
            mostSignificantBits = (mostSignificantBits << 8) | (hashBytes[i] & 0xff);
        for (int i = 8; i < 16; i++)
            leastSignificantBits = (leastSignificantBits << 8) | (hashBytes[i] & 0xff);
        // 置 UUID version 3 与 DCE 1.1 variant（魔法位来自 nameUUID 规范）
        mostSignificantBits &= ~0x000000000000F000L;
        mostSignificantBits |= 0x0000000000003000L;
        leastSignificantBits &= ~0xC000000000000000L;
        leastSignificantBits |= 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    /** 构建 device_fp 接口所需的 ext_fields 设备信息 JSON（含 CPU、存储、内存、传感器等）。 */
    public String getExtFields() {
        String deviceId = waitForDeviceId();
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("cpuType", android.os.Build.SUPPORTED_ABIS[0]);
        jsonObject.addProperty("romCapacity", String.valueOf(getTotalStorageSpace()));
        jsonObject.addProperty("productName", android.os.Build.PRODUCT);
        jsonObject.addProperty("romRemain", String.valueOf(getAvailableStorageSpace()));
        jsonObject.addProperty("manufacturer", android.os.Build.MANUFACTURER);
        jsonObject.addProperty("appMemory", String.valueOf(getTotalMemory()));
        jsonObject.addProperty("hostname", android.os.Build.HOST);
        jsonObject.addProperty("screenSize", context.getResources().getDisplayMetrics().widthPixels + "x" + context.getResources().getDisplayMetrics().heightPixels);
        jsonObject.addProperty("osVersion", String.valueOf(android.os.Build.VERSION.SDK_INT));
        jsonObject.addProperty("aaid", deviceId);
        jsonObject.addProperty("vendor", android.os.Build.BRAND);
        jsonObject.addProperty("accelerometer", getSensorInfo("accelerometer"));
        jsonObject.addProperty("buildTags", android.os.Build.TAGS);
        jsonObject.addProperty("model", android.os.Build.MODEL);
        jsonObject.addProperty("brand", android.os.Build.BRAND);
        jsonObject.addProperty("oaid", deviceId);
        jsonObject.addProperty("hardware", android.os.Build.HARDWARE);
        jsonObject.addProperty("deviceType", android.os.Build.DEVICE);
        jsonObject.addProperty("devId", android.os.Build.VERSION.RELEASE);
        jsonObject.addProperty("serialNumber", "UNKNOWN");
        jsonObject.addProperty("buildTime", String.valueOf(android.os.Build.TIME));
        jsonObject.addProperty("buildUser", android.os.Build.USER);
        jsonObject.addProperty("ramCapacity", String.valueOf(getTotalMemory()));
        jsonObject.addProperty("magnetometer", getSensorInfo("magnetometer"));
        jsonObject.addProperty("display", android.os.Build.DISPLAY);
        jsonObject.addProperty("ramRemain", String.valueOf(getAvailableRam()));
        jsonObject.addProperty("deviceInfo", android.os.Build.MANUFACTURER + "/" + android.os.Build.DEVICE + "/" + android.os.Build.BOARD + ":" + android.os.Build.VERSION.RELEASE + "/" + android.os.Build.ID + "/" + android.os.Build.VERSION.INCREMENTAL + ":" + android.os.Build.TYPE + "/" + android.os.Build.TAGS);
        jsonObject.addProperty("gyroscope", getSensorInfo("gyroscope"));
        jsonObject.addProperty("vaid", deviceId);
        jsonObject.addProperty("buildType", android.os.Build.TYPE);
        jsonObject.addProperty("sdkVersion", android.os.Build.VERSION.SDK_INT);
        jsonObject.addProperty("board", android.os.Build.BOARD);
        return gson.toJson(jsonObject);
    }

    /** 根分区总存储（MB），用于 ext_fields 上报。 */
    private long getTotalStorageSpace() {
        android.os.StatFs statFs = new android.os.StatFs(android.os.Environment.getRootDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long totalBlocks = statFs.getBlockCountLong();
        return (totalBlocks * blockSize) / (1024 * 1024);
    }

    /** 根分区可用存储（MB）。 */
    private long getAvailableStorageSpace() {
        android.os.StatFs statFs = new android.os.StatFs(android.os.Environment.getRootDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long availableBlocks = statFs.getAvailableBlocksLong();
        return (availableBlocks * blockSize) / (1024 * 1024);
    }

    /** 设备总内存（MB）。 */
    private long getTotalMemory() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.totalMem / (1024 * 1024);
    }


    /** 设备可用内存（MB）。 */
    private long getAvailableRam() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / (1024 * 1024);
    }

    /** 三轴传感器的固定标定值，用于 ext_fields 伪装设备特征。 */
    private String getSensorInfo(String sensorType) {
        switch (sensorType) {
            case "accelerometer":
                return "0.061016977x0.8362915x9.826724";
            case "magnetometer":
                return "80.64375x-14.1x77.90625";
            case "gyroscope":
            default:
                return "0.0x0.0x0.0";
        }
    }
}
