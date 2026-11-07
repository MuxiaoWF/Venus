package com.muxiao.Venus.User;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 国服短信验证码登录（passport-api / ma-cn-verifier + ma-cn-passport）。
 * <p>
 * 链路（对照 TeyvatGuide v0.13.0 的 {@code passportReq.captcha}）：
 * <ol>
 *   <li>{@code verifier/createLoginCaptcha}：提交 RSA 加密的区号与手机号，下发短信，
 *       返回 {@code action_type}（后续登录必须原样回传）与 {@code countdown}（重发倒计时）；</li>
 *   <li>{@code app/loginByMobileCaptcha}：提交同一组加密字段 + {@code action_type} + 明文验证码，
 *       直接返回 stoken（{@code data.token.token}）与 {@code user_info.mid/aid}。</li>
 * </ol>
 * 两个接口都不校验 DS（见 {@link HeaderManager#get_sms_headers}），但都可能在响应头
 * {@code x-rpc-aigis} 里下发极验任务：此时抛 {@link PasswordLogin.AigisRequiredException}，
 * 由调用方拉起 GT3 后带上极验头重提同一请求。
 * <p>
 * 与账密登录的差异：不需要 challenge 之外的东西，也不涉及 stoken 之外的凭证推导——
 * 拿到 stoken/mid 后与其余登录方式走同一套 {@code getLTokenBySToken} 收尾。
 */
public final class SmsLogin {

    /** 下发短信验证码 */
    private static final String CREATE_CAPTCHA_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-verifier/verifier/createLoginCaptcha";
    /** 用短信验证码完成登录 */
    private static final String LOGIN_BY_CAPTCHA_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/loginByMobileCaptcha";

    /** 区号固定 +86：该接口只服务国服账号，且 area_code 与手机号一样要 RSA 加密 */
    private static final String AREA_CODE = "+86";

    /** 需要极验（aigis）人机验证 */
    private static final int RETCODE_NEED_AIGIS = -3101;

    private static final Gson GSON = new Gson();

    private SmsLogin() {
    }

    /** 下发短信验证码的结果。 */
    public static final class CaptchaResult {
        /** 登录时必须回传的操作类型 */
        public final String actionType;
        /** 服务端给出的重发倒计时（秒），缺失或非法时为 0 */
        public final int countdown;

        CaptchaResult(String actionType, int countdown) {
            this.actionType = actionType;
            this.countdown = countdown;
        }
    }

    /** 短信验证码登录成功后的凭证，字段语义与 {@link PasswordLogin.Result} 一致。 */
    public static final class Result {
        public String stoken;
        public String mid;
        /** 账号 aid，等价于扫码登录里的 stuid */
        public String aid;
        public String loginTicket;
        public boolean realnameRequired;
        public boolean needRealperson;
    }

    /**
     * 下发短信验证码。需在后台线程调用。
     *
     * @param phone 11 位手机号（明文，方法内部加密，不做任何持久化）
     * @param aigis 极验完成后的 x-rpc-aigis，首次请求传 null
     * @throws PasswordLogin.AigisRequiredException 服务端要求极验时抛出
     */
    public static CaptchaResult sendCaptcha(Context context, HeaderManager headerManager,
                                            String phone, String aigis) {
        // 顺序与参考实现一致；area_code 同样走 RSA，不能发明文
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("area_code", PasswordLogin.rsaEncrypt(context, AREA_CODE));
        payload.put("mobile", PasswordLogin.rsaEncrypt(context, phone));
        String body = GSON.toJson(payload);

        Map<String, String> headers = headerManager.get_sms_headers(aigis);
        tools.HttpResponse response = tools.postJson(CREATE_CAPTCHA_URL, headers, body);

        JsonObject result = JsonParser.parseString(response.body).getAsJsonObject();
        int retcode = retcodeOf(result);
        if (retcode != 0)
            throw failure(context, response, result, retcode,
                    R.string.login_sms_captcha_failed);

        JsonObject data = dataOf(result);
        String actionType = optString(data, "action_type");
        if (actionType == null || actionType.isEmpty())
            throw new RuntimeException(context.getString(
                    R.string.login_sms_captcha_failed, retcode, response.body));
        return new CaptchaResult(actionType, optInt(data, "countdown"));
    }

    /**
     * 用短信验证码完成登录。需在后台线程调用。
     *
     * @param phone      11 位手机号（明文，内部加密）
     * @param code       收到的短信验证码
     * @param actionType {@link #sendCaptcha} 返回的 action_type
     * @param aigis      极验完成后的 x-rpc-aigis，首次请求传 null
     * @throws PasswordLogin.AigisRequiredException 服务端要求极验时抛出
     */
    public static Result login(Context context, HeaderManager headerManager, String phone,
                               String code, String actionType, String aigis) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("area_code", PasswordLogin.rsaEncrypt(context, AREA_CODE));
        payload.put("mobile", PasswordLogin.rsaEncrypt(context, phone));
        payload.put("action_type", actionType);
        payload.put("captcha", code);
        String body = GSON.toJson(payload);

        Map<String, String> headers = headerManager.get_sms_headers(aigis);
        tools.HttpResponse response = tools.postJson(LOGIN_BY_CAPTCHA_URL, headers, body);

        JsonObject result = JsonParser.parseString(response.body).getAsJsonObject();
        int retcode = retcodeOf(result);
        if (retcode != 0)
            throw failure(context, response, result, retcode,
                    R.string.login_sms_login_failed);

        JsonObject data = dataOf(result);
        if (data == null)
            throw new RuntimeException(context.getString(
                    R.string.login_sms_login_failed, retcode, response.body));

        Result parsed = new Result();
        // data.token.token 即 stoken
        JsonObject token = objOf(data, "token");
        parsed.stoken = optString(token, "token");
        if (parsed.stoken == null || parsed.stoken.isEmpty())
            throw new RuntimeException(context.getString(
                    R.string.login_sms_login_failed, retcode, response.body));

        JsonObject userInfo = objOf(data, "user_info");
        parsed.mid = optString(userInfo, "mid");
        if (parsed.mid == null || parsed.mid.isEmpty())
            throw new RuntimeException(context.getString(
                    R.string.login_sms_no_mid, response.body));
        parsed.aid = optString(userInfo, "aid");

        parsed.loginTicket = optString(data, "login_ticket");

        JsonObject realname = objOf(data, "realname_info");
        parsed.realnameRequired = realname != null && realname.has("required")
                && !realname.get("required").isJsonNull()
                && realname.get("required").getAsBoolean();
        parsed.needRealperson = data.has("need_realperson")
                && !data.get("need_realperson").isJsonNull()
                && data.get("need_realperson").getAsBoolean();
        return parsed;
    }

    /**
     * 统一失败出口：先看响应头是否下发极验任务（有则抛 {@link PasswordLogin.AigisRequiredException}
     * 让调用方拉起 GT3 重提），否则拼一条带 retcode/message 的失败异常。
     */
    private static RuntimeException failure(Context context, tools.HttpResponse response,
                                            JsonObject result, int retcode, int messageRes) {
        String aigisHeader = response.header("x-rpc-aigis");
        if (retcode == RETCODE_NEED_AIGIS || (aigisHeader != null && !aigisHeader.isEmpty())) {
            PasswordLogin.AigisInfo info = PasswordLogin.parseAigis(aigisHeader);
            if (info != null)
                throw new PasswordLogin.AigisRequiredException(
                        context.getString(R.string.login_password_need_captcha), info);
        }
        String message = optString(result, "message");
        return new RuntimeException(context.getString(messageRes, retcode,
                message == null || message.isEmpty() ? response.body : message));
    }

    /** 安全读取 retcode：缺失、JsonNull 或类型不符时返回哨兵值。 */
    private static int retcodeOf(JsonObject json) {
        if (json == null || !json.has("retcode") || json.get("retcode").isJsonNull())
            return Integer.MIN_VALUE;
        try {
            return json.get("retcode").getAsInt();
        } catch (RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static JsonObject dataOf(JsonObject json) {
        return objOf(json, "data");
    }

    private static JsonObject objOf(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return null;
        JsonElement el = json.get(key);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String optString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return null;
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** countdown 在参考实现那里是字符串（"60"），这里数字/字符串都接受，非法值返回 0。 */
    private static int optInt(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return 0;
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException e) {
            try {
                return Integer.parseInt(json.get(key).getAsString());
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
    }
}
