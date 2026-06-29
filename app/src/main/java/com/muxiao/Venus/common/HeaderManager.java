package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;
import android.os.Build;

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

    // DeviceUtils 单例，避免重复初始化 OAID
    private static volatile DeviceUtils sharedDeviceUtils;
    private static volatile String cachedDeviceId;

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

    private static void initSharedDeviceUtils(Context context) {
        if (sharedDeviceUtils == null) {
            synchronized (HeaderManager.class) {
                if (sharedDeviceUtils == null) {
                    sharedDeviceUtils = new DeviceUtils(context);
                    cachedDeviceId = sharedDeviceUtils.waitForDeviceId();
                }
            }
        }
    }

    private String getDeviceId() {
        if (device_id != null) return device_id;
        device_id = cachedDeviceId != null ? cachedDeviceId : sharedDeviceUtils.waitForDeviceId();
        return device_id;
    }

    /**
     * 一系列米游社游戏每日签到接口
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
     * getLTokenBySToken\getUserMissionsState等一系列米游社接口
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
     * getTokenByGameToken
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
     * 米游社游戏签到验证码
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
     * getUserGameRolesByStoken
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

    public Map<String, String> get_password_headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", user_agent);
        h.put("x-rpc-account_version", "2.20.1");
        h.put("x-rpc-app_id", currentAppId);
        h.put("x-rpc-device_name", Build.DEVICE);
        h.put("x-rpc-device_fp", getFp());
        h.put("x-rpc-app_version", BBSconstants.bbs_version);
        h.put("x-rpc-client_type", "2");
        h.put("x-rpc-device_id", getDeviceId());
        h.put("x-rpc-sdk_version", "2.20.1");
        h.put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        h.put("x-rpc-game_biz", isOversea ? "bbs_os" : "bbs_cn");
        h.put("DS", getDS(BBSconstants.K2));
        return h;
    }

    /**
     * 抽卡记录authkey
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
     * 设备FP
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
     * 米游社栏目接口
     */
    public Map<String, String> get_forums_id() {
        return createImageAndForumHeaders();
    }

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
    private static String getDS(String salt) {
        return generateDS("salt=" + salt);
    }

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
        body.put("ext_fields", sharedDeviceUtils.getExtFields());
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
