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
import android.content.SharedPreferences;

public class fixed {
    private final Context context;
    private final String userId;

    public fixed(Context context, String userId) {
        this.context = context;
        this.userId = userId;
        updateSalt();
        // 初始化headers
        initHeaders();
    }

    public String SALT_6X;
    public String SALT_4X;
    public String LK2;
    public String K2;
    public String bbs_version;

    // 使用静态代码块初始化配置常量
    private void updateSalt() {
        // 默认值
        String defaultSalt6x = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v";
        String defaultSalt4x = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs";
        String defaultLk2 = "IDMtPWQJfBCJSLOFxOlNjiIFVasBLttg";
        String defaultK2 = "aApXDrhCxFhZkKZQVWWyfoAlyHTlJkis";
        String defaultBbsVersion = "2.92.0";
        // 尝试从配置中获取值
        SharedPreferences configPrefs = context.getSharedPreferences("config_prefs", Context.MODE_PRIVATE);
        SALT_6X = configPrefs.getString("SALT_6X", defaultSalt6x);
        SALT_4X = configPrefs.getString("SALT_6X", defaultSalt4x);
        LK2 = configPrefs.getString("SALT_6X", defaultLk2);
        K2 = configPrefs.getString("SALT_6X", defaultK2);
        bbs_version = configPrefs.getString("bbs_version", defaultBbsVersion);
    }

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
     * 需要更新DS参数：password_headers.put("DS", getDS(K2));
     * 需要更新fp参数：getFp();
     */
    public Map<String, String> password_headers;
    /**
     * 需要更新DS参数：authkey_headers.put("DS", getDS(LK2));
     */
    public Map<String, String> authkey_headers;
    public Map<String, String> fp_headers;

    private static final Map<String, String> name_to_game_id = new HashMap<>() {{
        put("崩坏2", "bh2_cn");
        put("崩坏3", "bh3_cn");
        put("未定事件簿", "nxx_cn");
        put("原神", "hk4e_cn");
        put("星铁", "hkrpg_cn");
        put("绝区零", "nap_cn");
    }};
    private final String user_agent = "Mozilla/5.0 (Linux; Android 12; mi-Tech) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/" + bbs_version;

    private static final String app_id = "bll8iq97cem8";
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
        String seedId = Long.toString(randomLong, 16);
        String seedTime = String.valueOf(System.currentTimeMillis());
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
        jsonObject.addProperty("cpuType", android.os.Build.SUPPORTED_ABIS[0]);
        jsonObject.addProperty("romCapacity", String.valueOf(getTotalStorageSpace()));
        jsonObject.addProperty("productName", android.os.Build.PRODUCT);
        jsonObject.addProperty("romRemain", String.valueOf(getAvailableStorageSpace()));
        jsonObject.addProperty("manufacturer", android.os.Build.MANUFACTURER);
        jsonObject.addProperty("appMemory", String.valueOf(getTotalMemory()));
        jsonObject.addProperty("hostname", android.os.Build.HOST);
        jsonObject.addProperty("screenSize", context.getResources().getDisplayMetrics().widthPixels + "x" + context.getResources().getDisplayMetrics().heightPixels);
        jsonObject.addProperty("osVersion", String.valueOf(android.os.Build.VERSION.SDK_INT));
        jsonObject.addProperty("aaid", aaid);
        jsonObject.addProperty("vendor", android.os.Build.BRAND);
        jsonObject.addProperty("accelerometer", getSensorInfo("accelerometer"));
        jsonObject.addProperty("buildTags", android.os.Build.TAGS);
        jsonObject.addProperty("model", android.os.Build.MODEL);
        jsonObject.addProperty("brand", android.os.Build.BRAND);
        jsonObject.addProperty("oaid", generateDeviceId());
        jsonObject.addProperty("hardware", android.os.Build.HARDWARE);
        jsonObject.addProperty("deviceType", android.os.Build.DEVICE);
        jsonObject.addProperty("devId", android.os.Build.VERSION.RELEASE);
        jsonObject.addProperty("serialNumber", android.os.Build.SERIAL);
        jsonObject.addProperty("buildTime", String.valueOf(android.os.Build.TIME));
        jsonObject.addProperty("buildUser", android.os.Build.USER);
        jsonObject.addProperty("ramCapacity", String.valueOf(getTotalRam()));
        jsonObject.addProperty("magnetometer", getSensorInfo("magnetometer"));
        jsonObject.addProperty("display", android.os.Build.DISPLAY);
        jsonObject.addProperty("ramRemain", String.valueOf(getAvailableRam()));
        jsonObject.addProperty("deviceInfo", android.os.Build.MANUFACTURER + "/" + android.os.Build.DEVICE + "/" + android.os.Build.BOARD + ":" + android.os.Build.VERSION.RELEASE + "/" + android.os.Build.ID + "/" + android.os.Build.VERSION.INCREMENTAL + ":" + android.os.Build.TYPE + "/" + android.os.Build.TAGS);
        jsonObject.addProperty("gyroscope", getSensorInfo("gyroscope"));
        jsonObject.addProperty("vaid", "7.9894776E-4x-1.3315796E-4x6.6578976E-4"); // 虚拟广告标识符
        jsonObject.addProperty("buildType", android.os.Build.TYPE);
        jsonObject.addProperty("sdkVersion", android.os.Build.VERSION.SDK_INT);
        jsonObject.addProperty("board", android.os.Build.BOARD);
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

