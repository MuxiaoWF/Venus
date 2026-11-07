package com.muxiao.Venus.User;

import android.content.Context;
import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.tools;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;

/**
 * 国服账号密码登录（passport-api / ma-cn-passport / loginByPassword）。
 * <p>
 * 关键点（均由真实抓包核对）：
 * <ul>
 *   <li>account / password 需用米游社 1024 位 RSA 公钥做 RSA/ECB/PKCS1Padding 加密后 Base64；</li>
 *   <li>DS 走「账号 SDK 专用 salt + 带 body 的 DS2」算法，非 K2/LK2；</li>
 *   <li>参与签名的 body 必须与实际发出的 body 逐字节一致，因此这里先用 Gson 序列化成
 *       字符串（Gson 默认会把 = 转义成 \\u003d，与官方 App 行为一致），再拿该字符串同时用于签名和发包；</li>
 *   <li>返回的 data.token.token 就是 stoken（token_type = 1），mid 在 data.user_info.mid 中。</li>
 * </ul>
 */
public final class PasswordLogin {

    /** 米游社登录接口 RSA 公钥（1024 位，密文固定 128 字节 → Base64 172 字符） */
    private static final String LOGIN_RSA_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDDvekdPMHN3AYhm/vktJT+YJr7"
                    + "cI5DcsNKqdsx5DZX0gDuWFuIjzdwButrIYPNmRJ1G8ybDIF7oDW2eEpm5sMbL9zs"
                    + "9ExXCdvqrn51qELbqj0XxtMTIpaCHFSI50PfPpTFV9Xt/hmyVwokoOXFlAEgCn+Q"
                    + "CgGs52bFoYMtyi+xEQIDAQAB";

    /** 需要极验（aigis）人机验证 */
    private static final int RETCODE_NEED_AIGIS = -3101;

    private static final Gson GSON = new Gson();

    private PasswordLogin() {
    }

    /**
     * 登录结果。stoken / mid / aid 为后续任务所需的核心凭证。
     */
    public static class Result {
        public String stoken;
        public String mid;
        /** 账号 aid，等价于扫码登录里的 stuid */
        public String aid;
        public String loginTicket;
        public boolean realnameRequired;
        public boolean needRealperson;
    }

    /**
     * 从 x-rpc-aigis 响应头解析出的极验任务信息。
     * sessionId 用于重提，gt/challenge 用于配置 GT3 弹窗。
     * v4 只有 gt（captchaId）、无 challenge，额外带 riskType（验证形式）。
     */
    public static class AigisInfo {
        public final String sessionId;
        public final String gt;
        public final String challenge;
        /** 是否为极验第四代（v4）。v4 无 challenge，gt 即 captchaId */
        public final boolean useV4;
        /** v4 指定的验证形式（滑块 / 点选 / 图标等），v3 为 null */
        public final String riskType;

        AigisInfo(String sessionId, String gt, String challenge, boolean useV4, String riskType) {
            this.sessionId = sessionId;
            this.gt = gt;
            this.challenge = challenge;
            this.useV4 = useV4;
            this.riskType = riskType;
        }
    }

    /** 需要极验验证时抛出，携带解析出的任务信息，便于调用方拉起验证后重提 */
    public static class AigisRequiredException extends RuntimeException {
        private final AigisInfo aigisInfo;

        AigisRequiredException(String message, AigisInfo aigisInfo) {
            super(message);
            this.aigisInfo = aigisInfo;
        }

        public AigisInfo getAigisInfo() {
            return aigisInfo;
        }
    }

    /**
     * 执行账号密码登录。需在后台线程调用。
     *
     * @param account  手机号或邮箱（明文，方法内部加密，不做任何持久化）
     * @param password 密码（明文，同上）
     * @param aigis    极验通过后的 x-rpc-aigis，首次登录传 null
     */
    public static Result login(Context context, HeaderManager headerManager,
                               String account, String password, String aigis) {
        // 顺序固定为 account、password，与官方请求体一致
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("account", rsaEncrypt(context, account));
        payload.put("password", rsaEncrypt(context, password));
        // 先序列化，签名与发包共用同一字符串，避免 DS 与 body 不一致
        String body = GSON.toJson(payload);

        Map<String, String> headers = headerManager.get_password_headers(body, aigis);
        tools.HttpResponse response = tools.postJson(Constants.Urls.PASSWORD_LOGIN_URL, headers, body);

        JsonObject result = JsonParser.parseString(response.body).getAsJsonObject();
        JsonElement retcodeElement = result.get("retcode");
        // retcode 缺失/JsonNull 时原实现直接 getAsInt() 抛 NPE，此处统一按登录失败处理
        int retcode = (retcodeElement != null && !retcodeElement.isJsonNull()) ? retcodeElement.getAsInt() : -1;
        String message = result.has("message") && !result.get("message").isJsonNull()
                ? result.get("message").getAsString() : "";

        if (retcode != 0) {
            String aigisHeader = response.header("x-rpc-aigis");
            boolean needAigis = retcode == RETCODE_NEED_AIGIS
                    || (aigisHeader != null && !aigisHeader.isEmpty());
            if (needAigis) {
                // 解析出 session_id/gt/challenge 后由调用方拉起极验；解析失败则落到普通失败兜底
                AigisInfo info = parseAigis(aigisHeader);
                if (info != null)
                    throw new AigisRequiredException(
                            context.getString(R.string.login_password_need_captcha), info);
            }
            throw new RuntimeException(context.getString(
                    R.string.login_password_failed, retcode,
                    message.isEmpty() ? response.body : message));
        }

        JsonObject data = result.getAsJsonObject("data");
        if (data == null)
            throw new RuntimeException(context.getString(R.string.login_password_no_token, response.body));
        Result parsed = new Result();
        // data.token.token 即 stoken
        JsonObject token = data.getAsJsonObject("token");
        if (token == null || !token.has("token") || token.get("token").isJsonNull())
            throw new RuntimeException(context.getString(R.string.login_password_no_token, response.body));
        parsed.stoken = token.get("token").getAsString();

        JsonObject userInfo = data.getAsJsonObject("user_info");
        if (userInfo == null || !userInfo.has("mid") || userInfo.get("mid").isJsonNull())
            throw new RuntimeException(context.getString(R.string.login_password_no_mid, response.body));
        parsed.mid = userInfo.get("mid").getAsString();
        parsed.aid = userInfo.has("aid") && !userInfo.get("aid").isJsonNull()
                ? userInfo.get("aid").getAsString() : null;

        if (data.has("login_ticket") && !data.get("login_ticket").isJsonNull())
            parsed.loginTicket = data.get("login_ticket").getAsString();

        JsonObject realname = data.getAsJsonObject("realname_info");
        parsed.realnameRequired = realname != null && realname.has("required")
                && realname.get("required").getAsBoolean();
        parsed.needRealperson = data.has("need_realperson")
                && !data.get("need_realperson").isJsonNull()
                && data.get("need_realperson").getAsBoolean();

        return parsed;
    }

