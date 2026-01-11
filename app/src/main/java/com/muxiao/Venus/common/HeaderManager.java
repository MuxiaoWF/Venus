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

public class HeaderManager {
    private static final String app_id = "bll8iq97cem8";
    private final MiHoYoBBSConstants BBSconstants;
    private final DeviceUtils deviceUtils;
    private final String device_id;
    private final String user_agent;

    public HeaderManager(Context context) {
        this.BBSconstants = new MiHoYoBBSConstants(context);
        this.deviceUtils = new DeviceUtils(context);
        this.device_id = deviceUtils.generateDeviceId();
        this.user_agent = "Mozilla/5.0 (Linux; Android " + Build.VERSION.SDK_INT + "; " + Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/" + BBSconstants.bbs_version;
    }

    /**
     * 一系列米游社游戏每日签到接口
     */
    public Map<String, String> get_game_login_headers() {

        return new HashMap<>() {{
           put("Accept", "application/json; utf-8");
           put("x-rpc-channel", Build.MANUFACTURER);
           put("Origin", Constants.Urls.WEB_BASE_URL);
           put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
           put("x-rpc-app_version", BBSconstants.bbs_version);
           put("User-Agent", user_agent);
           put("x-rpc-client_type", "5");
           put("Accept-Language", "zh-CN,en-US;q=0.8");
           put("X-Requested-With", MiHoYoBBSConstants.PACKAGE_NAME);
           put("Cookie", "");
           put("x-rpc-device_id", device_id);
           put("DS", getDS(BBSconstants.LK2));
       }};
    }

    /**
     * getLTokenBySToken\getUserMissionsState等一系列米游社接口
     */
    public Map<String, String> get_bbs_headers() {
        return new HashMap<>() {{
            put("Accept", "application/json; utf-8");
            put("Origin", Constants.Urls.ORIGIN_REFERER_URL);
            put("x-rpc-client_type", "2");
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
            put("x-rpc-channel", Build.MANUFACTURER);
            put("x-rpc-device_id", device_id);
            put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
            put("User-Agent", user_agent);
            put("x-rpc-verify_key", app_id);
            put("DS", getDS(BBSconstants.K2));
        }};
    }

    public Map<String, String> get_token_by_stoken_headers() {
        return new HashMap<>() {{
            put("Accept", "application/json; utf-8");
            put("x-rpc-channel", Build.MANUFACTURER);
            put("Origin", Constants.Urls.WEB_BASE_URL);
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("User-Agent", user_agent);
            put("x-rpc-client_type", "5");
            put("Referer", "");
            put("Accept-Language", "zh-CN,en-US;q=0.8");
            put("X-Requested-With", MiHoYoBBSConstants.PACKAGE_NAME);
            put("Cookie", "");
            put("x-rpc-device_id", device_id);
            put("x-rpc-app_id", app_id);
            put("DS", getDS(BBSconstants.LK2));
        }};
    }

    /**
     * getTokenByGameToken
     */
    public Map<String, String> get_game_token_headers() {
        return new HashMap<>() {{
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-aigis", "");
            put("Content-Type", "application/json");
            put("Accept", "application/json");
            put("x-rpc-game_biz", "bbs_cn");
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
            put("x-rpc-device_id", device_id);
            put("x-rpc-device_name", Build.DEVICE);
            put("x-rpc-device_model", Build.MODEL);
            put("x-rpc-app_id", app_id);
            put("x-rpc-client_type", "4");
            put("User-Agent", user_agent);
        }};
    }

    public Map<String, String> get_captcha_headers() {
        return new HashMap<>() {{
            put("x-rpc-account_version", "2.20.1");
            put("x-rpc-app_id", app_id);
            put("x-rpc-device_name", Build.DEVICE);
            put("x-rpc-device_fp", "");
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", device_id);
            put("x-rpc-sdk_version", "2.20.1");
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
            put("x-rpc-game_biz", "bbs_cn");
            put("Content-Type", "application/json; utf-8");
            put("DS", getDS(BBSconstants.K2));
        }};
    }

    /**
     * 米游社游戏签到验证码
     */
    public Map<String, String> get_record_headers() {
        return new HashMap<>() {{
            put("x-rpc-client_type", "5");
            put("User-Agent", user_agent);
            put("X-Request-With", MiHoYoBBSConstants.PACKAGE_NAME);
            put("Origin", Constants.Urls.WEB_BASE_URL);
            put("Referer", Constants.Urls.WEB_BASE_URL);
            put("x-rpc-device_fp", getFp());
            put("x-rpc-device_id", device_id);
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-device_name", Build.DEVICE);
            put("x-rpc-page", "v5.3.2-gr-cn_#/ys");
            put("x-rpc-tool_version", "v5.3.2-gr-cn");
            put("sec-fetch-site", "same-site");
            put("sec-fetch-mode", "cors");
            put("sec-fetch-dest", "empty");
            put("accept", "*/*");
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
        }};
    }

