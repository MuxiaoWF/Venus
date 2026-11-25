package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;
import android.os.Build;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 米游社请求头管理器：按接口生成 Headers，含 DS 签名，自动区分国服/国际服。
 * DS = MD5(salt + t + r [+ body])，不同接口 salt 不同（K2 / LK2 / SALT_6X / SALT_PASSPORT / SALT_4X），
 * 不可合并为通用 Interceptor（见 ApiClient 类注释）。
 */
public class HeaderManager {
    private static final String app_id = "bll8iq97cem8";
    private static final String os_app_id = "6a4js97g007c";
    private static final Random RANDOM = new Random();
    private static final Gson GSON = new Gson();
    /** 每次进程启动生成一次，对应 x-rpc-lifecycle_id */
    private static final String LIFECYCLE_ID = java.util.UUID.randomUUID().toString();

    // 只缓存 DeviceUtils 的结果字符串：静态持有 DeviceUtils 会连带持有 Context 导致泄漏
    private static volatile String cachedDeviceId;
    private static volatile String cachedExtFields;

    /**
     * device_fp 进程级按域缓存：同设备同域取值不变，避免每个签到模块各请求一次。
     * 失败不缓存（下次仍重试）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Boolean, String> CACHED_FP_BY_SERVER =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final MiHoYoBBSConstants BBSconstants;
    private String device_id;
    private final String user_agent;
    private final boolean isOversea;

    private final String currentAppId;
    private final String currentPackageName;
    private final String currentWebBaseUrl;
    private final String currentAppBaseUrl;
    private final String currentOriginRefererUrl;

    public HeaderManager(Context context) {
        this(context, MiHoYoBBSConstants.is_oversea(context));
    }

    public HeaderManager(Context context, boolean forceOversea) {
        this.BBSconstants = new MiHoYoBBSConstants(context);
        initSharedDeviceUtils(context);
        this.isOversea = forceOversea;
        this.user_agent = "Mozilla/5.0 (Linux; Android " + Build.VERSION.SDK_INT + "; " + Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/" + BBSconstants.bbs_version;
        this.currentAppId = isOversea ? os_app_id : app_id;
        this.currentPackageName = isOversea ? MiHoYoBBSConstants.OS_PACKAGE_NAME : MiHoYoBBSConstants.PACKAGE_NAME;
        this.currentWebBaseUrl = isOversea ? Constants.Urls.OS_WEB_BASE_URL : Constants.Urls.WEB_BASE_URL;
        this.currentAppBaseUrl = isOversea ? Constants.Urls.OS_APP_BASE_URL : Constants.Urls.APP_BASE_URL;
        this.currentOriginRefererUrl = isOversea ? Constants.Urls.OS_ORIGIN_REFERER_URL : Constants.Urls.ORIGIN_REFERER_URL;
    }

    /**
     * 预热设备缓存。DeviceUtils 首次等待 OAID 最多阻塞约 5s（含库的一次性注册），主线程调用必 ANR，
     * 故主线程只起后台线程填充后立即返回；非主线程（Application 预热、后台任务）同步填充。
     */
    private static void initSharedDeviceUtils(Context context) {
        if (cachedDeviceId != null && cachedExtFields != null) return;

        boolean isMainThread = Looper.getMainLooper() == Looper.myLooper();
        if (isMainThread) {
            new Thread(() -> fillSharedDeviceUtils(context.getApplicationContext()), "HeaderManager-DeviceInit").start();
            return;
        }

        synchronized (HeaderManager.class) {
            if (cachedDeviceId == null || cachedExtFields == null) {
                fillSharedDeviceUtils(context.getApplicationContext());
            }
        }
    }

    /** 填充静态设备缓存（可能阻塞，须在非主线程调用）。 */
    private static void fillSharedDeviceUtils(Context appContext) {
        synchronized (HeaderManager.class) {
            if (cachedDeviceId != null && cachedExtFields != null) return;
            DeviceUtils utils = new DeviceUtils(appContext);
            cachedDeviceId = utils.waitForDeviceId();
            cachedExtFields = utils.getExtFields();
        }
    }

    private String getDeviceId() {
        if (device_id != null) return device_id;
        device_id = cachedDeviceId != null ? cachedDeviceId : "";
        return device_id;
    }

