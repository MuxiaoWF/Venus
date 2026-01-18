package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_COOKIE;
import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.annotation.SuppressLint;
import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.DeviceUtils;
import com.muxiao.Venus.common.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SklandDaily {
    private static final String APP_CODE = "4ca99fa6b56cc2ba";
    private static final String URL_GRANT = "https://as.hypergryph.com/user/oauth2/v2/grant";
    private static final String URL_CRED = "https://zonai.skland.com/web/v1/user/auth/generate_cred_by_code";
    private static final String URL_BIND = "https://zonai.skland.com/api/v1/game/player/binding";
    private static final String URL_SIGN = "https://zonai.skland.com/api/v1/game/attendance";
    private static final String SM_PUBKEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCmxMNr7n8ZeT0tE1R9j/mPixoinPkeM+k4VGIn/s0k7N5rJAfnZ0eMER+QhwFvshzo0LNmeUkpR8uIlU/GEVr8mN28sKmwd2gpygqj0ePnBmOW4v0ZVwbSYK+izkhVFk2V/doLoMbWy6b+UnA8mkjvg0iYWRByfRsK2gdl7llqCwIDAQAB";
    private final tools.StatusNotifier notifier;
    private final String token;
    private static String deviceID;

    public SklandDaily(Context context, tools.StatusNotifier notifier) {
        this.token = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).getString(SKLAND_COOKIE, "");
        if (token.isEmpty())
            throw new RuntimeException("森空岛content未设置");
        this.notifier = notifier;
        DeviceUtils deviceUtils = new DeviceUtils(context);
        deviceID = deviceUtils.waitForDeviceId();
    }

    public void run() throws Exception {
        notifier.notifyListeners("开始执行森空岛签到");
        Cred cred = getCredByToken(token);
        notifier.notifyListeners("获取认证信息成功");
        List<Role> roles = getRoles(cred);
        notifier.notifyListeners("获取角色列表成功，共找到 " + roles.size() + " 个角色");
        int currentRoleIndex = 0;
        for (Role r : roles) {
            currentRoleIndex++;
            notifier.notifyListeners("正在为角色 [" + r.name + "] (" + currentRoleIndex + "/" + roles.size() + ") 执行签到...");
            doSign(cred, r);
        }
        notifier.notifyListeners("全部角色签到完成");
    }

    /**
     * 获取特殊的设备ID
     */
    private static Cred getCredByToken(String token) throws Exception {
        // 获取设备ID
        Map<String, String> headers = new HashMap<>() {{
            put("User-Agent", "Skland/1.9.0 (com.hypergryph.skland; build:100001014; Android 35; ) Okhttp/4.11.0");
            put("Accept-Encoding", "gzip");
            put("Connection", "close");
            put("dId", getDid());
        }};
        Map<String, Object> signBase = new HashMap<>() {{
            put("appCode", APP_CODE);
            put("token", token);
            put("type", 0);
        }};
        String grant = sendPostRequest(URL_GRANT, headers, signBase);
        JsonObject g = JsonParser.parseString(grant).getAsJsonObject();
        if (g.get("status").getAsInt() != 0)
            throw new RuntimeException("grant 失败：" + g.get("msg").getAsString());
        String code = g.getAsJsonObject("data").get("code").getAsString();
        Map<String, Object> cred = new HashMap<>() {{
            put("code", code);
            put("kind", 1);
        }};
        String credResp = sendPostRequest(URL_CRED, headers, cred);
        JsonObject d = JsonParser.parseString(credResp).getAsJsonObject().getAsJsonObject("data");
        return new Cred(d.get("cred").getAsString(), d.get("token").getAsString());
    }

    /**
     * 获取绑定的角色列表
     */
    private static List<Role> getRoles(Cred cred) throws Exception {
        Map<String, String> signBase = new LinkedHashMap<>() {{
            put("platform", "");
            put("timestamp", "");
            put("dId", "");
            put("vName", "");
        }};
        Map<String, String> sig = generateSignature(cred.token, "/api/v1/game/player/binding", "", signBase);
        Map<String, String> headers = new LinkedHashMap<>() {{
            put("User-Agent", "'Skland/1.9.0 (com.hypergryph.skland; build:100001014; Android 35; ) Okhttp/4.11.0'");
            put("Accept-Encoding", "gzip");
            put("Connection", "close");
            put("cred", cred.cred);
            put("dId", signBase.get("dId"));
            put("platform", signBase.get("platform"));
            put("timestamp", sig.get("timestamp"));
            put("vName", signBase.get("vName"));
            put("sign", sig.get("sign"));
        }};
        String resp = sendGetRequest(URL_BIND, headers, null);
        JsonObject j = JsonParser.parseString(resp).getAsJsonObject();
        if (j.get("code").getAsInt() != 0)
            throw new RuntimeException(j.get("message").getAsString());
        List<Role> list = new ArrayList<>();
        for (JsonElement e : j.getAsJsonObject("data").getAsJsonArray("list")) {
            JsonObject o = e.getAsJsonObject();
            if (!"arknights".equals(o.get("appCode").getAsString())) continue;
            for (JsonElement b : o.getAsJsonArray("bindingList")) {
                JsonObject r = b.getAsJsonObject();
                list.add(new Role(r.get("uid").getAsString(), r.get("nickName").getAsString()));
            }
        }
        return list;
    }

    /**
     * 执行签到
     */
    private void doSign(Cred cred, Role role) throws Exception {
        Map<String, Object> body = new HashMap<>() {{
            put("gameId", 1);
            put("uid", role.uid);
        }};
        Map<String, String> signBase = new LinkedHashMap<>() {{
            put("platform", "");
            put("timestamp", "");
            put("dId", "");
            put("vName", "");
        }};
        Map<String, String> t = generateSignature(cred.token, "/api/v1/game/attendance", new Gson().toJson(body), signBase);
        Map<String, String> headers = new HashMap<>() {{
            put("cred", cred.cred);
            put("User-Agent", "Skland/1.9.0 (com.hypergryph.skland; build:100001014; Android 35; ) Okhttp/4.11.0");
            put("Accept-Encoding", "gzip");
            put("Connection", "close");
            put("sign", t.get("sign"));
            put("platform", signBase.get("platform"));
            put("timestamp", t.get("timestamp"));
            put("dId", "");
            put("vName", signBase.get("vName"));
        }};
        String resp = sendPostRequest(URL_SIGN, headers, body);
        JsonObject j = JsonParser.parseString(resp).getAsJsonObject();
        if (j.get("code").getAsInt() == 0) {
            JsonArray awards = j.getAsJsonObject("data").getAsJsonArray("awards");
            for (JsonElement award : awards) {
                JsonObject resource = award.getAsJsonObject().getAsJsonObject("resource");
                int count = award.getAsJsonObject().has("count") ? award.getAsJsonObject().get("count").getAsInt() : 1;
                notifier.notifyListeners("[" + role.name + "] 签到成功，获得了" + resource.get("name").getAsString() + "×" + count);
            }
        } else {
            notifier.notifyListeners("[" + role.name + "] 签到失败: " + j.get("message").getAsString());
        }
    }

    /**
     * 构建签名
     */
    private static Map<String, String> generateSignature(String token, String path, String bodyOrQuery, Map<String, String> headerForSign) throws Exception {
        // 生成时间戳
        long timestamp = System.currentTimeMillis() / 1000 - 2;
        String t = String.valueOf(timestamp);
        Map<String, String> headerCopy = new LinkedHashMap<>(headerForSign);
        headerCopy.put("timestamp", t);
        String headerStr = new Gson().toJson(headerCopy);
        // 构建签名字符串：接口地址+body或query+时间戳+请求头的四个重要参数.tojson()
        String signatureBase = path + bodyOrQuery + t + headerStr;
        // 使用HMAC-SHA256加密
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmacBytes = mac.doFinal(signatureBase.getBytes(StandardCharsets.UTF_8));
        String hexS = bytesToHex(hmacBytes);   // 64 字符 ASCII
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] md5Bytes = md5.digest(hexS.getBytes(StandardCharsets.UTF_8));
        return new HashMap<>() {{
            put("sign", bytesToHex(md5Bytes));
            put("timestamp", String.valueOf(timestamp));
        }};
    }

    /**
     * 获取 did
     */
    private static String getDid() throws Exception {
        byte[] uid = deviceID.getBytes();
        String pri = md5(uid).substring(0, 16);
        String ep = rsa(uid);
        long currentTime = System.currentTimeMillis();
        // 创建浏览器环境副本
        Map<String, Object> browser = new HashMap<>() {{
            put("plugins", "MicrosoftEdgePDFPluginPortableDocumentFormatinternal-pdf-viewer1,MicrosoftEdgePDFViewermhjfbmdgcfjbbpaeojofohoefgiehjai1");
            put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0");
            put("canvas", "259ffe69");
            put("timezone", -480);
            put("platform", "Win64");
            put("url", "https://www.skland.com/");
            put("referer", "");
            put("res", "1920_1080_24_1.25");
            put("clientSize", "0_0_1080_1920_1920_1080");
            put("status", "0011");
            put("vpw", deviceID);
            put("svm", currentTime);
            put("trees", deviceID);
            put("pmf", currentTime);
        }};

        // 创建目标数据结构
        Map<String, Object> desTarget = new HashMap<>(browser);
        desTarget.put("protocol", 102);
        desTarget.put("organization", "UWXspnCCJN4sfYlNfqps");
        desTarget.put("appId", "default");
        desTarget.put("os", "web");
        desTarget.put("version", "3.0.0");
        desTarget.put("sdkver", "3.0.0");
        desTarget.put("box", "");  // SMID，第一次为空
        desTarget.put("rtype", "all");
        desTarget.put("smid", smid());
        desTarget.put("subVersion", "1.0.0");
        desTarget.put("time", 0);

        // 计算tn值
        String tnValue = tn(desTarget);
        desTarget.put("tn", md5(tnValue.getBytes()));

        // 应用DES加密
        Map<String, Object> encryptedData = _DES(desTarget);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(b);
        g.write(new Gson().toJson(encryptedData).getBytes(StandardCharsets.UTF_8));
        g.close();
        byte[] gz = android.util.Base64.encode(b.toByteArray(), android.util.Base64.NO_WRAP);
        String aes = aes(gz, pri);

        Map<String, Object> requestBody = new HashMap<>() {{
            put("data", aes);
            put("ep", ep);
            put("compress", 2);
            put("encode", 5);
            put("appId", "default");
            put("organization", "UWXspnCCJN4sfYlNfqps");
            put("os", "web");
        }};
        String resp = sendPostRequest("https://fp-it.portal101.cn/deviceprofile/v4", new HashMap<>(), requestBody);

        return "B" + JsonParser.parseString(resp)
                .getAsJsonObject()
                .getAsJsonObject("detail")
                .get("deviceId").getAsString();
    }

    private static Map<String, Object> _DES(Map<String, Object> obj) throws Exception {
        Map<String, Object> result = new HashMap<>();

        Map<String, DesRule> desRules = getDesRules();

        for (String key : obj.keySet()) {
            if (desRules.containsKey(key)) {
                DesRule rule = desRules.get(key);
                Object res = obj.get(key);

                if (Objects.requireNonNull(rule).isEncrypt == 1) {
                    // 使用TripleDES加密（与Python代码保持一致）
                    byte[] keyBytes = rule.key.getBytes(StandardCharsets.UTF_8);
                    if (keyBytes.length < 8) {
                        // 如果密钥长度不足8字节，填充到8字节
                        byte[] paddedKey = new byte[8];
                        System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
                        for (int i = keyBytes.length; i < 8; i++) {
                            paddedKey[i] = 0; // 使用0填充
                        }
                        keyBytes = paddedKey;
                    } else if (keyBytes.length > 8) {
                        // 如果密钥长度超过8字节，截断到8字节
                        keyBytes = Arrays.copyOf(keyBytes, 8);
                    }

                    SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "DES");
                    @SuppressLint("GetInstance") Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey);

                    String stringValue = String.valueOf(res);
                    // 补足字节
                    byte[] data = stringValue.getBytes(StandardCharsets.UTF_8);
                    int blockSize = 8;
                    int padding = blockSize - (data.length % blockSize);
                    if (padding != blockSize) {
                        byte[] paddedData = new byte[data.length + padding];
                        System.arraycopy(data, 0, paddedData, 0, data.length);
                        data = paddedData;
                    }

                    byte[] encryptedBytes = cipher.doFinal(data);
                    res = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP);
                }

                result.put(rule.obfuscatedName, res);
            } else {
                result.put(key, obj.get(key));
            }
        }

        return result;
    }

    private static String tn(Map<String, Object> m) {
        List<String> k = new ArrayList<>(m.keySet());
        Collections.sort(k);
        StringBuilder sb = new StringBuilder();
        for (String s : k) {
            Object v = m.get(s);
            if (v instanceof Number)
                sb.append(((Number) v).doubleValue() * 10000);
            else sb.append(v);
        }
        return sb.toString();
    }

    private static String smid() throws Exception {
        Calendar cal = Calendar.getInstance();
        String t = String.format(java.util.Locale.getDefault(), "%d%02d%02d%02d%02d%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND));

        String v = t + md5(deviceID.getBytes()) + "00";
        String smskWeb = md5(("smsk_web_" + v).getBytes()).substring(0, 14);
        return v + smskWeb + "0";
    }

    private static String rsa(byte[] d) throws Exception {
        PublicKey k = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(android.util.Base64.decode(SM_PUBKEY, android.util.Base64.NO_WRAP)));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, k);
        return android.util.Base64.encodeToString(c.doFinal(d), android.util.Base64.NO_WRAP);
    }

    private static String aes(byte[] d, String k) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        int len = ((d.length + 16 - 1) / 16) * 16;
        d = Arrays.copyOf(d, len);
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(k.getBytes(), "AES"),
                new IvParameterSpec("0102030405060708".getBytes()));
        return bytesToHex(c.doFinal(d));
    }

    private static String md5(byte[] d) throws Exception {
        return bytesToHex(MessageDigest.getInstance("MD5").digest(d));
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(String.format("%02x", v & 0xff));
        }
        return sb.toString();
    }

    // 获取DES规则
    private static Map<String, DesRule> getDesRules() {
        Map<String, DesRule> rules = new HashMap<>();
        rules.put("appId", new DesRule("DES", 1, "uy7mzc4h", "xx"));
        rules.put("box", new DesRule("", 0, "", "jf"));
        rules.put("canvas", new DesRule("DES", 1, "snrn887t", "yk"));
        rules.put("clientSize", new DesRule("DES", 1, "cpmjjgsu", "zx"));
        rules.put("organization", new DesRule("DES", 1, "78moqjfc", "dp"));
        rules.put("os", new DesRule("DES", 1, "je6vk6t4", "pj"));
        rules.put("platform", new DesRule("DES", 1, "pakxhcd2", "gm"));
        rules.put("plugins", new DesRule("DES", 1, "v51m3pzl", "kq"));
        rules.put("pmf", new DesRule("DES", 1, "2mdeslu3", "vw"));
        rules.put("protocol", new DesRule("", 0, "", "protocol"));
        rules.put("referer", new DesRule("DES", 1, "y7bmrjlc", "ab"));
        rules.put("res", new DesRule("DES", 1, "whxqm2a7", "hf"));
        rules.put("rtype", new DesRule("DES", 1, "x8o2h2bl", "lo"));
        rules.put("sdkver", new DesRule("DES", 1, "9q3dcxp2", "sc"));
        rules.put("status", new DesRule("DES", 1, "2jbrxxw4", "an"));
        rules.put("subVersion", new DesRule("DES", 1, "eo3i2puh", "ns"));
        rules.put("svm", new DesRule("DES", 1, "fzj3kaeh", "qr"));
        rules.put("time", new DesRule("DES", 1, "q2t3odsk", "nb"));
        rules.put("timezone", new DesRule("DES", 1, "1uv05lj5", "as"));
        rules.put("tn", new DesRule("DES", 1, "x9nzj1bp", "py"));
        rules.put("trees", new DesRule("DES", 1, "acfs0xo4", "pi"));
        rules.put("ua", new DesRule("DES", 1, "k92crp1t", "bj"));
        rules.put("url", new DesRule("DES", 1, "y95hjkoo", "cf"));
        rules.put("version", new DesRule("", 0, "", "version"));
        rules.put("vpw", new DesRule("DES", 1, "r9924ab5", "ca"));
        return rules;
    }

    private static class DesRule {
        String cipher;
        int isEncrypt;
        String key;
        String obfuscatedName;

        DesRule(String cipher, int isEncrypt, String key, String obfuscatedName) {
            this.cipher = cipher;
            this.isEncrypt = isEncrypt;
            this.key = key;
            this.obfuscatedName = obfuscatedName;
        }
    }

    private static class Cred {
        public final String cred;
        public final String token;

        public Cred(String cred, String token) {
            this.cred = cred;
            this.token = token;
        }
    }

    private static class Role {
        public final String uid;
        public final String name;

        public Role(String uid, String name) {
            this.uid = uid;
            this.name = name;
        }
    }
}
