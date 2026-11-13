package com.muxiao.Venus.User;

import android.content.Context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器 Cookie 导入登录（国服）：账密登录会触发极验，验证链路被风控拒签就登不进去，这里提供一条完全绕开极验的入口。
 * 用户从电脑浏览器粘贴米游社 Cookie，解析出 stoken / ltoken / mid / stuid，落到与扫码、密码登录相同的凭证槽位，
 * 下游签到任务无需区分来源。
 * <p>
 * 字段来源（v2 优先）：stuid = ltuid_v2/account_id_v2/ltuid/account_id；mid = account_mid_v2/ltmid_v2/mid（缺失退化为 stuid）；
 * stoken = stoken_v2/stoken；ltoken = ltoken_v2/ltoken（缺失且有 stoken 时现换）；cookie_token 缺失留空，签到链路会自行刷新。
 * 只要 stuid 与 stoken/ltoken 之一齐备即可导入；只有 ltoken 时社区签到可用，游戏签到/抽卡链接需要 stoken，由调用方提示。
 */
public final class CookieLogin {

    private static final String TAG = "VenusCookie";
    /** BBS 登录态失效 */
    private static final int RETCODE_LOGIN_EXPIRED = -100;

    private CookieLogin() {
    }

    /** 解析成功并校验通过的凭证。 */
    public static final class Result {
        public final String stuid;
        public final String mid;
        public final String stoken;
        public final String ltoken;
        public final String cookieToken;
        /** 校验接口返回的 retcode（0 = 正常，其余为非登录态失效的风控/限流码）。 */
        public final int verifyRetcode;

        Result(String stuid, String mid, String stoken, String ltoken,
               String cookieToken, int verifyRetcode) {
            this.stuid = stuid;
            this.mid = mid;
            this.stoken = stoken;
            this.ltoken = ltoken;
            this.cookieToken = cookieToken;
            this.verifyRetcode = verifyRetcode;
        }

        /** 只有 ltoken，没有 stoken：只能做社区签到。 */
        public boolean onlyLtoken() {
            return (stoken == null || stoken.isEmpty());
        }
    }

    /**
    * 从 Cookie 键值表提取出的凭证字段（不含网络校验，便于单测与排查）；null 表示该键在 Cookie 中不存在。
    */
    static final class Fields {
        final String stuid;
        final String mid;
        final String stoken;
        final String ltoken;
        final String cookieToken;

        Fields(String stuid, String mid, String stoken, String ltoken, String cookieToken) {
            this.stuid = stuid;
            this.mid = mid;
            this.stoken = stoken;
            this.ltoken = ltoken;
            this.cookieToken = cookieToken;
        }
    }

    /**
    * 按优先级从键值表取凭证字段，纯内存操作：v2 字段优先；mid 缺失时退化为 stuid（旧账号体系两者同值）。
    */
    static Fields extract(Map<String, String> cookies) {
        String stuid = first(cookies, "ltuid_v2", "account_id_v2", "ltuid", "account_id");
        String stoken = first(cookies, "stoken_v2", "stoken");
        String ltoken = first(cookies, "ltoken_v2", "ltoken");
        String mid = first(cookies, "account_mid_v2", "ltmid_v2", "mid");
        String cookieToken = first(cookies, "cookie_token_v2", "cookie_token");
        if (mid == null) mid = stuid;
        return new Fields(stuid, mid, stoken, ltoken, cookieToken);
    }

    /**
    * 从粘贴的 Cookie 文本导入凭证（阻塞，需在后台线程调用）。rawCookie 允许带 "Cookie:" 前缀、换行与多余空格；
    * 字段缺失、换取 ltoken 失败或登录态失效时抛 RuntimeException，消息即可直接展示的文案。
    */
    public static Result importFromCookie(Context context, HeaderManager headerManager, String rawCookie) {
        Map<String, String> cookies = parse(rawCookie);
        Fields fields = extract(cookies);
        String stuid = fields.stuid;
        String mid = fields.mid;
        String stoken = fields.stoken;
        String ltoken = fields.ltoken;
        String cookieToken = fields.cookieToken;
        Logger.debug(TAG, "parsed: fields=" + cookies.size() + " stuid=" + (stuid != null)
                + " mid=" + (mid != null) + " stoken=" + (stoken != null)
                + " ltoken=" + (ltoken != null) + " cookieToken=" + (cookieToken != null));

        // 登录态的最小充分条件：账号 uid + 任一 token
        if (stuid == null || (stoken == null && ltoken == null)) {
            throw new RuntimeException(context.getString(R.string.login_cookie_invalid));
        }

        // 只有 stoken 时补齐 ltoken（社区签到与部分记录接口走 ltoken）
        if (stoken != null && ltoken == null) {
            ltoken = fetchLtoken(context, headerManager, stoken, mid, stuid);
        }

        int retcode = verify(headerManager, stoken, ltoken, mid, stuid);
        if (retcode == RETCODE_LOGIN_EXPIRED) {
            // 区分两种 -100：只有 ltoken 时是被接口拒收（BBS/签到接口要 stoken），
            // 有 stoken 时才是真的过期。实测 bbs-api / api-takumi 对 ltoken_v2 一律返回 -100。
            throw new RuntimeException(context.getString(stoken == null
                    ? R.string.login_cookie_ltoken_rejected
                    : R.string.login_cookie_expired));
        }
        return new Result(stuid, mid, stoken, ltoken, cookieToken, retcode);
    }

