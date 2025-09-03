package com.muxiao.Venus.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;
import static com.muxiao.Venus.common.tools.getDeviceId;

import android.content.Context;

public class fixed {
    private final Context context;
    private final String userId;

    public fixed(Context context, String userId) {
        this.context = context;
        this.userId = userId;
        // 初始化headers
        initHeaders();
    }

    public static final String SALT_6X = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v";
    public static final String SALT_4X = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs";
    public static final String LK2 = "IDMtPWQJfBCJSLOFxOlNjiIFVasBLttg";
    public static final String K2 = "aApXDrhCxFhZkKZQVWWyfoAlyHTlJkis";

    public static final String Honkai2_act_id = "e202203291431091";
    public static final String Honkai3rd_act_id = "e202306201626331";
    public static final String HonkaiStarRail_act_id = "e202304121516551";
    public static final String Genshin_act_id = "e202311201442471";
    public static final String TearsOfThemis_act_id = "e202202251749321";
    public static final String ZZZ_act_id = "e202406242138391";
    public static final Map<String, String> game_id_to_name = new HashMap<>() {{
        put("bh2_cn", "崩坏2");
        put("bh3_cn", "崩坏3");
        put("nxx_cn", "未定事件簿");
        put("hk4e_cn", "原神");
        put("hkrpg_cn", "星铁");
        put("nap_cn", "绝区零");
    }};
    public static final Map<String, String> genshin_TCG_headers = new HashMap<>() {{
        put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
        put("Accept", "application/json, text/plain, */*");
        put("Origin", "https://webstatic.mihoyo.com");
        put("Referer", "https://webstatic.mihoyo.com/");
        put("Accept-Language", "zh-CN,zh;q=0.9");
        put("Cookie", "");
    }};
    public static final List<Map<String, String>> bbs_list = new ArrayList<>(Arrays.asList(
            new HashMap<>() {{
                put("id", "1");
                put("forumId", "1");
                put("name", "崩坏3");
            }},
            new HashMap<>() {{
                put("id", "2");
                put("forumId", "26");
                put("name", "原神");
            }}, new HashMap<>() {{
                put("id", "3");
                put("forumId", "30");
                put("name", "崩坏2");
            }}, new HashMap<>() {{
                put("id", "4");
                put("forumId", "37");
                put("name", "未定事件簿");
            }}, new HashMap<>() {{
                put("id", "5");
                put("forumId", "34");
                put("name", "大别野");
            }}, new HashMap<>() {{
                put("id", "6");
                put("forumId", "52");
                put("name", "星铁");
            }}, new HashMap<>() {{
                put("id", "8");
                put("forumId", "57");
                put("name", "绝区零");
            }}));

    private String deviceId;

    private String generateDeviceId() {
        if (deviceId == null) {
            deviceId = getDeviceId(context);
        }
        return deviceId;
    }

    /**
     * 需要更新DS参数：game_login_headers.put("DS", getDS(LK2));
     */
    public Map<String, String> game_login_headers;
    /**
     * 需要更新DS参数：bbs_headers.put("DS", getDS(K2));
     */
    public Map<String, String> bbs_headers;
    /**
     * 需要更新DS参数：get_token_by_stoken_headers.put("DS", getDS(LK2));
     */
    public Map<String, String> get_token_by_stoken_headers;
    public Map<String, String> gameToken_headers;
    /**
     * 需要更新DS参数：captcha_headers.put("ds", getDS(K2));<br>
     * 需要更新fp:getFp();
     */
    public Map<String, String> captcha_headers;
    /**
     * 需要更新fp参数：getFp();
     */
    public Map<String, String> record_headers;
    /**
     * 需要更新DS参数：widget_headers.put("DS", getDS(K2));
     * 需要更新fp参数：getFp();
     */
    public Map<String, String> widget_headers;
    /**
     * 需要更新DS参数：user_game_roles_stoken_headers.put("DS", getDS(K2));
     * 需要更新fp参数：getFp();
     */
    public Map<String, String> user_game_roles_stoken_headers;
    /**
     * 需要更新DS参数：password_headers.put("ds", getDS(K2));
     * 需要更新fp参数：getFp();
     */
    public Map<String, String> password_headers;

