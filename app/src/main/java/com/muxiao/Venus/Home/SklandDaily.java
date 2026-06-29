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
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.DeviceUtils;
import com.muxiao.Venus.common.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Iterator;
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

/**
 * 森空岛签到（明日方舟/终末地）。
 * 流程：grant获取code → 生成cred → 获取绑定角色 → 遍历签到。
 * 使用 HMAC-SHA256 + MD5 签名，设备指纹通过 DES/AES/RSA 加密生成。
 */
public class SklandDaily {
    public static final int GAME_ARKNIGHTS = 1;
    public static final int GAME_ENDFIELD = 3;
    // 森空岛应用标识
    private static final String APP_CODE = "4ca99fa6b56cc2ba";
    private static final String SKLAND_USER_AGENT = "Skland/1.9.0 (com.hypergryph.skland; build:100001014; Android 35; ) Okhttp/4.11.0";
    private static final String URL_GRANT = "https://as.hypergryph.com/user/oauth2/v2/grant";
    private static final String URL_CRED = "https://zonai.skland.com/web/v1/user/auth/generate_cred_by_code";
    private static final String URL_BIND = "https://zonai.skland.com/api/v1/game/player/binding";
    private static final String URL_SIGN_ARKNIGHTS = "https://zonai.skland.com/api/v1/game/attendance";
    private static final String URL_SIGN_ENDFIELD = "https://zonai.skland.com/web/v1/game/endfield/attendance";
    // 设备指纹加密用的 RSA 公钥
    private static final String SM_PUBKEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCmxMNr7n8ZeT0tE1R9j/mPixoinPkeM+k4VGIn/s0k7N5rJAfnZ0eMER+QhwFvshzo0LNmeUkpR8uIlU/GEVr8mN28sKmwd2gpygqj0ePnBmOW4v0ZVwbSYK+izkhVFk2V/doLoMbWy6b+UnA8mkjvg0iYWRByfRsK2gdl7llqCwIDAQAB";
    private final tools.StatusNotifier notifier;
    private final Context context;
    private final String token;
    private final int gameId;
    private final String deviceID;

