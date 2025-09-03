package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.fixed.K2;
import static com.muxiao.Venus.common.fixed.getDS;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muxiao.Venus.common.fixed;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 获取抽卡链接的类
 *
 **/
public class GachaLink {
    private String StokenAndMid;
    private final Context context;
    private final com.muxiao.Venus.common.fixed fixed;
    private final String userId;

    public GachaLink(Context  context, String userId) {
        this.context = context;
        this.fixed = new fixed(context, userId);
        this.userId = userId;
    }


    /**
     * 获取原神抽卡记录url
     * @return Map uid-Integer url-String 抽卡记录网址-防止多用户
     **/
    public Map<Integer, String> genshin() {
        String stoken = tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        if (stoken == null || mid == null) {
            throw new RuntimeException("cookie有参数为null，请尝试重新获取");
        }
        StokenAndMid = "stoken=" + stoken + ";mid=" + mid + ";";
        try {
            int[] uids = getUID(com.muxiao.Venus.common.fixed.name_to_game_id("原神"));
            Map<Integer, String> url = new HashMap<>();
            for (int uid : uids) {
                String authKey = getAuthKey(com.muxiao.Venus.common.fixed.name_to_game_id("原神"), uid);
                url.put(uid, "https://public-operation-hk4e.mihoyo.com/gacha_info/api/getGachaLog?win_mode=fullscreen&authkey_ver=1&sign_type=2&auth_appid=webview_gacha&init_type=301&lang=zh-cn&region=cn_gf01&authkey="
                        + authKey + "&game_biz=" + com.muxiao.Venus.common.fixed.name_to_game_id("原神") + "&gacha_type=301&page=1&size=5&end_id=0");
            }
            return url;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * 获取uid
     **/
    private int[] getUID(String game_biz) {
        fixed.getFp();
        Map<String, String> user_game_roles_stoken_headers = new HashMap<>(fixed.user_game_roles_stoken_headers);
        user_game_roles_stoken_headers.put("DS", getDS(K2));
        user_game_roles_stoken_headers.put("Cookie", StokenAndMid);
        String content = tools.sendGetRequest("https://api-takumi.miyoushe.com/binding/api/getUserGameRolesByStoken", user_game_roles_stoken_headers, null);
        Gson gson = new Gson();
        JsonObject jsonResponse = gson.fromJson(content, JsonObject.class);
        // 检查 data 字段是否存在且不为 JsonNull
        JsonElement dataElement = jsonResponse.get("data");
        if (dataElement != null && !dataElement.isJsonNull() && dataElement.isJsonObject()) {
            JsonObject data = dataElement.getAsJsonObject();
            // 检查 list 字段是否存在且不为 JsonNull
            JsonElement listElement = data.get("list");
            if (listElement != null && !listElement.isJsonNull() && listElement.isJsonArray()) {
                ArrayList<Integer> uids = new ArrayList<>();
                JsonArray list = listElement.getAsJsonArray();
                for (JsonElement element : list) {
                    if (element.isJsonObject()) {
                        JsonObject object = element.getAsJsonObject();
                        if (object.get("game_biz").getAsString().equals(game_biz)) {
                            uids.add(object.get("game_uid").getAsInt());
                        }
                    }
                }
                int[] result = new int[uids.size()];
                for (int i = 0; i < uids.size(); i++) {
                    result[i] = uids.get(i);
                }
                return result;
            }
        }
        throw new RuntimeException("没成功获取游戏内角色信息");
    }

    /**
     * 获取authkey
     */
    private String getAuthKey(String game_biz, int uid) {
        Map<String, String> authkey_headers = new HashMap<>(com.muxiao.Venus.common.fixed.authkey_headers);
        authkey_headers.put("Cookie", StokenAndMid);
        Map<String, Object> body = new HashMap<>();
        // 准备请求体
        if (game_biz.equals(com.muxiao.Venus.common.fixed.name_to_game_id("原神"))) {
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", com.muxiao.Venus.common.fixed.name_to_game_id("原神"));
            body.put("game_uid", uid);
            body.put("region", "cn_gf01");
        } else if (game_biz.equals(com.muxiao.Venus.common.fixed.name_to_game_id("绝区零"))) {
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", com.muxiao.Venus.common.fixed.name_to_game_id("绝区零"));
            body.put("game_uid", uid);
            body.put("region", "prod_gf_cn");
        }
        // 发送请求
        String content = tools.sendPostRequest("https://api-takumi.mihoyo.com/binding/api/genAuthKey", authkey_headers, body);
        // 解析 JSON 响应
        Gson gson = new Gson();
        JsonObject jsonResponse = gson.fromJson(content, JsonObject.class);
        // 检查 data 字段是否存在且不为 JsonNull
        JsonElement dataElement = jsonResponse.get("data");
        if (dataElement != null && !dataElement.isJsonNull() && dataElement.isJsonObject()) {
            JsonObject data = dataElement.getAsJsonObject();
            String authkey = data.get("authkey").getAsString().replace("/", "%2F");
            return authkey.replace("+", "%2B");
        } else {
            // 检查 message 字段是否存在且不为 JsonNull
            JsonElement messageElement = jsonResponse.get("message");
            String message = messageElement != null && !messageElement.isJsonNull() ? messageElement.getAsString() : "authkey未知错误";
            throw new RuntimeException(message);
        }
    }

    /**
     * 获取绝区零抽卡记录url
     * @return Map uid-Integer url-String 抽卡记录网址-防止多用户
     **/
    public Map<Integer, String> zzz() {
        String stoken =tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        if (stoken == null || mid == null) {
            throw new RuntimeException("cookie有参数为null，请尝试重新获取");
        }
        StokenAndMid = "stoken=" + stoken + ";mid=" + mid + ";";
        try {
            int[] uids = getUID(com.muxiao.Venus.common.fixed.name_to_game_id("绝区零"));
            Map<Integer, String> url = new HashMap<>();
            for (int uid : uids) {
                String authKey = getAuthKey(com.muxiao.Venus.common.fixed.name_to_game_id("绝区零"), uid);
                url.put(uid, "https://public-operation-nap.mihoyo.com/common/gacha_record/api/getGachaLog?authkey_ver=1&sign_type=2&auth_appid=webview_gacha&win_mode=fullscreen&init_log_gacha_type=2001&init_log_gacha_base_type=2&ui_layout=&button_mode=default&plat_type=3&authkey="
                        + authKey + "&game_biz=" + com.muxiao.Venus.common.fixed.name_to_game_id("绝区零") + "&lang=zh-cn&region=prod_gf_cn&page=2&size=5&gacha_type=2001&real_gacha_type=2&end_id=0");
            }
            return url;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}