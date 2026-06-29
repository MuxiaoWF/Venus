package com.muxiao.Venus.Link;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽卡链接获取：通过 stoken 获取 authkey，拼接抽卡记录URL。
 * 支持原神和绝区零，自动区分国服/国际服域名和region参数。
 */
public class GachaLink {
    private String stoken_and_mid;
    private final Context context;
    private final HeaderManager header_manager;
    private final String userId;
    private final boolean isOversea;

    public GachaLink(Context context, String userId) {
        this.context = context;
        this.header_manager = new HeaderManager(context);
        this.userId = userId;
        this.isOversea = MiHoYoBBSConstants.is_oversea(context);
    }

    /**
     * 获取原神抽卡记录url
     **/
    private void initToken() {
        String stoken = tools.read(context, userId, "stoken");
        String ltoken = tools.read(context, userId, "ltoken");
        String mid = tools.read(context, userId, "mid");
        String token = stoken != null ? stoken : ltoken;
        if (token == null)
            throw new RuntimeException(context.getString(R.string.gacha_cookie_null));
        String tokenKey = stoken != null ? "stoken" : "ltoken";
        stoken_and_mid = tokenKey + "=" + token + (mid != null ? ";mid=" + mid : "") + ";";
    }

    public Map<Integer, String> genshin() {
        initToken();
        String game_biz = MiHoYoBBSConstants.name_to_game_id("原神", isOversea);
        int[] uids = getUID(game_biz);
        Map<Integer, String> url = new HashMap<>();
        String gachaBaseUrl = isOversea ? Constants.Urls.OS_YSH_GACHA_URL : Constants.Urls.YSH_GACHA_URL;
        for (int uid : uids)
            url.put(uid, gachaBaseUrl + getAuthKey(game_biz, uid) + "&game_biz=" + game_biz + "&gacha_type=301&page=1&size=5&end_id=0");
        return url;
    }

    /**
     * 获取uid
     **/
    private int[] getUID(String game_biz) {
        Map<String, String> user_game_roles_stoken_headers = header_manager.get_user_game_roles_stoken_headers();
        user_game_roles_stoken_headers.put("Cookie", stoken_and_mid);
        String gameRolesUrl = isOversea ? Constants.Urls.OS_GAME_ROLES_URL : Constants.Urls.GAME_ROLES_URL;
        String content = tools.sendGetRequest(gameRolesUrl, user_game_roles_stoken_headers, null);
        JsonElement dataElement = JsonParser.parseString(content).getAsJsonObject().get("data");
        if (dataElement != null && !dataElement.isJsonNull() && dataElement.isJsonObject()) {
            JsonElement listElement = dataElement.getAsJsonObject().get("list");
            if (listElement != null && !listElement.isJsonNull() && listElement.isJsonArray()) {
                ArrayList<Integer> uids = new ArrayList<>();
                JsonArray list = listElement.getAsJsonArray();
                for (JsonElement element : list) {
                    if (element.isJsonObject()) {
                        JsonObject object = element.getAsJsonObject();
                        if (object.get("game_biz").getAsString().equals(game_biz))
                            uids.add(object.get("game_uid").getAsInt());
                    }
                }
                int[] result = new int[uids.size()];
                for (int i = 0; i < uids.size(); i++)
                    result[i] = uids.get(i);
                return result;
            }
        }
        throw new RuntimeException(context.getString(R.string.gacha_get_roles_failed));
    }

    /**
     * 获取authkey
     */
    private String getAuthKey(String game_biz, int uid) {
        Map<String, String> authkey_headers = header_manager.get_authkey_headers();
        authkey_headers.put("Cookie", stoken_and_mid);
        Map<String, Object> body = new HashMap<>();
        // 准备请求体
        String genshinBiz = MiHoYoBBSConstants.name_to_game_id("原神", isOversea);
        String zzzBiz = MiHoYoBBSConstants.name_to_game_id("绝区零", isOversea);

        if (game_biz.equals(genshinBiz)) {
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", genshinBiz);
            body.put("game_uid", uid);
            body.put("region", isOversea ? "os_usa" : "cn_gf01");
        } else if (game_biz.equals(zzzBiz)) {
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", zzzBiz);
            body.put("game_uid", uid);
            body.put("region", isOversea ? "prod_gf_us" : "prod_gf_cn");
        }
        // 发送请求
        String authKeyUrl = isOversea ? Constants.Urls.OS_GEN_AUTH_KEY_URL : Constants.Urls.GEN_AUTH_KEY_URL;
        String content = tools.sendPostRequest(authKeyUrl, authkey_headers, body);
        // 解析 JSON 响应
        JsonObject jsonResponse = JsonParser.parseString(content).getAsJsonObject();
        // 检查 data 字段是否存在,不存在就使用message提示错误
        JsonElement dataElement = jsonResponse.get("data");
        if (dataElement != null && !dataElement.isJsonNull() && dataElement.isJsonObject()) {
            JsonObject data = dataElement.getAsJsonObject();
            String authkey = data.get("authkey").getAsString().replace("/", "%2F");
            return authkey.replace("+", "%2B");
        } else {
            JsonElement messageElement = jsonResponse.get("message");
            String message = messageElement != null && !messageElement.isJsonNull() ? messageElement.getAsString() : context.getString(R.string.link_authkey_error);
            throw new RuntimeException(message);
        }
    }

    /**
     * 获取绝区零抽卡记录url
     *
     * @return Map uid-Integer url-String 抽卡记录网址-防止多用户
     **/
    public Map<Integer, String> zzz() {
        initToken();
        String game_biz = MiHoYoBBSConstants.name_to_game_id("绝区零", isOversea);
        int[] uids = getUID(game_biz);
        Map<Integer, String> url = new HashMap<>();
        String zzzGachaBaseUrl = isOversea ? Constants.Urls.OS_ZZZ_GACHA_URL : Constants.Urls.ZZZ_GACHA_URL;
        for (int uid : uids)
            url.put(uid, zzzGachaBaseUrl + getAuthKey(game_biz, uid) + "&game_biz=" + game_biz + "&lang=" + (isOversea ? "en-us" : "zh-cn") + "&region=" + (isOversea ? "prod_gf_us" : "prod_gf_cn") + "&page=1&size=5&gacha_type=2001&real_gacha_type=2&end_id=0");
        return url;
    }
}