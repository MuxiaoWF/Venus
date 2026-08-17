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
 * 米游社 API 请求头管理器。
 * 按接口分类生成所需的 Headers（含 DS 签名），自动区分国服/国际服。
 * DS 签名使用 MD5(salt + timestamp + random + ...) 格式，不同接口使用不同 salt（K2/LK2/SALT_6X）。
 */
public class HeaderManager {
    private static final String app_id = "bll8iq97cem8";
    private static final String os_app_id = "6a4js97g007c";
    private static final Random RANDOM = new Random();
    private static final Gson GSON = new Gson();
    /** 每次进程启动生成一次，对应 x-rpc-lifecycle_id */
    private static final String LIFECYCLE_ID = java.util.UUID.randomUUID().toString();

    // 仅缓存 DeviceUtils 产出的字符串结果，避免静态持有 DeviceUtils（其持有 Context）导致内存泄漏
    private static volatile String cachedDeviceId;
    private static volatile String cachedExtFields;

    private final MiHoYoBBSConstants BBSconstants;
    private String device_id;
    private final String user_agent;
    private final boolean isOversea;
    private String cachedFp;

    // Pre-resolved isOversea-dependent values
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
     * 初始化共享设备工具并填充静态缓存。
     * <p>
     * 阻塞风险：{@link DeviceUtils#waitForDeviceId()} 与 {@link DeviceUtils#getExtFields()}
     * 在主线程调用时会同步阻塞最多约 10 秒，极易触发 ANR（表现为「进页即闪退」）。
     * 因此：若当前已是主线程且缓存未热，改在后台线程完成阻塞等待，主线程立即返回；
     * 非主线程（如 Application 预热、后台任务）仍同步阻塞，行为不变。
     */
    private static void initSharedDeviceUtils(Context context) {
        if (cachedDeviceId != null && cachedExtFields != null) return;

        boolean isMainThread = Looper.getMainLooper() == Looper.myLooper();
        if (isMainThread) {
            // 主线程：避免阻塞，交由后台线程填充缓存，立即返回。
            new Thread(() -> fillSharedDeviceUtils(context.getApplicationContext()), "HeaderManager-DeviceInit").start();
            return;
        }

        synchronized (HeaderManager.class) {
            if (cachedDeviceId == null || cachedExtFields == null) {
                fillSharedDeviceUtils(context.getApplicationContext());
            }
        }
    }

    /** 实际填充静态设备缓存（可能阻塞，应在非主线程调用）。 */
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

    /**
     * 每日签到类接口请求头，DS 使用 LK2 salt；
     * 国际服走不同的 Referer/Cookie 分支（Referer 为 act.hoyolab.com，Cookie 留空）。
     */
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

    /**
     * 米游社通用接口（如 getLTokenBySToken、getUserMissionsState）请求头，DS 使用 K2 salt。
     */
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

    /**
     * 用 stoken 换取凭证类接口（如 getTokenByGameToken）请求头，DS 使用 LK2 salt。
     */
    public Map<String, String> get_token_by_stoken_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json; utf-8");
        h.put("x-rpc-channel", Build.MANUFACTURER);
        h.put("Origin", currentWebBaseUrl);
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("User-Agent", user_agent);
        h.put("x-rpc-client_type", "5");
        h.put("Referer", "");
        h.put("Accept-Language", isOversea ? "en-US" : "zh-CN,en-US;q=0.8");
        h.put("X-Requested-With", currentPackageName);
        h.put("Cookie", "");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-app_id", currentAppId);
        h.put("DS", getDS(BBSconstants.LK2));
        return h;
    }

    /**
     * getTokenByGameToken 登录态转换接口请求头（无 DS，依赖 app_id / client_type 等标识）。
     */
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

