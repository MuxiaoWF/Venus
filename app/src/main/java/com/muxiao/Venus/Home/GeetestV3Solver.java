package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.ApiClient;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 极验 v3 协议客户端（移植自 luguoyixiazi/test_nine 的 crack.py）。
 *
 * <p>流程：gettype → get.php 换 c/s → fullpage → get.php 取图 → 模型识别 → ajax.php 提交答案 → 返回 validate。
 * 加密链：载荷 → AES-128-CBC → RSA 加密 key → 自定义 6bit 字符表编码。
 */
public final class GeetestV3Solver {

    private static final String TAG = "VenusCaptcha";

    /** 极验前端 JS 固定公钥，用于加密 AES key。 */
    private static final String GEETEST_RSA_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDB45NNFhRGWzMFPn9I7k7IexS5"
                    + "XviJR3E9Je7L/350x5d9AtwdlFH3ndXRwQwprLaptNb7fQoCebZxnhdyVl8Jr2J3"
                    + "FZGSIa75GJnK4IwNaG10iyCjYDviMYymvCtZcGWSqSGdC/Bcn2UCOiHSMwgHJSrg"
                    + "Bm1Zzu+l8nSOqAurgQIDAQAB";

    private static final String AES_IV = "0000000000000000";
    /** {@link #encode} 输出的自定义 6bit 字符表。 */
    private static final String ENCODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789()";
    /** 轨迹编码字符表（postprocess 位拼接结果查此表）。 */
    private static final String PATH_CHARS = "()*,-./0123456789:?@ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz~";
    private static final String FINGERPRINT_PLACEHOLDER =
            "-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!-1!!";
    private static final String REQUEST_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final String gt;
    private final String challenge;
    private final Random rng = new Random();

    private String aesKey;
    private String encKey;
    /** get.php 返回的挑战参数（postprocess/transform 的三元组取值来源）。 */
    private List<Double> c;
    private String s;
    /** get.php 返回的图片相对路径，verify 的 pic 字段原样回传。 */
    private String picPath;
    /** 图片资源服务器 host（resource_servers[0]，无尾斜杠）。 */
    private String picHost;

    private GeetestV3Solver(String gt, String challenge) {
        this.gt = gt;
        this.challenge = challenge;
    }

    /** 一次成功验证的结果：validate 即 API2 二次验证所需的凭证。 */
    public static final class Result {
        public final boolean success;
        public final String validate;
        public final String picType;
        public final String error;

        private Result(boolean success, String validate, String picType, String error) {
            this.success = success;
            this.validate = validate;
            this.picType = picType;
            this.error = error;
        }

        static Result ok(String validate, String picType) {
            return new Result(true, validate, picType, null);
        }

        static Result fail(String error, String picType) {
            return new Result(false, null, picType, error);
        }
    }

    /**
     * 答案提供者：picType 不同坐标格式不同——"nine" 为 {@code col_row}（0 基），
     * "icon"/"icon1" 为 {@code x_y}（0~10000 归一化）。返回 null/空 = 识别失败。
     */
    public interface AnswerProvider {
        List<String> answer(String picType, byte[] picBytes) throws Exception;
    }

    /**
     * 完整求解：取挑战 → 取图 → 识别 → 提交 → 返回 validate。
     * @return 永不为 null；失败时 {@link Result#success} 为 false 并带原因
     */
    public static Result solve(@NonNull String gt, @NonNull String challenge,
                               AnswerProvider provider, long minTotalMs) {
        GeetestV3Solver solver = new GeetestV3Solver(gt, challenge);
        try {
            return solver.run(provider, minTotalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("interrupted", null);
        } catch (Exception e) {
            Logger.debug(TAG, "GeetestV3Solver: " + e);
            return Result.fail(describe(e), null);
        }
    }

    private Result run(AnswerProvider provider, long minTotalMs) throws Exception {
        long start = System.currentTimeMillis();

        JsonObject typeData = getType();
        initKeys();
        getCS(typeData);
        TimeUnit.MILLISECONDS.sleep(400L + rng.nextInt(200));
        ajaxFullpage();
        String[] picInfo = getPic();
        byte[] picBytes = downloadPic(picPath);

        List<String> points = null;
        String identifyError = null;
        try {
            points = provider == null ? null : provider.answer(picInfo[0], picBytes);
        } catch (Throwable t) {
            identifyError = describe(t);
        }
        if (points == null || points.isEmpty()) {
            String why = identifyError != null ? identifyError : "no answer from provider";
            Logger.debug(TAG, "GeetestV3Solver: identify failed: " + why);
            return Result.fail("identify failed: " + why, picInfo[0]);
        }

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < minTotalMs)
            TimeUnit.MILLISECONDS.sleep(minTotalMs - elapsed);

        String validate = verify(points);
        if (validate == null || validate.isEmpty())
            return Result.fail("verify returned no validate", picInfo[0]);
        return Result.ok(validate, picInfo[0]);
    }