    public Map<String, String> get_widget_headers() {
        return new HashMap<>() {{
            put("x-rpc-client_type", "2");
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-device_id", device_id);
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
            put("x-rpc-device_name", Build.DEVICE);
            put("x-rpc-device_model", Build.MODEL);
            put("x-rpc-device_fp", getFp());
            put("x-rpc-channel", Build.MANUFACTURER);
            put("Referer", Constants.Urls.APP_BASE_URL);
            put("cookie", "");
            put("x-rpc-h256_supported", "1");
            put("x-rpc-verify_key", app_id);
            put("x-rpc-csm_source", "home");
            put("User-Agent", user_agent);
            put("Connection", "Keep-Alive");
            put("DS", getDS(BBSconstants.K2));
        }};
    }

    /**
     * getUserGameRolesByStoken
     */
    public Map<String, String> get_user_game_roles_stoken_headers() {
        return new HashMap<>() {{
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", device_id);
            put("x-rpc-device_fp", getFp());
            put("x-rpc-verify_key", app_id);
            put("User-Agent", user_agent);
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("Cookie", "");
            put("DS", getDS(BBSconstants.K2));
        }};
    }

    public Map<String, String> get_password_headers() {
        return new HashMap<>() {{
            put("User-Agent", user_agent);
            put("x-rpc-account_version", "2.20.1");
            put("x-rpc-app_id", app_id);
            put("x-rpc-device_name", Build.DEVICE);
            put("x-rpc-device_fp", getFp());
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", device_id);
            put("x-rpc-sdk_version", "2.20.1");
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
            put("x-rpc-game_biz", "bbs_cn");
            put("DS", getDS(BBSconstants.K2));
        }};
    }

    /**
     * 抽卡记录authkey
     */
    public Map<String, String> get_authkey_headers() {
        return new HashMap<>() {{
            put("Cookie", "");
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-client_type", "5");
            put("Content-Type", "application/json; charset=utf-8");
            put("Connection", "Keep-Alive");
            put("User-Agent", user_agent);
            put("DS", getDS(BBSconstants.LK2));
        }};
    }

    /**
     * 设备FP
     */
    public Map<String, String> get_fp_headers() {
        return new HashMap<>() {{
            put("User-Agent", user_agent);
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-client_type", "5");
            put("Referer", Constants.Urls.WEB_BASE_URL);
            put("Origin", Constants.Urls.WEB_BASE_URL);
            put("Content-Type", "application/json; utf-8");
            put("Accept-Language", "zh-CN,zh-Hans;q=0.9");
        }};
    }

    /**
     * 米游社图片接口
     */
    public Map<String, String> get_images_headers() {
        return new HashMap<>() {{
            put("x-rpc-app_version", BBSconstants.bbs_version);
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", device_id);
            put("x-rpc-sys_version", String.valueOf(Build.VERSION.SDK_INT));
            put("x-rpc-device_name", Build.DEVICE);
            put("x-rpc-device_model", Build.MODEL);
            put("x-rpc-device_fp", getFp());
            put("x-rpc-channel", Build.MANUFACTURER);
            put("x-rpc-h256_supported", "0");
            put("Referer", Constants.Urls.APP_BASE_URL);
            put("x-rpc-verify_key", app_id);
            put("x-rpc-csm_source", "discussion");
            put("DS", getDS(BBSconstants.K2));
        }};
    }

    /**
     * 获取DS
     *
     * @param salt 米游社salt
     **/
    private static String getDS(String salt) {
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        StringBuilder r = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            Random rand = new Random();
            char randomChar = chars.charAt(rand.nextInt(chars.length()));
            r.append(randomChar);
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(("salt=" + salt + "&t=" + currentTimeMillis + "&r=" + r).getBytes());
            byte[] digest = messageDigest.digest();
            StringBuilder d = new StringBuilder();
            for (byte b : digest)
                d.append(String.format("%02x", b));
            return currentTimeMillis + "," + r + "," + d;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 专供米游社api：signIn
     */
    public String getDS_signIn(String body) {
        String params = "";
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        StringBuilder r = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            Random rand = new Random();
            char randomChar = chars.charAt(rand.nextInt(chars.length()));
            r.append(randomChar);
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(("salt=" + BBSconstants.SALT_6X + "&t=" + currentTimeMillis + "&r=" + r + "&b=" + body + "&q=" + params).getBytes());
            byte[] digest = messageDigest.digest();
            StringBuilder d = new StringBuilder();
            for (byte b : digest)
                d.append(String.format("%02x", b));
            return currentTimeMillis + "," + r + "," + d;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取device_fp
     */
    private String getFp() {
        long min = 281474976710657L;
        long max = 4503599627370494L;
        Random random = new Random();
        long randomLong = min + (long) ((max - min + 1) * random.nextDouble());
        Map<String, Object> body = new HashMap<>() {{
            put("seed_id", Long.toString(randomLong, 16));
            put("platform", "2");
            put("device_fp", device_id.replace("-", "").substring(8, 21));
            put("device_id", device_id);
            put("bbs_device_id", device_id);
            put("ext_fields", deviceUtils.getExtFields());
            put("app_name", "bbs_cn");
            put("seed_time", String.valueOf(System.currentTimeMillis()));
        }};
        String response = sendPostRequest(Constants.Urls.FP_URL, get_fp_headers(), body);
        Gson j = new Gson();
        JsonObject jsonObject = j.fromJson(response, JsonObject.class);
        int retCode = jsonObject.get("retcode").getAsInt();
        if (retCode == 0) {
            JsonObject data = jsonObject.getAsJsonObject("data");
            return data.get("device_fp").getAsString();
        }
        return null;
    }
}