    /** 每日签到接口，DS = LK2；国际服只带 Referer / Accept-Encoding / 空 Cookie。 */
    public Map<String, String> get_game_login_headers() {
        Map<String, String> h = new HashMap<>();
        if (isOversea) {
            h.put("Referer", "https://act.hoyolab.com/");
            h.put("Accept-Encoding", "gzip, deflate, br");
            h.put("Cookie", "");
            return h;
        }
        h.put("Accept", "application/json; utf-8");
        h.put("x-rpc-channel", Build.MANUFACTURER);
        h.put("Origin", Constants.Urls.WEB_BASE_URL);
        h.put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("User-Agent", user_agent);
        h.put("x-rpc-client_type", "5");
        h.put("Accept-Language", "zh-CN,en-US;q=0.8");
        h.put("X-Requested-With", MiHoYoBBSConstants.PACKAGE_NAME);
        h.put("Cookie", "");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("DS", getDS(BBSconstants.LK2));
        return h;
    }

    /** 米游社通用接口（getLTokenBySToken、getUserMissionsState 等），DS = K2。 */
    public Map<String, String> get_bbs_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json; utf-8");
        h.put("Origin", currentOriginRefererUrl);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        h.put("x-rpc-channel", Build.MANUFACTURER);
        h.put("x-rpc-device_id", getDeviceId());
        h.put("Referer", currentOriginRefererUrl);
        h.put("User-Agent", user_agent);
        h.put("x-rpc-verify_key", currentAppId);
        h.put("DS", getDS(BBSconstants.K2));
        return h;
    }

    /** getTokenByGameToken 登录态转换：无 DS，仅靠 app_id / client_type 等标识。 */
    public Map<String, String> get_game_token_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-aigis", "");
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-device_name", Build.DEVICE);
        h.put("x-rpc-device_model", Build.MODEL);
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-client_type", "4");
        h.put("User-Agent", user_agent);
        return h;
    }

    /** 游戏签到记录类接口：带 device_fp 与 page / tool_version 标识。 */
    public Map<String, String> get_record_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("x-rpc-client_type", "5");
        h.put("User-Agent", user_agent);
        h.put("X-Requested-With", currentPackageName);
        h.put("Origin", currentWebBaseUrl);
        h.put("Referer", currentWebBaseUrl);
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-device_name", Build.DEVICE);
        h.put("x-rpc-page", isOversea ? "v5.3.2-gr-os_#/ys" : "v5.3.2-gr-cn_#/ys");
        h.put("x-rpc-tool_version", isOversea ? "v5.3.2-gr-os" : "v5.3.2-gr-cn");
        h.put("sec-fetch-site", "same-site");
        h.put("sec-fetch-mode", "cors");
        h.put("sec-fetch-dest", "empty");
        h.put("accept", "*/*");
        h.put("accept-language", isOversea ? "en-US" : "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        return h;
    }

    /** getUserGameRolesByStoken（stoken 取角色列表），DS = K2。 */
    public Map<String, String> get_user_game_roles_stoken_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-verify_key", currentAppId);
        h.put("User-Agent", user_agent);
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("Cookie", "");
        h.put("DS", getDS(BBSconstants.K2));
        return h;
    }

    /**
     * loginByPassword（账号密码登录）请求头。
     * DS 必须用账号 SDK 专用 salt 的带 body 算法（{@link #getDS_passport}），用 K2 / LK2 会判签名错误；
     * body 须与实际请求体逐字节一致。
     *
     * @param body  已序列化的请求体 JSON
     * @param aigis 极验二次提交的 x-rpc-aigis，首次传 null 或空串
     */
    public Map<String, String> get_password_headers(String body, String aigis) {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", user_agent);
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-device_name", sanitizeHeaderValue(Build.DEVICE));
        h.put("x-rpc-device_model", sanitizeHeaderValue(Build.MODEL));
        h.put("x-rpc-sys_version", Build.VERSION.RELEASE);
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-sdk_version", MiHoYoBBSConstants.ACCOUNT_SDK_VERSION);
        h.put("x-rpc-account_version", MiHoYoBBSConstants.ACCOUNT_SDK_VERSION);
        h.put("x-rpc-lifecycle_id", LIFECYCLE_ID);
        h.put("x-rpc-aigis", aigis != null ? aigis : "");
        h.put("DS", getDS_passport(body));
        return h;
    }

    /** header 值不可含空格等非法字符，统一替换为 + */
    private static String sanitizeHeaderValue(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._\\-+]", "+");
    }

    /**
     * 短信验证码登录（createLoginCaptcha / loginByMobileCaptcha 共用）。
     * 与 {@link #get_password_headers} 的差异：这两个接口**不带 DS**（线上长期可用）。
     * 若服务端收紧，补 {@code h.put("DS", getDS_passport(body))} 并传入 body 即可。
     *
     * @param aigis 极验完成后回填的 x-rpc-aigis，首次传 null 或空串
     */
    public Map<String, String> get_sms_headers(String aigis) {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", user_agent);
        h.put("Referer", isOversea ? "https://user.hoyolab.com/" : "https://user.miyoushe.com/");
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-device_name", sanitizeHeaderValue(Build.DEVICE));
        h.put("x-rpc-device_model", sanitizeHeaderValue(Build.MODEL));
        h.put("x-rpc-sys_version", Build.VERSION.RELEASE);
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("x-rpc-aigis", aigis != null ? aigis : "");
        return h;
    }

    /**
     * 通行证 V2 换票（{@code ma-cn-session/app/exchange}）请求头：账号 SDK 同族头 + passport salt 带 body 的 DS。
     * 与 {@link #get_password_headers} 的差异：client_type 固定 2、不带 aigis；sdk/account 版本取
     * {@link MiHoYoBBSConstants#PASSPORT_SDK_VERSION}。body 须与实际请求体逐字节一致，否则 -100 判签名错误。
     *
     * @param body 已序列化的换票请求体 JSON
     */
    public Map<String, String> get_exchange_headers(String body) {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", user_agent);
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("x-rpc-sys_version", Build.VERSION.RELEASE);
        h.put("x-rpc-sdk_version", MiHoYoBBSConstants.PASSPORT_SDK_VERSION);
        h.put("x-rpc-account_version", MiHoYoBBSConstants.PASSPORT_SDK_VERSION);
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-device_name", sanitizeHeaderValue(Build.DEVICE));
        h.put("x-rpc-device_model", sanitizeHeaderValue(Build.MODEL));
        h.put("x-rpc-lifecycle_id", LIFECYCLE_ID);
        try {
            String fp = getFp();
            if (!fp.isEmpty()) h.put("x-rpc-device_fp", fp);
        } catch (RuntimeException ignored) {
            // 设备指纹接口不可达时不带该头：换票不依赖它，不该因为 FP 拉取失败而整体失败
        }
        h.put("DS", getDS_passport(body));
        return h;
    }

    /**
     * 国服扫码登录（createQRLogin / queryQRLoginStatus）：DS = X4 salt + 带 body 算法（{@link #getDS_4x}），
     * app_version / sdk_version / account_version 三者同值。
     *
     * @param body 与实际请求体逐字节一致的 JSON
     */
    public Map<String, String> get_passport_qr_headers(String body) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 miHoYoBBS/" + BBSconstants.bbs_version + " Capture/2.2.0");
        h.put("Accept", "*/*");
        h.put("Accept-Language", "zh-cn");
        h.put("Content-Type", "application/json");
        h.put("x-rpc-client_type", "3");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-sdk_version", BBSconstants.bbs_version);
        h.put("x-rpc-account_version", BBSconstants.bbs_version);
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-device_name", sanitizeHeaderValue(Build.DEVICE));
        h.put("x-rpc-device_model", sanitizeHeaderValue(Build.MODEL));
        h.put("DS", getDS_4x(body));
        return h;
    }

    /** 抽卡记录 authkey 接口，DS = LK2。 */
    public Map<String, String> get_authkey_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cookie", "");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-client_type", "5");
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Connection", "Keep-Alive");
        h.put("User-Agent", user_agent);
        h.put("DS", getDS(BBSconstants.LK2));
        return h;
    }

    /** device_fp 获取接口：无 DS。 */
    public Map<String, String> get_fp_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", user_agent);
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-client_type", "5");
        h.put("Referer", currentWebBaseUrl);
        h.put("Origin", currentWebBaseUrl);
        h.put("Content-Type", "application/json; utf-8");
        h.put("Accept-Language", isOversea ? "en-US" : "zh-CN,zh-Hans;q=0.9");
        return h;
    }

    /** 图片接口（与 forum 栏目接口 header 相同） */
    public Map<String, String> get_images_headers() {
        return createImageAndForumHeaders();
    }

    /** 论坛栏目接口（与图片接口 header 相同） */
    public Map<String, String> get_forums_id() {
        return createImageAndForumHeaders();
    }

    /** 图片 / 论坛栏目共用请求头，DS = K2。 */
    private Map<String, String> createImageAndForumHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        h.put("x-rpc-device_name", Build.DEVICE);
        h.put("x-rpc-device_model", Build.MODEL);
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-channel", Build.MANUFACTURER);
        h.put("x-rpc-h256_supported", "0");
        h.put("Referer", currentAppBaseUrl);
        h.put("x-rpc-verify_key", currentAppId);
        h.put("x-rpc-csm_source", "discussion");
        h.put("DS", getDS(BBSconstants.K2));
        return h;
    }

    /** 普通 DS：MD5("salt=<salt>&t=&r=")，t 秒级、r 6 位随机。 */
    private static String getDS(String salt) {
        return generateDS("salt=" + salt);
    }

    /** 签到专用 DS：SALT_6X + body，MD5("salt=&t=&r=&b=body&q=")。 */
    public String getDS_signIn(String body) {
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        StringBuilder r = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 6; i++)
            r.append(chars.charAt(RANDOM.nextInt(chars.length())));
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(("salt=" + BBSconstants.SALT_6X + "&t=" + currentTimeMillis + "&r=" + r + "&b=" + body + "&q=").getBytes());
            return currentTimeMillis + "," + r + "," + tools.bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 账号密码登录专用 DS：SALT_PASSPORT + body，POST 无 query 故 q 为空。
     * 明文顺序 salt→t→r→b→q 已用真实抓包反算命中验证，勿调整。
     *
     * @param body 与实际请求体逐字节一致的 JSON
     */
    public String getDS_passport(String body) {
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        StringBuilder r = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 6; i++)
            r.append(chars.charAt(RANDOM.nextInt(chars.length())));
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(("salt=" + BBSconstants.SALT_PASSPORT + "&t=" + currentTimeMillis + "&r=" + r + "&b=" + body + "&q=").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return currentTimeMillis + "," + r + "," + tools.bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 网页 / PC 扫码专用 DS：SALT_4X + body（取码 body 为 "{}"，轮询为 {"ticket":"..."}，
     * 字段顺序也须与实际请求体一致）。
     */
    public String getDS_4x(String body) {
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        StringBuilder r = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 6; i++)
            r.append(chars.charAt(RANDOM.nextInt(chars.length())));
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(("salt=" + BBSconstants.SALT_4X + "&t=" + currentTimeMillis
                    + "&r=" + r + "&b=" + body + "&q=")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return currentTimeMillis + "," + r + "," + tools.bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 无 body 的 DS 通用实现：MD5(saltPart + "&t=" + t + "&r=" + r)。 */
    private static String generateDS(String saltPart) {
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        StringBuilder r = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 6; i++)
            r.append(chars.charAt(RANDOM.nextInt(chars.length())));
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update((saltPart + "&t=" + currentTimeMillis + "&r=" + r).getBytes());
            return currentTimeMillis + "," + r + "," + tools.bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 取并缓存 device_fp：成功入缓存，失败返回空串（不缓存，下次重试）。 */
    private String getFp() {
        String cached = CACHED_FP_BY_SERVER.get(isOversea);
        if (cached != null) return cached;
        long min = 281474976710657L;
        long max = 4503599627370494L;
        long randomLong = min + (long) ((max - min + 1) * RANDOM.nextDouble());
        String appName = isOversea ? "bbs_os" : "bbs_cn";
        String fpUrl = isOversea ? Constants.Urls.OS_FP_URL : Constants.Urls.FP_URL;
        String deviceId = getDeviceId();

        Map<String, Object> body = new HashMap<>();
        body.put("seed_id", Long.toString(randomLong, 16));
        body.put("platform", "2");
        body.put("device_fp", seedFromDeviceId(deviceId));
        body.put("device_id", deviceId);
        body.put("bbs_device_id", deviceId);
        body.put("ext_fields", cachedExtFields != null ? cachedExtFields : "");
        body.put("app_name", appName);
        body.put("seed_time", String.valueOf(System.currentTimeMillis()));
        String response = sendPostRequest(fpUrl, get_fp_headers(), body);
        JsonObject jsonObject = GSON.fromJson(response, JsonObject.class);
        if (jsonObject == null || !jsonObject.has("retcode") || jsonObject.get("retcode").isJsonNull()) return "";
        if (jsonObject.get("retcode").getAsInt() != 0) return "";
        JsonObject data = jsonObject.getAsJsonObject("data");
        if (data == null || !data.has("device_fp") || data.get("device_fp").isJsonNull()) return "";
        String fp = data.get("device_fp").getAsString();
        CACHED_FP_BY_SERVER.put(isOversea, fp);
        return fp;
    }

    /** 设备 ID 去连字符后取 [8,21) 作 seed 前缀；长度不足退化为原串（防 StringIndexOutOfBoundsException）。 */
    private static String seedFromDeviceId(String deviceId) {
        if (deviceId == null) return "";
        String compact = deviceId.replace("-", "");
        if (compact.length() <= 8) return compact;
        return compact.substring(8, Math.min(21, compact.length()));
    }
}