    // ==================== HTTP 步骤 ====================

    private JsonObject getType() {
        Map<String, String> params = single("gt", gt);
        params.put("callback", callback());
        String resp = sendGetRequest(Constants.Urls.GEETEST_GETTYPE_URL, uaHeaders(), params);
        JsonObject data = parseJsonp(resp);
        if (data == null) throw new IllegalStateException("gettype returned no data");
        return data;
    }

    private void getCS(JsonObject typeData) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("gt", gt);
        payload.addProperty("challenge", challenge);
        payload.addProperty("offline", false);
        payload.addProperty("new_captcha", true);
        payload.addProperty("product", "embed");
        payload.addProperty("width", "300px");
        payload.addProperty("https", true);
        payload.addProperty("protocol", "https://");
        if (typeData != null) {
            for (Map.Entry<String, JsonElement> e : typeData.entrySet()) payload.add(e.getKey(), e.getValue());
        }
        payload.addProperty("cc", 16);
        payload.addProperty("ww", true);
        payload.addProperty("i", FINGERPRINT_PLACEHOLDER);

        Map<String, String> params = single("gt", gt);
        params.put("challenge", challenge);
        params.put("lang", "zh-cn");
        params.put("pt", "0");
        params.put("client_type", "web");
        params.put("callback", callback());
        params.put("w", encode(aesEncrypt(payload.toString())) + encKey);
        String resp = sendGetRequest(Constants.Urls.GEETEST_GET_URL, uaHeaders(), params);
        JsonObject data = parseJsonp(resp);
        if (data == null || !data.has("c") || !data.has("s"))
            throw new IllegalStateException("get.php returned no c/s");
        c = new ArrayList<>();
        for (JsonElement el : data.getAsJsonArray("c")) c.add(el.getAsDouble());
        s = data.get("s").getAsString();
    }

    private void ajaxFullpage() throws Exception {
        String tt = transform(encodeMousePath(syntheticShortPath(), c, s), c, s);
        JsonObject payload = new JsonObject();
        payload.addProperty("lang", "zh-cn");
        payload.addProperty("type", "fullpage");
        payload.addProperty("tt", tt);
        payload.addProperty("light", "DIV_0");
        payload.addProperty("s", "c7c3e21112fe4f741921cb3e4ff9f7cb");
        payload.addProperty("h", "321f9af1e098233dbd03f250fd2b5e21");
        payload.addProperty("hh", "39bd9cad9e425c3a8f51610fd506e3b3");
        payload.addProperty("hi", "09eb21b3ae9542a9bc1e8b63b3d9a467");
        payload.addProperty("vip_order", -1);
        payload.addProperty("ct", -1);
        payload.add("ep", ajaxEp());
        payload.addProperty("passtime", 1600);
        payload.addProperty("rp", md5(gt + challenge + s));
        payload.addProperty("captcha_token", "2064329542");
        payload.addProperty("tsfq", "xovrayel");

        Map<String, String> params = single("gt", gt);
        params.put("challenge", challenge);
        params.put("lang", "zh-cn");
        params.put("pt", "0");
        params.put("client_type", "web");
        params.put("callback", callback());
        params.put("w", encode(aesEncrypt(payload.toString())));
        sendGetRequest(Constants.Urls.GEETEST_AJAX_URL, uaHeaders(), params);
    }

    private String[] getPic() {
        Map<String, String> params = single("is_next", "true");
        params.put("type", "click");
        params.put("gt", gt);
        params.put("challenge", challenge);
        params.put("lang", "zh-cn");
        params.put("https", "true");
        params.put("protocol", "https://");
        params.put("offline", "false");
        params.put("product", "float");
        params.put("api_server", "api.geevisit.com");
        params.put("isPC", "true");
        params.put("autoReset", "true");
        params.put("width", "100%");
        params.put("callback", callback());
        String resp = sendGetRequest(Constants.Urls.GEETEST_API_SERVER + "/get.php", uaHeaders(), params);
        JsonObject data = parseJsonp(resp);
        if (data == null || !data.has("pic"))
            throw new IllegalStateException("get.php(pic) returned no pic");
        picPath = data.get("pic").getAsString();
        String picType = data.has("pic_type") ? data.get("pic_type").getAsString() : "unknown";
        if (picType.equals("icon") && picPath.contains("80/2023-12-04T12/icon")) picType = "icon1";
        if (data.has("resource_servers") && !data.getAsJsonArray("resource_servers").isEmpty()) {
            String host = data.getAsJsonArray("resource_servers").get(0).getAsString();
            picHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        }
        return new String[]{picType, picPath};
    }

    private byte[] downloadPic(String path) throws Exception {
        if (path == null || path.isEmpty()) throw new IllegalStateException("pic path empty");
        String host = picHost != null ? picHost : "static.geetest.com";
        String url = "https://" + host + (path.startsWith("/") ? path : "/" + path);
        Request request = new Request.Builder().url(url).header("User-Agent", REQUEST_UA).build();
        try (Response response = ApiClient.get().newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new IllegalStateException("pic download http " + response.code());
            return response.body().bytes();
        }
    }

    private String verify(List<String> points) throws Exception {
        String a = String.join(",", points);
        String tt = encodeMousePath(syntheticAnswerPath(), c, s);
        long passtime = 1600;

        JsonObject ep = new JsonObject();
        com.google.gson.JsonArray ca = new com.google.gson.JsonArray();
        long[][] caPairs = {{524, 209, 0, 1819}, {558, 299, 0, 428}, {563, 95, 0, 952}, {670, 407, 3, 892}};
        for (long[] p : caPairs) {
            JsonObject o = new JsonObject();
            o.addProperty("x", p[0]);
            o.addProperty("y", p[1]);
            o.addProperty("t", p[2]);
            o.addProperty("dt", p[3]);
            ca.add(o);
        }
        ep.add("ca", ca);
        ep.addProperty("v", "3.1.0");
        ep.addProperty("$_FB", false);
        ep.addProperty("me", true);
        ep.add("tm", verifyTm());
        JsonObject payload = new JsonObject();
        payload.addProperty("lang", "zh-cn");
        payload.addProperty("passtime", passtime);
        payload.addProperty("a", a);
        payload.addProperty("pic", picPath);
        payload.addProperty("tt", tt);
        payload.add("ep", ep);
        payload.addProperty("h9s9", "1816378497");
        payload.addProperty("rp", md5(gt + challenge + passtime));

        Map<String, String> params = single("gt", gt);
        params.put("challenge", challenge);
        params.put("lang", "zh-cn");
        params.put("pt", "0");
        params.put("client_type", "web");
        params.put("w", encode(aesEncrypt(payload.toString())) + encKey);
        String resp = sendGetRequest(Constants.Urls.GEETEST_API_SERVER + "/ajax.php", uaHeaders(), params);
        JsonObject data = parseJsonp(resp);
        if (data != null && data.has("validate")) return data.get("validate").getAsString();
        return null;
    }

    // ==================== 加密与编码 ====================

    private void initKeys() throws Exception {
        StringBuilder kb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int v = (int) ((1.0 + rng.nextDouble()) * 65536);
            String hex = Integer.toHexString(v);
            kb.append(hex, 1, hex.length());
        }
        aesKey = kb.toString();

        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(
                Base64.decode(GEETEST_RSA_PUBLIC_KEY, Base64.DEFAULT)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pub);
        encKey = toHex(cipher.doFinal(aesKey.getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== 加密与编码 ====================

    private byte[] aesEncrypt(String content) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8)));
        return cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String md5(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static String encode(byte[] input) {
        StringBuilder out = new StringBuilder();
        String padding = "";
        for (int i = 0; i < input.length; i += 3) {
            int chunkLen = Math.min(3, input.length - i);
            int b0 = input[i] & 0xFF;
            int b1 = chunkLen >= 2 ? input[i + 1] & 0xFF : 0;
            int b2 = chunkLen == 3 ? input[i + 2] & 0xFF : 0;
            int value = (b0 << 16) + (b1 << 8) + b2;
            out.append(encodeChar(transformValue(value, 7274496)));
            out.append(encodeChar(transformValue(value, 9483264)));
            if (chunkLen >= 2) out.append(encodeChar(transformValue(value, 19220)));
            if (chunkLen == 3) out.append(encodeChar(transformValue(value, 235)));
            padding = chunkLen == 3 ? "" : chunkLen == 2 ? "." : "..";
        }
        return out + padding;
    }

    private static int transformValue(int value, int bitMask) {
        int result = 0;
        for (int r = 23; r >= 0; r--) {
            if (((bitMask >> r) & 1) != 0) result = (result << 1) + ((value >> r) & 1);
        }
        return result;
    }

    private static char encodeChar(int index) {
        return index >= 0 && index < ENCODE_CHARS.length() ? ENCODE_CHARS.charAt(index) : '.';
    }

    // ==================== 轨迹编码 ====================

    private static String encodeMousePath(List<Object[]> path, List<Double> c, String s) {
        return postprocessFixed(process(preprocess(path)), c, s);
    }

    private static List<Object[]> preprocess(List<Object[]> path) {
        if (path == null || path.isEmpty()) return new ArrayList<>();
        String source = null;
        for (Object[] e : path) {
            if (e != null && e.length > 4 && e[4] != null) {
                source = String.valueOf(e[4]);
                break;
            }
        }
        List<Object[]> filtered = path;
        if (source != null && !source.isEmpty()) {
            String prefix = null;
            for (String cand : new String[]{"mouse", "touch", "pointer", "MSPointer"}) {
                if (source.startsWith(cand)) prefix = cand;
            }
            if (prefix != null) {
                filtered = new ArrayList<>();
                for (Object[] e : path) {
                    String type = String.valueOf(e[0]);
                    if (type.equals("move") || type.equals("down") || type.equals("up")) {
                        String v = e.length > 4 && e[4] != null ? String.valueOf(e[4]) : "";
                        if (!v.startsWith(prefix)) continue;
                    }
                    filtered.add(e);
                }
            }
        }
        // 只取最近 300 个事件，差分出相对坐标与时间增量
        long prevX = 0, prevY = 0, prevTs = 0;
        List<Object[]> out = new ArrayList<>();
        int count = filtered.size();
        int start = count < 300 ? 0 : count - 300;
        for (int i = start; i < count; i++) {
            Object[] e = filtered.get(i);
            String h = String.valueOf(e[0]);
            if (h.equals("down") || h.equals("move") || h.equals("up") || h.equals("scroll")) {
                long x = ((Number) e[1]).longValue();
                long y = ((Number) e[2]).longValue();
                long ts = ((Number) e[3]).longValue();
                long dt = prevTs != 0 ? clampShort(ts - prevTs) : 0;
                out.add(new Object[]{h, new long[]{clampShort(x - prevX), clampShort(y - prevY)}, dt});
                prevX = x;
                prevY = y;
                prevTs = ts;
            } else if (h.equals("blur") || h.equals("focus") || h.equals("unload")) {
                long v = ((Number) e[1]).longValue();
                out.add(new Object[]{h, clampShort(prevTs != 0 ? clampShort(v - prevTs) : 0)});
                prevTs = v;
            }
        }
        return out;
    }

    private static long clampShort(long v) {
        if (v > 32767) return 32767;
        if (v < -32767) return -32767;
        return v;
    }

    private static String process(List<Object[]> pp) {
        List<String> names = new ArrayList<>();
        List<Long> pos = new ArrayList<>();
        List<Long> rx = new ArrayList<>(), ry = new ArrayList<>();
        for (Object[] e : pp) {
            names.add(String.valueOf(e[0]));
            if (e.length == 2) pos.add(((Number) e[1]).longValue());
            else pos.add(((Number) e[2]).longValue());
            if (e.length == 3) {
                long[] d = (long[]) e[1];
                rx.add(d[0]);
                ry.add(d[1]);
            }
        }
        String bits = d(names) + g(pos, false) + g(rx, true) + g(ry, true);
        if (bits.length() % 6 != 0) bits += pad(0, 6 - bits.length() % 6);
        return u(bits);
    }

    private static String d(List<String> names) {
        List<Integer> t = new ArrayList<>();
        int n = names.size(), r = 0;
        while (r < n) {
            String name = names.get(r);
            int s = 0;
            while (true) {
                if (s >= 16) break;
                int o = r + s + 1;
                if (o >= n) break;
                if (!names.get(o).equals(name)) break;
                s++;
            }
            r += 1 + s;
            int code = codeOf(name);
            if (s != 0) {
                t.add(code | 8);
                t.add(s - 1);
            } else {
                t.add(code);
            }
        }
        StringBuilder out = new StringBuilder(pad(n | 32768, 16));
        for (int v : t) out.append(pad(v, 4));
        return out.toString();
    }

    private static int codeOf(String name) {
        switch (name) {
            case "move": return 0;
            case "down": return 1;
            case "up": return 2;
            case "scroll": return 3;
            case "focus": return 4;
            case "blur": return 5;
            case "unload": return 6;
            default: return 7;
        }
    }

    /** RLE 压缩 + 动态位宽打包；signed 时附加符号位序列。 */
    private static String g(List<Long> input, boolean signed) {
        List<Long> e = new ArrayList<>();
        int n = input.size(), r = 0;
        while (r < n) {
            int s = 1;
            long o = input.get(r);
            long abs = Math.abs(o);
            while (true) {
                if (n <= r + s) break;
                if (input.get(r + s) != o) break;
                if (abs >= 127 || s >= 127) break;
                s++;
            }
            if (s > 1) e.add((o < 0 ? 49152 : 32768) | ((long) s << 7) | abs);
            else e.add(o);
            r += s;
        }
        StringBuilder sBits = new StringBuilder(), oBits = new StringBuilder();
        for (long v : e) {
            int bits = (int) Math.ceil(Math.log(Math.abs(v) + 1) / Math.log(16));
            if (bits == 0) bits = 1;
            sBits.append(pad(bits - 1, 2));
            oBits.append(pad(Math.abs(v), bits * 4));
        }
        StringBuilder signs = new StringBuilder();
        if (signed) {
            for (long v : e) {
                if (v != 0 && (v >> 15) != 1) signs.append(v < 0 ? '1' : '0');
            }
        }
        return pad(e.size() | 32768, 16) + sBits + oBits + signs;
    }

    private static String pad(long e, int t) {
        String n = Long.toBinaryString(e);
        StringBuilder r = new StringBuilder();
        int i = n.length() + 1;
        while (i <= t) {
            i++;
            r.append('0');
        }
        return r + n;
    }

    private static String u(String e) {
        StringBuilder t = new StringBuilder();
        int n = e.length() / 6;
        for (int r = 0; r < n; r++) {
            t.append(PATH_CHARS.charAt(Integer.parseInt(e.substring(6 * r, 6 * (r + 1)), 2)));
        }
        return t.toString();
    }

    /** 按 c 三元组把 s 中的十六进制字符插回串中（postprocess）。 */
    private static String postprocessFixed(String e, List<Double> c, String s) {
        if (c == null || c.size() < 5 || s == null || s.isEmpty()) return e;
        double a0 = c.get(0), a2 = c.get(2), a4 = c.get(4);
        StringBuilder out = new StringBuilder(e);
        int i = 0;
        while (i + 2 <= s.length()) {
            String hex = s.substring(i, i + 2);
            i += 2;
            int v = Integer.parseInt(hex, 16);
            int at = (int) ((a0 * v * v + a2 * v + a4) % e.length());
            out.insert(at, (char) v);
        }
        return out.toString();
    }

    /** transform：与 postprocess 同构，但模数取增长中的 out.length()。 */
    private static String transform(String e, List<Double> c, String s) {
        if (c == null || c.size() < 5 || s == null || s.isEmpty()) return e;
        double a0 = c.get(0), a2 = c.get(2), a4 = c.get(4);
        StringBuilder out = new StringBuilder(e);
        int i = 0;
        while (i + 2 <= s.length()) {
            String hex = s.substring(i, i + 2);
            i += 2;
            int v = Integer.parseInt(hex, 16);
            int at = (int) ((a0 * v * v + a2 * v + a4) % out.length());
            out.insert(at, (char) v);
        }
        return out.toString();
    }

    // ==================== 合成轨迹与指纹快照 ====================

    private static List<Object[]> syntheticShortPath() {
        long base = System.currentTimeMillis();
        List<Object[]> path = new ArrayList<>();
        path.add(move(385, 313, base, "pointermove"));
        path.add(move(385, 315, base + 2, "pointermove"));
        path.add(move(386, 315, base + 10, "pointermove"));
        path.add(move(387, 315, base + 18, "pointermove"));
        path.add(move(387, 316, base + 24, "pointermove"));
        path.add(move(388, 316, base + 40, "pointermove"));
        path.add(move(388, 317, base + 54, "pointermove"));
        path.add(move(388, 317, base + 422, "pointerdown"));
        path.add(new Object[]{"focus", base + 423});
        path.add(move(388, 317, base + 468, "pointerup"));
        return path;
    }

    private static List<Object[]> syntheticAnswerPath() {
        long base = System.currentTimeMillis();
        List<Object[]> path = new ArrayList<>();
        int x = 40, y = 620;
        for (int i = 0; i < 40; i++) {
            x += 6 + (i % 5);
            y += i % 2 == 0 ? -3 : 2;
            path.add(move(x, y, base + i * 12, "pointermove"));
        }
        path.add(move(x + 2, y, base + 500, "pointerdown"));
        for (int i = 0; i < 6; i++) path.add(move(x + 2 + i, y + 1, base + 510 + i * 15, "pointermove"));
        path.add(move(x + 8, y + 1, base + 610, "pointerup"));
        return path;
    }

    private static Object[] move(int x, int y, long ts, String source) {
        return new Object[]{"move", x, y, ts, source};
    }

    private JsonObject ajaxEp() {
        long now = System.currentTimeMillis();
        JsonObject ep = new JsonObject();
        ep.addProperty("v", "9.2.0-guwyxh");
        ep.addProperty("te", false);
        ep.addProperty("me", true);
        ep.addProperty("ven", "Google Inc. (Intel)");
        ep.addProperty("ren", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics (0x0000A7A0) Direct3D11 vs_5_0 ps_5_0, D3D11)");
        com.google.gson.JsonArray fp = new com.google.gson.JsonArray();
        fp.add("scroll");
        fp.add(0);
        fp.add(1602);
        fp.add(now - 300000);
        fp.add((String) null);
        ep.add("fp", fp);
        com.google.gson.JsonArray lp = new com.google.gson.JsonArray();
        lp.add("up");
        lp.add(386);
        lp.add(217);
        lp.add(now - 200000);
        lp.add("pointerup");
        ep.add("lp", lp);
        JsonObject em = new JsonObject();
        em.addProperty("ph", 0);
        em.addProperty("cp", 0);
        em.addProperty("ek", "11");
        em.addProperty("wd", 1);
        em.addProperty("nt", 0);
        em.addProperty("si", 0);
        em.addProperty("sc", 0);
        ep.add("em", em);
        ep.add("tm", tmFrom(now, 0, 238, 251, 0, 0, 1, 1, 1, 6, 112, 19, 112, 234, 236, 258, 948, 948, 950, 3067, 3067, 3069));
        ep.addProperty("dnf", "dnf");
        ep.addProperty("by", 0);
        return ep;
    }

    private JsonObject verifyTm() {
        long now = System.currentTimeMillis();
        return tmFrom(now, 0, 202, 210, 0, 0, 1, 1, 1, 10, 20, 0, 10, 198, 200, 215, 346, 346, 348, 1665, 1665, 1666);
    }

    /** tm 对象：a~u 共 21 个键，d/e/k 恒为 0。 */
    private JsonObject tmFrom(long base, int... deltas) {
        String[] keys = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k",
                "l", "m", "n", "o", "p", "q", "r", "s", "t", "u"};
        JsonObject tm = new JsonObject();
        for (int i = 0; i < keys.length; i++) {
            long v = (i == 3 || i == 4 || i == 10) ? 0 : base + deltas[i];
            tm.addProperty(keys[i], v);
        }
        return tm;
    }

    // ==================== 小工具 ====================

    private static Map<String, String> single(String k, String v) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put(k, v);
        return map;
    }

    private static Map<String, String> uaHeaders() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", REQUEST_UA);
        headers.put("Referer", "https://webstatic.mihoyo.com/");
        return headers;
    }

    private static String callback() {
        return "geetest_" + System.currentTimeMillis();
    }

    private static JsonObject parseJsonp(String resp) {
        if (resp == null) return null;
        int start = resp.indexOf('(');
        int end = resp.lastIndexOf(')');
        String body = start >= 0 && end > start ? resp.substring(start + 1, end) : resp;
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        return obj.has("data") && obj.get("data").isJsonObject() ? obj.getAsJsonObject("data") : obj;
    }

    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