    /**
     * 解析 Cookie 文本为键值表：兼容 "Cookie: a=b; c=d" 前缀、跨行粘贴，值中含 '=' 时只按首个 '=' 切分。
     */
    static Map<String, String> parse(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null) return map;
        String text = raw.trim();
        if (text.regionMatches(true, 0, "cookie:", 0, 7)) text = text.substring(7);
        // 部分浏览器复制出来是 "a=b; c=d" 分多行，先拍平
        text = text.replace("\r", "").replace("\n", "");
        for (String piece : text.split(";")) {
            int eq = piece.indexOf('=');
            if (eq <= 0) continue;
            String key = piece.substring(0, eq).trim();
            String value = piece.substring(eq + 1).trim();
            // 同名键以首次出现为准（用户可能把多个域的 cookie 一起粘贴）
            if (!key.isEmpty() && !value.isEmpty()) map.putIfAbsent(key, value);
        }
        return map;
    }

    private static String first(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String value = map.get(key);
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    /**
     * 用 stoken 换 ltoken：Cookie 里的 mid 可能是新格式字符串，而该接口对旧数值 mid 同样有效，
     * 因此先用账号 mid 尝试，失败再用 stuid 重试一次；两者都失败则认为 stoken 已失效。
     */
    private static String fetchLtoken(Context context, HeaderManager headerManager,
                                      String stoken, String mid, String stuid) {
        String ltoken = requestLtoken(headerManager, stoken, mid);
        if (ltoken == null && !mid.equals(stuid)) {
            ltoken = requestLtoken(headerManager, stoken, stuid);
        }
        if (ltoken == null) {
            throw new RuntimeException(context.getString(R.string.login_cookie_stoken_expired));
        }
        return ltoken;
    }

    /** 单次 getLTokenBySToken 调用，失败返回 null（由上层决定是否重试/报错）。 */
    private static String requestLtoken(HeaderManager headerManager,
                                        String stoken, String mid) {
        Map<String, String> headers = headerManager.get_bbs_headers();
        headers.put("Cookie", "stoken=" + stoken + ";mid=" + mid);
        try {
            String response = tools.sendGetRequest(Constants.Urls.LTOKEN_URL, headers, null);
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            int retcode = retcodeOf(json);
            Logger.debug(TAG, "getLTokenBySToken retcode=" + retcode);
            if (retcode != 0) return null;
            JsonElement data = json.get("data");
            if (data == null || !data.isJsonObject()) return null;
            JsonElement token = data.getAsJsonObject().get("ltoken");
            if (token == null || token.isJsonNull()) return null;
            String value = token.getAsString();
            return value.isEmpty() ? null : value;
        } catch (RuntimeException e) {
            Logger.debug(TAG, "getLTokenBySToken failed: " + tools.describeError(e));
            return null;
        }
    }

    /**
     * 用导入的 Cookie 打一次社区任务列表接口，确认登录态真的可用。
     *
     * @return retcode（0 = 正常；-100 = 登录态失效；其余原样返回，由调用方按「可用但被限流/风控」处理）
     */
    private static int verify(HeaderManager headerManager, String stoken,
                              String ltoken, String mid, String stuid) {
        Map<String, String> headers = headerManager.get_bbs_headers();
        StringBuilder cookie = new StringBuilder();
        if (stoken != null && !stoken.isEmpty()) {
            cookie.append("stoken=").append(stoken).append(";mid=").append(mid);
        } else {
            cookie.append("ltoken=").append(ltoken);
        }
        cookie.append(";stuid=").append(stuid).append(";ltuid=").append(stuid).append(";");
        headers.put("Cookie", cookie.toString());
        try {
            String response = tools.sendGetRequest(Constants.Urls.BBS_TASK_URL, headers, null);
            int retcode = retcodeOf(JsonParser.parseString(response).getAsJsonObject());
            Logger.debug(TAG, "verify getUserMissionsState retcode=" + retcode);
            // 只有 -100 是登录态失效；1034（人机验证）/429 等说明凭证本身有效
            return retcode;
        } catch (RuntimeException e) {
            // 网络异常不阻断导入：凭证已解析完整，交给后续任务在真正请求时暴露
            Logger.debug(TAG, "verify failed: " + tools.describeError(e));
            return 0;
        }
    }

    /** 安全读取 retcode：缺失/JsonNull/类型不符返回哨兵值。 */
    private static int retcodeOf(JsonObject json) {
        if (json == null || !json.has("retcode") || json.get("retcode").isJsonNull())
            return Integer.MIN_VALUE;
        try {
            return json.get("retcode").getAsInt();
        } catch (RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }
}