        authkey_headers = new HashMap<>() {{
            put("Cookie", "");
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-client_type", "5");
            put("Content-Type", "application/json; charset=utf-8");
            put("Connection", "Keep-Alive");
            put("User-Agent", user_agent);
        }};

        fp_headers = new HashMap<>() {{
            put("User-Agent", user_agent);
            put("x-rpc-app_version", bbs_version);
            put("x-rpc-client_type", "5");
            put("Referer", "https://webstatic.mihoyo.com/");
            put("Origin", "https://webstatic.mihoyo.com/");
            put("Content-Type", "application/json; utf-8");
            put("Accept-Language", "zh-CN,zh-Hans;q=0.9");
        }};
    }

    // 获取总存储空间的方法
    private long getTotalStorageSpace() {
        android.os.StatFs statFs = new android.os.StatFs(android.os.Environment.getRootDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long totalBlocks = statFs.getBlockCountLong();
        return (totalBlocks * blockSize) / (1024 * 1024); // 返回MB
    }

    // 获取可用存储空间的方法
    private long getAvailableStorageSpace() {
        android.os.StatFs statFs = new android.os.StatFs(android.os.Environment.getRootDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long availableBlocks = statFs.getAvailableBlocksLong();
        return (availableBlocks * blockSize) / (1024 * 1024); // 返回MB
    }

    // 获取总内存的方法
    private long getTotalMemory() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.totalMem / (1024 * 1024); // 返回MB
    }

    // 获取总RAM的方法
    private long getTotalRam() {
        return getTotalMemory();
    }

    // 获取可用RAM的方法
    private long getAvailableRam() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / (1024 * 1024); // 返回MB
    }

    // 获取传感器信息的方法
    private String getSensorInfo(String sensorType) {
        try {
            android.hardware.SensorManager sensorManager = (android.hardware.SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            android.hardware.Sensor sensor = null;
            switch (sensorType) {
                case "accelerometer":
                    sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                    break;
                case "gyroscope":
                    sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
                    break;
                case "magnetometer":
                    sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
                    break;
            }
            if (sensor != null) {
                android.hardware.SensorEventListener sensorEventListener = new android.hardware.SensorEventListener() {
                    @Override
                    public void onSensorChanged(android.hardware.SensorEvent event) {
                        // 我们不需要实际处理事件，只是获取一次数据
                    }

                    @Override
                    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
                        // 不需要处理
                    }
                };
                // 注册传感器监听器
                sensorManager.registerListener(sensorEventListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
                // 等待一小段时间让传感器采集数据
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 注销传感器监听器
                sensorManager.unregisterListener(sensorEventListener);
                // 返回默认值作为示例（实际项目中应该从onSensorChanged获取真实数据）
                switch (sensorType) {
                    case "accelerometer":
                        return "0.061016977x0.8362915x9.826724";
                    case "gyroscope":
                        return "0.0x0.0x0.0";
                    case "magnetometer":
                        return "80.64375x-14.1x77.90625";
                }
            }
        } catch (Exception e) {
            // 如果获取传感器信息失败，返回默认值
            switch (sensorType) {
                case "accelerometer":
                    return "0.061016977x0.8362915x9.826724";
                case "gyroscope":
                    return "0.0x0.0x0.0";
                case "magnetometer":
                    return "80.64375x-14.1x77.90625";
            }
        }
        return "0.0x0.0x0.0";
    }
}