    private static final Map<String, String> name_to_game_id = new HashMap<>() {{
        put("崩坏2", "bh2_cn");
        put("崩坏3", "bh3_cn");
        put("未定事件簿", "nxx_cn");
        put("原神", "hk4e_cn");
        put("星铁", "hkrpg_cn");
        put("绝区零", "nap_cn");
    }};
    private static final String bbs_version = "2.92.0";
    private static final String user_agent = "Mozilla/5.0 (Linux; Android 12; mi-Tech) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/" + bbs_version;
    public static final Map<String, String> fp_headers = new HashMap<>() {{
        put("User-Agent", user_agent);
        put("x-rpc-app_version", bbs_version);
        put("x-rpc-client_type", "5");
        put("Referer", "https://webstatic.mihoyo.com/");
        put("Origin", "https://webstatic.mihoyo.com/");
        put("Content-Type", "application/json; utf-8");
        put("Accept-Language", "zh-CN,zh-Hans;q=0.9");
    }};
    public static final Map<String, String> authkey_headers = new HashMap<>() {{
        put("Cookie", "");
        put("DS", getDS(LK2));
        put("x-rpc-app_version", bbs_version);
        put("x-rpc-client_type", "5");
        put("Content-Type", "application/json; charset=utf-8");
        put("Connection", "Keep-Alive");
        put("User-Agent", user_agent);
    }};

    private static final String app_id = "bll8iq97cem8";
    public static String publicKeyString = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDDvekdPMHN3AYhm/vktJT+YJr7cI5DcsNKqdsx5DZX0gDuWFuIjzdwButrIYPNmRJ1G8ybDIF7oDW2eEpm5sMbL9zs9ExXCdvqrn51qELbqj0XxtMTIpaCHFSI50PfPpTFV9Xt/hmyVwokoOXFlAEgCn+QCgGs52bFoYMtyi+xEQIDAQAB";
    private String seedId;
    private String seedTime;

    public static String name_to_game_num_id(String game_name) {
        for (Map<String, String> map : bbs_list) {
            if (Objects.equals(map.get("name"), game_name)) {
                return map.get("id");
            }
        }
        return null;
    }

    /**
     * 获取游戏id（game_biz），可输入崩坏2、原神、崩坏3、绝区零、星铁、绝区零
     */
    public static String name_to_game_id(String game_name) {
        return name_to_game_id.get(game_name);
    }

