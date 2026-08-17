package com.muxiao.Venus.User;

import android.content.Context;
import android.util.Base64;

import com.google.gson.Gson;
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

    /** 需要极验验证时抛出，便于调用方给出针对性提示 */
    public static class AigisRequiredException extends RuntimeException {
        /** 服务端下发的 aigis 挑战数据（Base64 JSON），完成极验后需回填到 x-rpc-aigis */
        public final String aigisChallenge;

        AigisRequiredException(String message, String aigisChallenge) {
            super(message);
            this.aigisChallenge = aigisChallenge;
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
        int retcode = result.get("retcode").getAsInt();
        String message = result.has("message") && !result.get("message").isJsonNull()
                ? result.get("message").getAsString() : "";

        if (retcode != 0) {
            String aigisChallenge = response.header("x-rpc-aigis");
            if (retcode == RETCODE_NEED_AIGIS || (aigisChallenge != null && !aigisChallenge.isEmpty()))
                throw new AigisRequiredException(
                        context.getString(R.string.login_password_need_captcha), aigisChallenge);
            throw new RuntimeException(context.getString(
                    R.string.login_password_failed, retcode,
                    message.isEmpty() ? response.body : message));
        }

        JsonObject data = result.getAsJsonObject("data");
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
     * RSA/ECB/PKCS1Padding 加密后 Base64（单行，无换行）
     */
    private static String rsaEncrypt(Context context, String plain) {
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
                    R.string.login_password_encrypt_failed, String.valueOf(e.getMessage())), e);
        }
    }
}
