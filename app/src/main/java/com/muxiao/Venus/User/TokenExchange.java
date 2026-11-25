package com.muxiao.Venus.User;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.Home.JsonAccess;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;

/**
 * 通行证 V2 换票：{@code POST passport-api.mihoyo.com/account/ma-cn-session/app/exchange}。
 *
 * <p>用 SToken（{@code src_token.token_type=1}）兑换目标 token：
 * <ul>
 *   <li>{@link #TARGET_LTOKEN} = 2 → LToken</li>
 *   <li>{@link #TARGET_COOKIE_TOKEN} = 4 → CookieToken</li>
 * </ul>
 * 取代已停止正常工作的旧接口 {@code getLTokenBySToken}（GET，passport/auth）与
 * {@code getCookieAccountInfoBySToken}（GET，api-takumi/auth）。
 *
 * <p>关键约束（均由真实抓包核对）：
 * <ul>
 *   <li>body 必须与实际请求逐字节一致：DS 中的 {@code b=} 就是这段序列化结果，故这里只序列化一次，
 *       签名与发包共用同一个字符串（{@link tools#postJson}）；</li>
 *   <li>DS 用账号 SDK 专用 salt（passport），算法见 {@link HeaderManager#getDS_passport}；</li>
 *   <li>必须带 mid；缺 mid 无法换票（旧接口在无 mid 时仍可用，见调用方的兜底分支）。</li>
 * </ul>
 *
 * <p>换出的 token 与米游社 App 注入 WebView 的形态一致（v2），因此 {@code ltoken} 与 {@code ltoken_v2}、
 * {@code cookie_token} 与 {@code cookie_token_v2} 应写同一个值，本类只负责取值，写回由调用方决定。
 */
public final class TokenExchange {

    /** 目标 token 类型：LToken */
    public static final int TARGET_LTOKEN = 2;
    /** 目标 token 类型：CookieToken */
    public static final int TARGET_COOKIE_TOKEN = 4;

    private static final String TAG = "VenusTokenExchange";
    private static final int RETCODE_OK = 0;
    /** SToken 失效 */
    private static final int RETCODE_LOGIN_EXPIRED = -100;
    /** SToken 已被吊销 */
    private static final int RETCODE_STOKEN_REVOKED = -3260;

    private TokenExchange() {
    }

    /**
     * 换票失败。{@link #stokenInvalid} 区分「凭证本身失效（需重新登录）」与「瞬时失败（限流/风控/网络）」，
     * 避免把可重试的失败报成要重新登录。
     */
    public static final class ExchangeException extends RuntimeException {
        /** 服务端 retcode；本地前置校验失败时为 -1 */
        public final int retcode;
        /** true = SToken 已失效（-100 / -3260），调用方应提示重新登录 */
        public final boolean stokenInvalid;

        ExchangeException(String message, int retcode, boolean stokenInvalid) {
            super(message);
            this.retcode = retcode;
            this.stokenInvalid = stokenInvalid;
        }
    }

    /**
     * 用 SToken 兑换目标 token（阻塞，需在非主线程调用）。
     *
     * @param stoken 账号 SToken（不是 LToken）
     * @param mid    账号 mid（新格式为字符串，旧账号为数值串，统一按字符串下发）
     * @param target {@link #TARGET_LTOKEN} 或 {@link #TARGET_COOKIE_TOKEN}
     * @return 兑换到的 token 字符串（非空）
     * @throws ExchangeException 业务码非 0、响应缺 token 或本地前置校验失败
     */
    public static String exchange(HeaderManager headerManager, String stoken, String mid, int target) {
        if (stoken == null || stoken.isEmpty())
            throw new ExchangeException("stoken 为空", -1, true);
        if (mid == null || mid.isEmpty())
            throw new ExchangeException("mid 为空，无法换票", -1, false);

        String body = buildBody(stoken, mid, target);
        // 注意：签名与发包必须用同一个 body 字符串
        String response = tools.postJson(Constants.Urls.PASSPORT_EXCHANGE_URL,
                headerManager.get_exchange_headers(body), body).body;
        JsonObject json;
        try {
            json = JsonParser.parseString(response).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ExchangeException("换票响应非 JSON：" + tools.describeError(e), -1, false);
        }
        int retcode = JsonAccess.retcode(json);
        if (retcode != RETCODE_OK) {
            boolean invalid = retcode == RETCODE_LOGIN_EXPIRED || retcode == RETCODE_STOKEN_REVOKED;
            throw new ExchangeException(JsonAccess.message(json, ""), retcode, invalid);
        }
        String token = readToken(json);
        if (token == null) throw new ExchangeException("换票响应缺少 token", RETCODE_OK, false);
        Logger.debug(TAG, "exchange ok: target=" + target + " tokenLen=" + token.length());
        return token;
    }

    /**
     * 构造换票请求体。字段顺序即签名顺序，勿调整：
     * {@code {"dst_token_type":N,"src_token":{"token":"...","token_type":1},"mid":"..."}}。
     */
    static String buildBody(String stoken, String mid, int target) {
        JsonObject srcToken = new JsonObject();
        srcToken.addProperty("token", stoken);
        srcToken.addProperty("token_type", 1);
        JsonObject body = new JsonObject();
        body.addProperty("dst_token_type", target);
        body.add("src_token", srcToken);
        body.addProperty("mid", mid);
        return body.toString();
    }

    /**
     * 取响应中的 token：标准形态为 {@code data.token.token}；另兼容 {@code data.ltoken} /
     * {@code data.cookie_token} 等平铺形态，避免服务端微调后整体判失败。
     */
    private static String readToken(JsonObject json) {
        JsonObject data = JsonAccess.data(json);
        JsonObject tokenObject = JsonAccess.optObject(data, "token");
        String token = JsonAccess.optString(tokenObject, "token", null);
        if (token == null) token = JsonAccess.optString(data, "ltoken", null);
        if (token == null) token = JsonAccess.optString(data, "cookie_token", null);
        return (token == null || token.isEmpty()) ? null : token;
    }
}