    /**
     * 通过stoken获取cookie_token
     */
    public String getCookieTokenByStoken() {
        String stoken = tools.read(context, userId, "stoken");
        String stuid = tools.read(context, userId, "stuid");
        if (stoken == null || stoken.isEmpty() && stuid == null || stuid.isEmpty()) {
            throw new RuntimeException("Stoken和Suid为空，无法自动更新CookieToken");
        }
        String cookie = "stuid=" + stuid + ";stoken=" + stoken;
        if (stoken.startsWith("v2_")) {
            if (tools.read(context, userId, "mid") == null)
                throw new RuntimeException("v2的stoken获取cookie_token时需要mid");
            cookie = cookie + ";mid=" + tools.read(context, userId, "mid");
        }
        game_login_headers.put("DS", getDS(LK2));
        Map<String, String> header = game_login_headers;
        header.put("cookie", cookie);
        String response = sendGetRequest("https://api-takumi.mihoyo.com/auth/api/getCookieAccountInfoBySToken", header, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        if (res.get("retcode").getAsInt() != 0) {
            throw new RuntimeException("获取CookieToken失败,stoken已失效请重新抓取");
        }
        tools.write(context, userId, "cookie_token", res.get("data").getAsJsonObject().get("cookie_token").getAsString());
        return res.get("data").getAsJsonObject().get("cookie_token").getAsString();
    }

    /**
     * 获取device_fp
     */
    public void getFp() {
        long min = 281474976710657L;
        long max = 4503599627370494L;
        Random random = new Random();
        long randomLong = min + (long) ((max - min + 1) * random.nextDouble());
        seedId = Long.toString(randomLong, 16);
        seedTime = String.valueOf(System.currentTimeMillis());
        Map<String, Object> body = new HashMap<>() {{
            put("seed_id", seedId);
            put("platform", "2");
            put("device_fp", generateRandomFp());
            put("device_id", generateDeviceId());
            put("bbs_device_id", generateDeviceId());
            put("ext_fields", getExtFields());
            put("app_name", "bbs_cn");
            put("seed_time", seedTime);
        }};
        String response = sendPostRequest("https://public-data-api.mihoyo.com/device-fp/api/getFp", fp_headers, body);
        Gson j = new Gson();
        JsonObject jsonObject = j.fromJson(response, JsonObject.class);
        int retCode = jsonObject.get("retcode").getAsInt();
        if (retCode == 0) {
            JsonObject data = jsonObject.getAsJsonObject("data");
            // 更新所有需要device_fp的header
            String deviceFp = data.get("device_fp").getAsString();
            captcha_headers.put("x-rpc-device_fp", deviceFp);
            record_headers.put("x-rpc-device_fp", deviceFp);
            widget_headers.put("x-rpc-device_fp", deviceFp);
            user_game_roles_stoken_headers.put("x-rpc-device_fp", deviceFp);
            password_headers.put("x-rpc-device_fp", deviceFp);
        }
    }

    /**
     * 获取device_fp中的原始随机device_fp参数
     *
     * @return device_fp -String
     */
    private String generateRandomFp() {
        String temp = generateDeviceId().replace("-", "");
        return temp.substring(8, 21);
    }

    /**
     * 获取device_fp中的ext_fields参数
     *
     * @return ext_fields -String
     */
    private String getExtFields() {
        String[] temp2 = generateDeviceId().split("-");
        String aaid = temp2[0] + temp2[4].substring(0, 3) + "-" + temp2[4].substring(3, 6) + "-" + temp2[4].substring(6, 9) + temp2[1] + temp2[2] + temp2[3];
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("cpuType", "arm64-v8a");
        jsonObject.addProperty("romCapacity", "512");
        jsonObject.addProperty("productName", "ishtar");
        jsonObject.addProperty("romRemain", "459");
        jsonObject.addProperty("manufacturer", "Xiaomi");
        jsonObject.addProperty("appMemory", "512");
        jsonObject.addProperty("hostname", "xiaomi.eu");
        jsonObject.addProperty("screenSize", "1440x3022");
        jsonObject.addProperty("osVersion", "13");
        jsonObject.addProperty("aaid", aaid);
        jsonObject.addProperty("vendor", "中国电信");
        jsonObject.addProperty("accelerometer", "0.061016977x0.8362915x9.826724");
        jsonObject.addProperty("buildTags", "release-keys");
        jsonObject.addProperty("model", "2304FPN6DC");
        jsonObject.addProperty("brand", "Xiaomi");
        jsonObject.addProperty("oaid", generateDeviceId());
        jsonObject.addProperty("hardware", "qcom");
        jsonObject.addProperty("deviceType", "ishtar");
        jsonObject.addProperty("devId", "REL");
        jsonObject.addProperty("serialNumber", "unknown");
        jsonObject.addProperty("buildTime", String.valueOf(System.currentTimeMillis()));
        jsonObject.addProperty("buildUser", "builder");
        jsonObject.addProperty("ramCapacity", "229481");
        jsonObject.addProperty("magnetometer", "80.64375x-14.1x77.90625");
        jsonObject.addProperty("display", "TKQ1.221114.001 release-keys");
        jsonObject.addProperty("ramRemain", "110308");
        jsonObject.addProperty("deviceInfo", "Xiaomi/ishtar/ishtar:13/TKQ1.221114.001/V14.0.17.0.TMACNXM:user/release-keys");
        jsonObject.addProperty("gyroscope", "0.0x0.0x0.0");
        jsonObject.addProperty("vaid", "7.9894776E-4x-1.3315796E-4x6.6578976E-4");
        jsonObject.addProperty("buildType", "user");
        jsonObject.addProperty("sdkVersion", 33);
        jsonObject.addProperty("board", "kalama");
        return gson.toJson(jsonObject);
    }

    /**
     * 获取DS
     *
     * @param salt 米游社salt
     **/
    public static String getDS(String salt) {
        byte[] digest;
        long currentTimeMillis = System.currentTimeMillis() / 1000;
        ArrayList<Character> arrayList = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            Random rand = new Random();
            char randomChar = chars.charAt(rand.nextInt(chars.length()));
            arrayList.add(randomChar);
        }
        StringBuilder r = new StringBuilder();
        for (Character c : arrayList) {
            r.append(c);
        }
        String random = r.toString();
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] bytes = ("salt=" + salt + "&t=" + currentTimeMillis + "&r=" + random).getBytes();
        messageDigest.update(bytes);
        digest = messageDigest.digest();
        StringBuilder d = new StringBuilder();
        for (byte b : digest) {
            d.append(String.format("%02x", b));
        }
        return currentTimeMillis + "," + random + "," + d;
    }

    private void initHeaders() {
        game_login_headers = new HashMap<>() {{
            put("Accept", "application/json; utf-8");
            put("x-rpc-channel", "miyousheluodi");
            put("Origin", "https://webstatic.mihoyo.com");
            put("x-rpc-app_version", bbs_version);
            put("User-Agent", user_agent);
            put("x-rpc-client_type", "5");
            put("Referer", "");
            put("Accept-Language", "zh-CN,en-US;q=0.8");
            put("X-Requested-With", "com.mihoyo.hyperion");
            put("Cookie", "");
            put("x-rpc-device_id", generateDeviceId());
        }};

        bbs_headers = new HashMap<>() {{
            put("Accept", "application/json; utf-8");
            put("Origin", "https://act.mihoyo.com/");
            put("x-rpc-client_type", "2");
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-sys_version", "14");
            put("x-rpc-channel", "miyousheluodi");
            put("x-rpc-device_id", generateDeviceId());
            put("Referer", "https://act.mihoyo.com/");
            put("User-Agent", user_agent);
            put("x-rpc-verify_key", app_id);
        }};

        get_token_by_stoken_headers = new HashMap<>() {{
            put("Accept", "application/json; utf-8");
            put("x-rpc-channel", "miyousheluodi");
            put("Origin", "https://webstatic.mihoyo.com");
            put("x-rpc-app_version", bbs_version);
            put("User-Agent", user_agent);
            put("x-rpc-client_type", "5");
            put("Referer", "");
            put("Accept-Language", "zh-CN,en-US;q=0.8");
            put("X-Requested-With", "com.mihoyo.hyperion");
            put("Cookie", "");
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-app_id", app_id);
        }};

        gameToken_headers = new HashMap<>() {{
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-aigis", "");
            put("Content-Type", "application/json");
            put("Accept", "application/json");
            put("x-rpc-game_biz", "bbs_cn");
            put("x-rpc-sys_version", "14");
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-device_name", "mi-Tech-Device");
            put("x-rpc-device_model", "mi-Tech");
            put("x-rpc-app_id", app_id);
            put("x-rpc-client_type", "4");
            put("User-Agent", user_agent);
        }};

        captcha_headers = new HashMap<>() {{
            put("x-rpc-account_version", "2.20.1");
            put("x-rpc-app_id", app_id);
            put("x-rpc-device_name", "mi-Tech-Device");
            put("x-rpc-device_fp", "");
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-sdk_version", "2.20.1");
            put("x-rpc-sys_version", "14");
            put("x-rpc-game_biz", "bbs_cn");
            put("Content-Type", "application/json; utf-8");
        }};

        record_headers = new HashMap<>() {{
            put("x-rpc-client_type", "5");
            put("User-Agent", user_agent);
            put("X-Request-With", "com.mihoyo.hyperion");
            put("Origin", "https://webstatic.mihoyo.com");
            put("Referer", "https://webstatic.mihoyo.com/");
            put("x-rpc-device_fp", "");
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-device_name", "mi-Tech-Device");
            put("x-rpc-page", "v5.3.2-gr-cn_#/ys");
            put("x-rpc-tool_version", "v5.3.2-gr-cn");
            put("sec-fetch-site", "same-site");
            put("sec-fetch-mode", "cors");
            put("sec-fetch-dest", "empty");
            put("accept", "*/*");
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
            put("x-rpc-sys_version", "14");
        }};

        widget_headers = new HashMap<>() {{
            put("x-rpc-client_type", "2");
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-sys_version", "14");
            put("x-rpc-device_name", "mi-Tech-Device");
            put("x-rpc-device_model", "mi-Tech");
            put("x-rpc-device_fp", "");
            put("x-rpc-channel", "miyousheluodi");
            put("Referer", "https://app.mihoyo.com");
            put("cookie", "");
            put("x-rpc-h256_supported", "1");
            put("x-rpc-verify_key", app_id);
            put("x-rpc-csm_source", "home");
            put("User-Agent", user_agent);
            put("Connection", "Keep-Alive");
        }};

        user_game_roles_stoken_headers = new HashMap<>() {{
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-device_fp", "");
            put("x-rpc-verify_key", app_id);
            put("User-Agent", user_agent);
            put("x-rpc-app_version", bbs_version);
            put("Cookie", "");
        }};

        password_headers = new HashMap<>() {{
            put("User-Agent", user_agent);
            put("x-rpc-account_version", "2.20.1");
            put("x-rpc-app_id", app_id);
            put("x-rpc-device_name", "mi-Tech-Device");
            put("x-rpc-device_fp", "");
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-client_type", "2");
            put("x-rpc-device_id", generateDeviceId());
            put("x-rpc-sdk_version", "2.20.1");
            put("x-rpc-sys_version", "14");
            put("x-rpc-game_biz", "bbs_cn");
        }};
    }
}