    /**
     * 极验人机验证相关接口请求头，DS 使用 K2 salt，含 account/sdk 版本号与 device_fp。
     */
    public Map<String, String> get_captcha_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("x-rpc-account_version", "2.20.1");
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-device_name", Build.DEVICE);
        h.put("x-rpc-device_fp", "");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-sdk_version", "2.20.1");
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("Content-Type", "application/json; utf-8");
        h.put("DS", getDS(BBSconstants.K2));
        return h;
    }

    /**
     * 游戏签到/记录类接口（如签到记录查询）请求头，含 device_fp 与 page/tool_version 标识。
     */
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

    /**
     * 小组件（widget）相关接口请求头，DS 使用 K2 salt，csm_source=home。
     */
    public Map<String, String> get_widget_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        h.put("x-rpc-device_name", Build.DEVICE);
        h.put("x-rpc-device_model", Build.MODEL);
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-channel", Build.MANUFACTURER);
        h.put("Referer", currentAppBaseUrl);
        h.put("cookie", "");
        h.put("x-rpc-h256_supported", "1");
        h.put("x-rpc-verify_key", currentAppId);
        h.put("x-rpc-csm_source", "home");
        h.put("User-Agent", user_agent);
        h.put("Connection", "Keep-Alive");
        h.put("DS", getDS(BBSconstants.K2));
        return h;
    }

    /**
     * getUserGameRolesByStoken（用 stoken 获取角色列表）接口请求头，DS 使用 K2 salt。
     */
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
     * loginByPassword（账号密码登录，passport-api）请求头。
     * <p>
     * DS 必须使用「账号 SDK 专用 salt + 带 body 的 DS2 算法」，
     * 用 K2 / LK2 的无 body 版本会直接被判签名错误。
     * 传入的 body 必须与实际发出的请求体逐字节一致。
     *
     * @param body 已序列化的请求体 JSON 字符串
     * @param aigis 极验二次提交时的 x-rpc-aigis 值，首次请求传 null 或空串
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

    /** header 值不能含空格等非法字符，统一替换为 + */
    private static String sanitizeHeaderValue(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._\\-+]", "+");
    }

    /**
     * 抽卡记录 authkey 获取接口请求头，DS 使用 LK2 salt。
     */
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

    /**
     * device_fp 获取接口请求头（无 DS，仅含 UA、版本、Referer/Origin 与语言）。
     */
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

    /**
     * 米游社图片/栏目接口（两个接口 header 完全相同）
     */
    public Map<String, String> get_images_headers() {
        return createImageAndForumHeaders();
    }

    /**
     * 米游社论坛栏目（forum）接口请求头，与图片接口共用同一套 header。
     */
    public Map<String, String> get_forums_id() {
        return createImageAndForumHeaders();
    }

    /**
     * 构造图片/论坛栏目接口共用的请求头，DS 使用 K2 salt。
     */
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
    /**
     * 生成普通 DS 签名：以 "salt=" + salt 作为盐，走无 body 的通用算法（t 秒级时间戳 + 6 位随机 r）。
     */
    private static String getDS(String salt) {
        return generateDS("salt=" + salt);
    }

    /**
     * 签到专用 DS：以 SALT_6X 为 salt、将请求 body 纳入签名，算法为 MD5("salt=&t=&r=&b=body&q=")。
     */
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
     * 账号密码登录（passport-api / ma-cn-passport）专用 DS。
     * <p>
     * 与 K2 / LK2 的无 body 版本、以及签到用的 SALT_6X 版本都不同：
     * 使用账号 SDK 专用 SALT_PASSPORT，且必须带上 body 参与签名，POST 无 query 故 q 为空。
     * 明文顺序为 salt → t → r → b → q，该顺序与 salt 均已用真实抓包 DS 反算命中验证，请勿调整。
     *
     * @param body 与实际发出的请求体逐字节一致的 JSON 字符串
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
     * DS 通用实现：MD5(saltPart + "&t=" + t + "&r=" + r)，t 为秒级时间戳，r 为 6 位随机串。
     */
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

    /**
     * 懒加载并缓存 device_fp：向 fp 接口上报设备信息，成功则缓存结果，失败返回空串。
     */
    private String getFp() {
        if (cachedFp != null) return cachedFp;
        long min = 281474976710657L;
        long max = 4503599627370494L;
        long randomLong = min + (long) ((max - min + 1) * RANDOM.nextDouble());
        String appName = isOversea ? "bbs_os" : "bbs_cn";
        String fpUrl = isOversea ? Constants.Urls.OS_FP_URL : Constants.Urls.FP_URL;

        Map<String, Object> body = new HashMap<>();
        body.put("seed_id", Long.toString(randomLong, 16));
        body.put("platform", "2");
        body.put("device_fp", getDeviceId().replace("-", "").substring(8, 21));
        body.put("device_id", getDeviceId());
        body.put("bbs_device_id", getDeviceId());
        body.put("ext_fields", cachedExtFields != null ? cachedExtFields : "");
        body.put("app_name", appName);
        body.put("seed_time", String.valueOf(System.currentTimeMillis()));
        String response = sendPostRequest(fpUrl, get_fp_headers(), body);
        JsonObject jsonObject = GSON.fromJson(response, JsonObject.class);
        int retCode = jsonObject.get("retcode").getAsInt();
        if (retCode == 0) {
            JsonObject data = jsonObject.getAsJsonObject("data");
            cachedFp = data.get("device_fp").getAsString();
            return cachedFp;
        }
        return "";
    }
}