    public SklandDaily(Context context, tools.StatusNotifier notifier, int gameId) {
        this.context = context;
        this.token = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).getString(SKLAND_COOKIE, "");
        if (token.isEmpty())
            throw new RuntimeException(context.getString(R.string.skland_content_not_set));
        this.notifier = notifier;
        this.gameId = gameId;
        DeviceUtils deviceUtils = new DeviceUtils(context);
        deviceID = deviceUtils.waitForDeviceId();
        if (deviceID == null || deviceID.isEmpty()) {
            throw new RuntimeException("获取设备ID失败(deviceID is null)，请检查权限或重启应用");
        }
    }

    public void run() throws Exception {
        notifier.notifyListeners(context.getString(R.string.skland_start));
        Cred cred = getCredByToken(token);
        notifier.notifyListeners(context.getString(R.string.skland_cred_success));
        List<Role> roles = getRoles(cred);
        android.util.Log.d("SklandDaily", "all roles: " + roles.size() + ", target gameId=" + gameId);
        for (Role r : roles) android.util.Log.d("SklandDaily", "  role: " + r.name + " gameId=" + r.gameId + " uid=" + r.uid + " serverId=" + r.serverId);
        // 按 gameId 过滤角色
        Iterator<Role> it = roles.iterator();
        while (it.hasNext()) {
            if (it.next().gameId != gameId) it.remove();
        }
        if (roles.isEmpty()) {
            notifier.notifyListeners(context.getString(R.string.skland_no_roles));
            return;
        }
        notifier.notifyListeners(context.getString(R.string.skland_roles_found, roles.size()));
        int currentRoleIndex = 0;
        for (Role r : roles) {
            currentRoleIndex++;
            notifier.notifyListeners(context.getString(R.string.skland_signing_role, r.name, currentRoleIndex, roles.size()));
            doSign(cred, r);
        }
        notifier.notifyListeners(context.getString(R.string.skland_all_done));
    }

    /**
     * 获取特殊的设备ID
     */
    private Cred getCredByToken(String token) throws Exception {
        // 获取设备ID
        Map<String, String> headers = Map.of(
                "User-Agent", SKLAND_USER_AGENT,
                "Accept-Encoding", "gzip",
                "Connection", "close",
                "dId", getDid()
        );
        Map<String, Object> signBase = Map.of(
                "appCode", APP_CODE,
                "token", token,
                "type", 0
        );
        String grant = sendPostRequest(URL_GRANT, headers, signBase);
        JsonObject g = JsonParser.parseString(grant).getAsJsonObject();
        if (g.get("status").getAsInt() != 0)
            throw new RuntimeException(context.getString(R.string.skland_grant_failed, g.get("msg").getAsString()));
        String code = g.getAsJsonObject("data").get("code").getAsString();
        Map<String, Object> cred = Map.of("code", code, "kind", 1);
        String credResp = sendPostRequest(URL_CRED, headers, cred);
        JsonObject d = JsonParser.parseString(credResp).getAsJsonObject().getAsJsonObject("data");
        return new Cred(d.get("cred").getAsString(), d.get("token").getAsString());
    }

    /**
     * 获取绑定的角色列表
     */
    private static List<Role> getRoles(Cred cred) throws Exception {
        Map<String, String> signBase = new LinkedHashMap<>();
        signBase.put("platform", "");
        signBase.put("timestamp", "");
        signBase.put("dId", "");
        signBase.put("vName", "");
        Map<String, String> sig = generateSignature(cred.token, "/api/v1/game/player/binding", "", signBase);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", SKLAND_USER_AGENT);
        headers.put("Accept-Encoding", "gzip");
        headers.put("Connection", "close");
        headers.put("cred", cred.cred);
        headers.put("dId", signBase.get("dId"));
        headers.put("platform", signBase.get("platform"));
        headers.put("timestamp", sig.get("timestamp"));
        headers.put("vName", signBase.get("vName"));
        headers.put("sign", sig.get("sign"));
        String resp = sendGetRequest(URL_BIND, headers, null);
        android.util.Log.d("SklandDaily", "binding response: " + resp);
        JsonObject j = JsonParser.parseString(resp).getAsJsonObject();
        if (j.get("code").getAsInt() != 0)
            throw new RuntimeException(j.get("message").getAsString());
        List<Role> list = new ArrayList<>();
        JsonArray bindList = j.getAsJsonObject("data").getAsJsonArray("list");
        if (bindList == null) return list;
        for (JsonElement e : bindList) {
            JsonObject o = e.getAsJsonObject();
            String appCode = o.has("appCode") && !o.get("appCode").isJsonNull() ? o.get("appCode").getAsString() : "";
            JsonArray bindingArr = o.getAsJsonArray("bindingList");
            if (bindingArr == null) continue;
            for (JsonElement b : bindingArr) {
                JsonObject r = b.getAsJsonObject();
                if ("arknights".equals(appCode)) {
                    if (r.has("uid") && r.has("nickName") && r.has("gameId"))
                        list.add(new Role(r.get("uid").getAsString(), r.get("nickName").getAsString(), r.get("gameId").getAsInt()));
                } else if (r.has("roles") && !r.get("roles").isJsonNull()) {
                    int gid = r.has("gameId") ? r.get("gameId").getAsInt() : 0;
                    for (JsonElement r1 : r.getAsJsonArray("roles")) {
                        JsonObject r2o = r1.getAsJsonObject();
                        if (r2o.has("roleId") && r2o.has("nickname"))
                            list.add(new Role(r2o.get("roleId").getAsString(), r2o.get("nickname").getAsString(), gid, r2o.has("serverId") ? r2o.get("serverId").getAsString() : ""));
                    }
                }
            }
        }
        return list;
    }

    private String getTaskName() {
        return gameId == GAME_ARKNIGHTS
                ? context.getString(R.string.task_name_skland_arknights)
                : context.getString(R.string.task_name_skland_endfield);
    }

    /**
     * 执行签到
     */
    private void doSign(Cred cred, Role role) throws Exception {
        Map<String, String> signBase = new LinkedHashMap<>();
        signBase.put("platform", "");
        signBase.put("timestamp", "");
        signBase.put("dId", "");
        signBase.put("vName", "");
        String taskName = getTaskName();
        try {
            if (role.gameId == GAME_ENDFIELD) {
                Map<String, String> t = generateSignature(cred.token, "/web/v1/game/endfield/attendance", "{}", signBase);
                Map<String, String> headers = createSignHeaders(cred, signBase, t);
                headers.put("Content-Type", "application/json");
                headers.put("sk-game-role", "3_" + role.uid + "_" + role.serverId);
                headers.put("referer", "https://game.skland.com/");
                headers.put("origin", "https://game.skland.com/");
                String resp = sendPostRequest(URL_SIGN_ENDFIELD, headers, new HashMap<>());
                android.util.Log.d("SklandDaily", "endfield sign response: " + resp);
                JsonObject j = JsonParser.parseString(resp).getAsJsonObject();
                if (j.get("code").getAsInt() == 0) {
                    JsonObject data = j.getAsJsonObject("data");
                    JsonArray awardids = data.getAsJsonArray("awardIds");
                    JsonObject resMap = data.getAsJsonObject("resourceInfoMap");
                    if (awardids != null && resMap != null) {
                        for (JsonElement awardid : awardids) {
                            String id = awardid.getAsJsonObject().get("id").getAsString();
                            JsonObject r1 = resMap.getAsJsonObject(id);
                            if (r1 != null)
                                notifier.notifyListeners(taskName + " [" + role.name + "] " + context.getString(R.string.skland_sign_success, r1.get("name").getAsString() + "×" + r1.get("count").getAsInt()));
                        }
                    }
                } else {
                    notifier.notifyListeners(taskName + " [" + role.name + "] " + context.getString(R.string.skland_sign_failed, j.get("message").getAsString() + " (retcode=" + j.get("code").getAsInt() + ")"));
                }
            } else {
                Map<String, Object> body = new HashMap<>();
                body.put("gameId", role.gameId);
                body.put("uid", role.uid);
                Map<String, String> t = generateSignature(cred.token, "/api/v1/game/attendance", new Gson().toJson(body), signBase);
                Map<String, String> headers = createSignHeaders(cred, signBase, t);
                String resp = sendPostRequest(URL_SIGN_ARKNIGHTS, headers, body);
                android.util.Log.d("SklandDaily", "arknights sign response: " + resp);
                JsonObject j = JsonParser.parseString(resp).getAsJsonObject();
                if (j.get("code").getAsInt() == 0) {
                    JsonObject data = j.getAsJsonObject("data");
                    JsonArray awards = data != null ? data.getAsJsonArray("awards") : null;
                    if (awards != null) {
                        for (JsonElement award : awards) {
                            JsonObject resource = award.getAsJsonObject().getAsJsonObject("resource");
                            int count = award.getAsJsonObject().has("count") ? award.getAsJsonObject().get("count").getAsInt() : 1;
                            if (resource != null)
                                notifier.notifyListeners(taskName + " [" + role.name + "] " + context.getString(R.string.skland_sign_success, resource.get("name").getAsString() + "×" + count));
                        }
                    }
                } else {
                    notifier.notifyListeners(taskName + " [" + role.name + "] " + context.getString(R.string.skland_sign_failed, j.get("message").getAsString() + " (retcode=" + j.get("code").getAsInt() + ")"));
                }
            }
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("403")) {
                tools.writeLog(context, taskName + " [" + role.name + "] " + context.getString(R.string.skland_already_signed));
            } else {
                throw e;
            }
        }
    }

    private Map<String, String> createSignHeaders(Cred cred, Map<String, String> signBase, Map<String, String> sig) {
        Map<String, String> headers = new HashMap<>();
        headers.put("cred", cred.cred);
        headers.put("User-Agent", SKLAND_USER_AGENT);
        headers.put("Accept-Encoding", "gzip");
        headers.put("Connection", "close");
        headers.put("sign", sig.get("sign"));
        headers.put("platform", signBase.get("platform"));
        headers.put("timestamp", sig.get("timestamp"));
        headers.put("dId", signBase.get("dId"));
        headers.put("vName", signBase.get("vName"));
        return headers;
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
        String hexS = tools.bytesToHex(hmacBytes);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] md5Bytes = md5.digest(hexS.getBytes(StandardCharsets.UTF_8));
        Map<String, String> result = new HashMap<>();
        result.put("sign", tools.bytesToHex(md5Bytes));
        result.put("timestamp", String.valueOf(timestamp));
        return result;
    }

    /**
     * 获取 did
     */
    private String getDid() throws Exception {
        byte[] uid = deviceID.getBytes();
        String pri = md5(uid).substring(0, 16);
        String ep = rsa(uid);
        long currentTime = System.currentTimeMillis();
        // 创建浏览器环境副本
        Map<String, Object> browser = new HashMap<>();
        browser.put("plugins", "MicrosoftEdgePDFPluginPortableDocumentFormatinternal-pdf-viewer1,MicrosoftEdgePDFViewermhjfbmdgcfjbbpaeojofohoefgiehjai1");
        browser.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0");
        browser.put("canvas", "259ffe69");
        browser.put("timezone", -480);
        browser.put("platform", "Win64");
        browser.put("url", "https://www.skland.com/");
        browser.put("referer", "");
        browser.put("res", "1920_1080_24_1.25");
        browser.put("clientSize", "0_0_1080_1920_1920_1080");
        browser.put("status", "0011");
        browser.put("vpw", deviceID);
        browser.put("svm", currentTime);
        browser.put("trees", deviceID);
        browser.put("pmf", currentTime);

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

        Map<String, Object> encryptedData = _DES(desTarget);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(b)) {
            g.write(new Gson().toJson(encryptedData).getBytes(StandardCharsets.UTF_8));
        }
        byte[] gz = android.util.Base64.encode(b.toByteArray(), android.util.Base64.NO_WRAP);
        String aes = aes(gz, pri);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("data", aes);
        requestBody.put("ep", ep);
        requestBody.put("compress", 2);
        requestBody.put("encode", 5);
        requestBody.put("appId", "default");
        requestBody.put("organization", "UWXspnCCJN4sfYlNfqps");
        requestBody.put("os", "web");
        String resp = sendPostRequest("https://fp-it.portal101.cn/deviceprofile/v4", new HashMap<>(), requestBody);

        return "B" + JsonParser.parseString(resp)
                .getAsJsonObject()
                .getAsJsonObject("detail")
                .get("deviceId").getAsString();
    }

    private static Map<String, Object> _DES(Map<String, Object> obj) throws Exception {
        Map<String, Object> result = new HashMap<>();

        Map<String, DesRule> desRules = DES_RULES;

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

    private String smid() throws Exception {
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
        return tools.bytesToHex(c.doFinal(d));
    }

    private static String md5(byte[] d) throws Exception {
        return tools.bytesToHex(MessageDigest.getInstance("MD5").digest(d));
    }

    private static final Map<String, DesRule> DES_RULES = new HashMap<>();
    static {
        DES_RULES.put("appId", new DesRule(1, "uy7mzc4h", "xx"));
        DES_RULES.put("box", new DesRule(0, "", "jf"));
        DES_RULES.put("canvas", new DesRule(1, "snrn887t", "yk"));
        DES_RULES.put("clientSize", new DesRule(1, "cpmjjgsu", "zx"));
        DES_RULES.put("organization", new DesRule(1, "78moqjfc", "dp"));
        DES_RULES.put("os", new DesRule(1, "je6vk6t4", "pj"));
        DES_RULES.put("platform", new DesRule(1, "pakxhcd2", "gm"));
        DES_RULES.put("plugins", new DesRule(1, "v51m3pzl", "kq"));
        DES_RULES.put("pmf", new DesRule(1, "2mdeslu3", "vw"));
        DES_RULES.put("protocol", new DesRule(0, "", "protocol"));
        DES_RULES.put("referer", new DesRule(1, "y7bmrjlc", "ab"));
        DES_RULES.put("res", new DesRule(1, "whxqm2a7", "hf"));
        DES_RULES.put("rtype", new DesRule(1, "x8o2h2bl", "lo"));
        DES_RULES.put("sdkver", new DesRule(1, "9q3dcxp2", "sc"));
        DES_RULES.put("status", new DesRule(1, "2jbrxxw4", "an"));
        DES_RULES.put("subVersion", new DesRule(1, "eo3i2puh", "ns"));
        DES_RULES.put("svm", new DesRule(1, "fzj3kaeh", "qr"));
        DES_RULES.put("time", new DesRule(1, "q2t3odsk", "nb"));
        DES_RULES.put("timezone", new DesRule(1, "1uv05lj5", "as"));
        DES_RULES.put("tn", new DesRule(1, "x9nzj1bp", "py"));
        DES_RULES.put("trees", new DesRule(1, "acfs0xo4", "pi"));
        DES_RULES.put("ua", new DesRule(1, "k92crp1t", "bj"));
        DES_RULES.put("url", new DesRule(1, "y95hjkoo", "cf"));
        DES_RULES.put("version", new DesRule(0, "", "version"));
        DES_RULES.put("vpw", new DesRule(1, "r9924ab5", "ca"));
    }

    private static class DesRule {
        private final int isEncrypt;
        private final String key;
        private final String obfuscatedName;

        DesRule(int isEncrypt, String key, String obfuscatedName) {
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
        public final int gameId;
        public String serverId = "";

        public Role(String uid, String name, int gameId) {
            this.uid = uid;
            this.name = name;
            this.gameId = gameId;
        }

        public Role(String uid, String name, int gameId, String serverId) {
            this.uid = uid;
            this.name = name;
            this.gameId = gameId;
            this.serverId = serverId;
        }
    }
}
