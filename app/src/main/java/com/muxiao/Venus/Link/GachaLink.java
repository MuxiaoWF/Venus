package com.muxiao.Venus.Link;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 获取抽卡链接的类
 **/
public class GachaLink {
    private String stoken_and_mid;
    private final Context context;
    private final HeaderManager header_manager;
    private final String userId;

    public GachaLink(Context context, String userId) {
        this.context = context;
        this.header_manager = new HeaderManager(context);
        this.userId = userId;
    }

    /**
     * 获取原神抽卡记录url
     *
     * @return Map uid-Integer url-String 抽卡记录网址-防止多用户
     **/
    public Map<Integer, String> genshin() {
        String stoken = tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        if (stoken == null || mid == null)
            throw new RuntimeException("cookie有参数(stoken/mid)为null，请尝试重新获取");
        stoken_and_mid = "stoken=" + stoken + ";mid=" + mid + ";";
        String game_biz = MiHoYoBBSConstants.name_to_game_id("原神");
        int[] uids = getUID(game_biz);
        Map<Integer, String> url = new HashMap<>();
        for (int uid : uids)
            url.put(uid, Constants.Urls.YSH_GACHA_URL + getAuthKey(game_biz, uid) + "&game_biz=" + game_biz + "&gacha_type=301&page=1&size=5&end_id=0");
        return url;
    }

    /**
     * 获取uid
     **/
    private int[] getUID(String game_biz) {
        Map<String, String> user_game_roles_stoken_headers = header_manager.get_user_game_roles_stoken_headers();
        user_game_roles_stoken_headers.put("Cookie", stoken_and_mid);
        String content = tools.sendGetRequest(Constants.Urls.GAME_ROLES_URL, user_game_roles_stoken_headers, null);
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
        throw new RuntimeException("没成功获取游戏内角色信息");
    }

    /**
     * 获取authkey
     */
    private String getAuthKey(String game_biz, int uid) {
        Map<String, String> authkey_headers = header_manager.get_authkey_headers();
        authkey_headers.put("Cookie", stoken_and_mid);
        Map<String, Object> body = new HashMap<>();
        // 准备请求体
        if (game_biz.equals(MiHoYoBBSConstants.name_to_game_id("原神"))) {
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", MiHoYoBBSConstants.name_to_game_id("原神"));
            body.put("game_uid", uid);
            body.put("region", "cn_gf01");
        } else if (game_biz.equals(MiHoYoBBSConstants.name_to_game_id("绝区零"))) {
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", MiHoYoBBSConstants.name_to_game_id("绝区零"));
            body.put("game_uid", uid);
            body.put("region", "prod_gf_cn");
        }
        // 发送请求
        String content = tools.sendPostRequest(Constants.Urls.GEN_AUTH_KEY_URL, authkey_headers, body);
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
            String message = messageElement != null && !messageElement.isJsonNull() ? messageElement.getAsString() : "authkey未知错误";
            throw new RuntimeException(message);
        }
    }

    /**
     * 获取绝区零抽卡记录url
     *
     * @return Map uid-Integer url-String 抽卡记录网址-防止多用户
     **/
    public Map<Integer, String> zzz() {
        String stoken = tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        if (stoken == null || mid == null)
            throw new RuntimeException("cookie有参数为null，请尝试重新获取");
        stoken_and_mid = "stoken=" + stoken + ";mid=" + mid + ";";
        String game_biz = MiHoYoBBSConstants.name_to_game_id("绝区零");
        int[] uids = getUID(game_biz);
        Map<Integer, String> url = new HashMap<>();
        for (int uid : uids)
            url.put(uid, Constants.Urls.ZZZ_GACHA_URL + getAuthKey(game_biz, uid) + "&game_biz=" + game_biz + "&lang=zh-cn&region=prod_gf_cn&page=2&size=5&gacha_type=2001&real_gacha_type=2&end_id=0");
        return url;
    }
}