    /**
     * 解析 x-rpc-aigis 响应头。真实响应头外层是一个 JSON 数组（取首个元素），
     * 元素含 session_id，data 是嵌套的 JSON 字符串：
     * <ul>
     *   <li>v3：含 gt/challenge，配置 GT3 弹窗；</li>
     *   <li>v4：use_v4=true，只有 gt（captchaId）、无 challenge，额外带 risk_type。</li>
     * </ul>
     * 任何字段缺失或格式异常均返回 null，由调用方走普通失败分支，避免风控残缺 JSON 崩溃。
     */
    static AigisInfo parseAigis(String header) {
        if (header == null || header.isEmpty())
            return null;
        try {
            JsonElement parsed = JsonParser.parseString(header);
            JsonObject outer;
            // 外层可能是数组（真实抓包为 [{...}]），也可能直接是对象，两者都兼容
            if (parsed.isJsonArray()) {
                if (parsed.getAsJsonArray().isEmpty())
                    return null;
                outer = parsed.getAsJsonArray().get(0).getAsJsonObject();
            } else {
                outer = parsed.getAsJsonObject();
            }
            if (!outer.has("session_id") || outer.get("session_id").isJsonNull())
                return null;
            String sessionId = outer.get("session_id").getAsString();
            if (!outer.has("data") || outer.get("data").isJsonNull())
                return null;
            JsonObject inner = JsonParser.parseString(
                    outer.get("data").getAsString()).getAsJsonObject();
            if (!inner.has("gt") || inner.get("gt").isJsonNull())
                return null;
            String gt = inner.get("gt").getAsString();
            boolean useV4 = inner.has("use_v4") && !inner.get("use_v4").isJsonNull()
                    && inner.get("use_v4").getAsBoolean();
            if (useV4) {
                // v4 只有 captchaId（gt）与 risk_type，challenge 由 SDK 内部向极验申请
                String riskType = inner.has("risk_type") && !inner.get("risk_type").isJsonNull()
                        ? inner.get("risk_type").getAsString() : null;
                return new AigisInfo(sessionId, gt, null, true, riskType);
            }
            // v3 必须有 challenge
            if (!inner.has("challenge") || inner.get("challenge").isJsonNull())
                return null;
            return new AigisInfo(sessionId, gt, inner.get("challenge").getAsString(), false, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 用极验 SDK 返回的三参数组装重提头：
     * {@code session_id + ";" + base64(极验结果JSON)}。
     * Base64 采用无换行的标准编码（与官方 StdEncoding 一致）。
     */
    public static String buildAigisHeader(AigisInfo info, String geetestChallenge,
                                          String geetestValidate, String geetestSeccode) {
        Map<String, String> geetest = new LinkedHashMap<>();
        geetest.put("geetest_challenge", geetestChallenge);
        geetest.put("geetest_validate", geetestValidate);
        geetest.put("geetest_seccode", geetestSeccode);
        String geetestJson = GSON.toJson(geetest);
        String base64Geetest = Base64.encodeToString(
                geetestJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return info.sessionId + ";" + base64Geetest;
    }

    /**
     * 组装 v4 的重提头：{@code session_id + ";" + base64(validate JSON)}。
     * <p>
     * 与 v3 不同，v4 的 validate 是极验 SDK 直接给出的原生对象
     * （lot_number / captcha_output / pass_token / gen_time / captcha_id / sign_token），
     * 不做字段重排、不挑字段，整体 base64 即可——顺序与内容都参与极验服务端校验。
     * 该字符串由 WebView 中的 {@code getValidate()} 序列化得到，非空即为验证通过。
     */
    public static String buildAigisV4Header(AigisInfo info, String validateJson) {
        String base64Validate = Base64.encodeToString(
                validateJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return info.sessionId + ";" + base64Validate;
    }

    /**
     * RSA/ECB/PKCS1Padding 加密后 Base64（单行，无换行）。
     * <p>
     * 包内可见：{@link SmsLogin} 也用同一把账号 SDK 公钥加密 area_code 与手机号。
     */
    static String rsaEncrypt(Context context, String plain) {
        try {
            byte[] keyBytes = Base64.decode(LOGIN_RSA_PUBLIC_KEY, Base64.DEFAULT);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(context.getString(
                    R.string.login_password_encrypt_failed, tools.describeError(e)), e);
        }
    }
}
