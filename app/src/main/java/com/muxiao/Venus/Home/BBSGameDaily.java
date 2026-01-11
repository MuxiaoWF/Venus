package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public class BBSGameDaily {
    private final tools.StatusNotifier statusNotifier;
    protected Map<String, String> cookies = new HashMap<>();
    private final String game_name;
    private final String act_id;
    private final List<Map<String, String>> account_list;
    private List<Map<String, Object>> checkin_rewards = new ArrayList<>();
    private final Context context;
    private final String userId;
    private final GeetestController gt3Controller;
    private final Notification notification;
    private final HeaderManager header_manager;

    /**
     * @param game_name 游戏名
     */
    public BBSGameDaily(Context context, String userId, String game_name, tools.StatusNotifier statusNotifier, GeetestController gt3Controller) {
        this.game_name = game_name;
        this.context = context;
        this.userId = userId;
        this.statusNotifier = statusNotifier;
        this.gt3Controller = gt3Controller;
        this.header_manager = new HeaderManager(context);
        this.act_id = MiHoYoBBSConstants.name_to_act_id(game_name);
        String game_id = MiHoYoBBSConstants.name_to_game_id(game_name);
        this.notification = new Notification(context);
        switch (game_name) {
            case "崩坏2":
                cookies.put("Referer", Constants.Urls.BBS_GAME_BH2_REFERER_URL);
                break;
            case "崩坏3":
                cookies.put("Referer", Constants.Urls.BBS_GAME_BH3_REFERER_URL);
                break;
            case "星铁":
                cookies.put("Origin", Constants.Urls.ORIGIN_REFERER_URL);
                break;
            case "原神":
                cookies.put("Origin", Constants.Urls.ORIGIN_REFERER_URL);
                cookies.put("x-rpc-signgame", "hk4e");
                break;
            case "绝区零":
                cookies.put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
                cookies.put("X-Rpc-Signgame", "zzz");
                break;
            case "未定事件簿":
                cookies.put("Referer", Constants.Urls.BBS_GAME_WD_REFERER_URL);
                break;
        }
        String cookie_token = tools.read(context, userId, "cookie_token");
        if (cookie_token == null)
            getCookieTokenByStoken();
        cookies.put("Cookie", cookies.get("Cookie") + ";cookie_token=" + tools.read(context, userId, "cookie_token") + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
        this.account_list = getAccountList(game_id);
        if (!account_list.isEmpty())
            this.checkin_rewards = getCheckinRewards();
    }

    private Map<String, String> get_game_login_headers() {
        Map<String, String> game_login_headers = header_manager.get_game_login_headers();
        game_login_headers.putAll(cookies);
        return game_login_headers;
    }

    /**
     * 更新cookie_token
     */
    private String updateCookieToken() {
        statusNotifier.notifyListeners("CookieToken失效，尝试刷新");
        notification.sendErrorNotification("游戏签到", "CookieToken失效，尝试刷新");
        String newToken = getCookieTokenByStoken();
        statusNotifier.notifyListeners("CookieToken刷新成功");
        tools.write(context, userId, "cookie_token", newToken);
        return newToken;
    }

    /**
     * 通过stoken获取cookie_token
     */
    public String getCookieTokenByStoken() {
        String stoken = tools.read(context, userId, "stoken");
        String stuid = tools.read(context, userId, "stuid");
        if (stoken == null || stoken.isEmpty() && stuid == null || stuid.isEmpty()) {
            notification.sendErrorNotification("游戏签到出错", "Stoken和Suid为空，无法自动更新CookieToken");
            throw new RuntimeException("Stoken和Suid为空，无法自动更新CookieToken");
        }
        Map<String, String> game_login_headers = get_game_login_headers();
        game_login_headers.put("Cookie", "stuid=" + stuid + ";stoken=" + stoken + ";mid=" + tools.read(context, userId, "mid"));
        String response = sendGetRequest(Constants.Urls.COOKIE_TOKEN_STOKEN_URL, game_login_headers, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        if (res.get("retcode").getAsInt() != 0) {
            notification.sendErrorNotification("游戏签到出错", "获取CookieToken失败,stoken已失效请重新抓取");
            throw new RuntimeException("获取CookieToken失败,stoken已失效请重新登录以抓取");
        }
        tools.write(context, userId, "cookie_token", res.get("data").getAsJsonObject().get("cookie_token").getAsString());
        return res.get("data").getAsJsonObject().get("cookie_token").getAsString();
    }

    /**
     * 获取米哈游账号绑定的指定游戏账号列表
     *
     * @param game_id 游戏id
     * @return 账号列表List<Map < String, String>>, key为region, uid, nickname.
     */
    protected List<Map<String, String>> getAccountList(String game_id) {
        String game_name = MiHoYoBBSConstants.game_id_to_name(game_id);
        statusNotifier.notifyListeners("正在获取米哈游账号绑定的" + game_name + "账号列表...");
        Map<String, String> headers = get_game_login_headers();
        String response = sendGetRequest(Constants.Urls.ACCOUNT_LIST_URL, headers, Map.of("game_biz", game_id));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        // CookieToken失效，刷新CookieToken
        if (data.get("retcode").getAsInt() == -100) {
            String new_Cookie = updateCookieToken();
            if (new_Cookie == null) {
                statusNotifier.notifyListeners("CookieToken刷新失败，可以重新运行一下每日签到（再次点击运行按钮）");
                throw new RuntimeException("CookieToken刷新失败，可以重新运行一下每日签到（再次点击运行按钮）");
            }
            cookies.put("Cookie", "cookie_token=" + new_Cookie + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
            return getAccountList(game_id);
        } else if (data.get("retcode").getAsInt() != 0) { //获取账号列表失败
            statusNotifier.notifyListeners("获取" + game_name + "账号列表失败！");
            notification.sendErrorNotification("游戏签到", "获取" + game_name + "账号列表失败！");
            return new ArrayList<>();
        } else {   // 获取结果中的账号列表
            List<Map<String, String>> account_list = new ArrayList<>();
            for (var entry : data.getAsJsonObject("data").getAsJsonArray("list").asList()) {
                JsonObject account = entry.getAsJsonObject();
                Map<String, String> accountInfo = new HashMap<>();
                accountInfo.put("nickname", account.get("nickname").getAsString());
                accountInfo.put("game_uid", account.get("game_uid").getAsString());
                accountInfo.put("region", account.get("region").getAsString());
                accountInfo.put("game_biz", account.get("game_biz").getAsString());
                account_list.add(accountInfo);
            }
            tools.write(context, userId, game_id + "_user", new Gson().toJson(account_list));
            statusNotifier.notifyListeners("已获取到" + account_list.size() + "个" + game_name + "账号信息");
            return account_list;
        }
    }

    /**
     * 获取签到奖励列表
     *
     * @return 签到奖励列表List<Map < String, Object>>, key为name名称, cnt数量.
     */
    private List<Map<String, Object>> getCheckinRewards() {
        statusNotifier.notifyListeners("正在获取签到奖励列表...");
        for (int i = 0; i < 3; i++) {
            String rewards_api = game_name.equals("绝区零") ? Constants.Urls.BBS_GAME_REWARDS_ZZZ_URL : Constants.Urls.BBS_GAME_REWARDS_URL;
            String response = sendGetRequest(rewards_api, get_game_login_headers(), Map.of("lang", "zh-cn", "act_id", act_id));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 0) {
                JsonArray awardsArray = data.getAsJsonObject("data").getAsJsonArray("awards");
                List<Map<String, Object>> rewards = new ArrayList<>();
                for (JsonElement award : awardsArray) {
                    JsonObject awardObject = award.getAsJsonObject();
                    Map<String, Object> rewardMap = new HashMap<>();
                    rewardMap.put("name", awardObject.get("name").getAsString());
                    rewardMap.put("cnt", awardObject.get("cnt").getAsString());
                    rewards.add(rewardMap);
                }
                return rewards;
            } else  // 没能成功获取
                statusNotifier.notifyListeners("获取签到奖励列表失败，重试次数: " + (i + 1));
            try {
                Thread.sleep(new Random().nextInt(8 - 2 + 1) + 2 * 1000);
            } catch (InterruptedException e) {
                throw new RuntimeException("thread异常" + e);
            }
        }
        statusNotifier.notifyListeners("重试次数达到上限，获取签到奖励列表失败");
        return new ArrayList<>();
    }

    /**
     * 获取账号签到信息
     *
     * @param region 游戏区
     * @param uid    游戏uid
     * @return 签到信息Map<String, Object>, key为is_sign是否已签到, remain_days剩余天数, sign_cnt已签到天数, first_bind是否首次绑定.
     */
    private Map<String, Object> isSign(String region, String uid) {
        Map<String, String> game_login_headers = get_game_login_headers();
        String is_sign_api = game_name.equals("绝区零") ? Constants.Urls.BBS_GAME_REWARDS_ZZZ_INFO_URL : Constants.Urls.BBS_GAME_REWARDS_INFO_URL;
        String response = sendGetRequest(is_sign_api, game_login_headers, Map.of("lang", "zh-cn", "act_id", act_id, "region", region, "uid", uid));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        // CookieToken需要刷新
        if (data.get("retcode").getAsInt() == -100) {
            String new_cookie = updateCookieToken();
            cookies.put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
            cookies.put("Cookie", "cookie_token=" + new_cookie + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
            return isSign(region, uid);
        } else if (data.get("retcode").getAsInt() != 0) { // 其他错误
            notification.sendErrorNotification("游戏签到出错", "获取账号签到信息失败！");
            throw new RuntimeException("BBS Cookie Errror" + "获取账号签到信息失败！" + response);
        }
        Map<String, Object> resultMap = new HashMap<>();
        JsonObject dataObject = data.getAsJsonObject("data");
        // 解析结果
        for (Map.Entry<String, JsonElement> entry : dataObject.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isNumber())
                    resultMap.put(entry.getKey(), value.getAsNumber());
                else if (value.getAsJsonPrimitive().isBoolean())
                    resultMap.put(entry.getKey(), value.getAsBoolean());
                else
                    resultMap.put(entry.getKey(), value.getAsString());
            } else if (value.isJsonObject())
                resultMap.put(entry.getKey(), value.getAsJsonObject().toString());
            else if (value.isJsonArray())
                resultMap.put(entry.getKey(), value.getAsJsonArray().toString());
            else
                resultMap.put(entry.getKey(), value.toString());
        }
        return resultMap;
    }

    /**
     * 签到
     *
     * @param account 账号信息Map<String, String>, key为nickname昵称, game_uid游戏uid, region游戏区.
     * @return 签到结果String
     */
    private String checkIn(Map<String, String> account) throws Exception {
        String sign_api = game_name.equals("绝区零") ? Constants.Urls.BBS_GAME_REWARDS_ZZZ_SIGN_URL : Constants.Urls.BBS_GAME_REWARDS_SIGN_URL;
        String response = "";
        for (int i = 1; i <= 3; i++) {
            Map<String, String> game_login_header = get_game_login_headers();
            // 如果是之前进行了人机验证的，添加请求头
            if (geetest_code != null)
                game_login_header.putAll(geetest_code);
            response = sendPostRequest(sign_api, game_login_header, Map.of("act_id", act_id, "region", Objects.requireNonNull(account.get("region")), "uid", Objects.requireNonNull(account.get("game_uid"))));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 429) {
                Thread.sleep(10000); // 429同ip请求次数过多，尝试sleep10s进行解决
                notification.sendErrorNotification("游戏签到出错", "429 Too Many Requests ，即将进入下次请求");
                statusNotifier.notifyListeners("429 Too Many Requests ，即将进入下一次请求");
                continue;
            }
            // 触发验证码
            if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 1) {
                Map<String, String> record_headers = header_manager.get_record_headers();
                record_headers.put("Cookie", "ltuid=" + tools.read(context, userId, "stuid") + ";ltoken=" + tools.read(context, userId, "stoken")
                        + ";ltoken_v2=" + tools.read(context, userId, "stoken") + ";ltuid_v2=" + tools.read(context, userId, "stuid")
                        + ";account_id=" + tools.read(context, userId, "stuid")
                        + ";account_id_v2=" + tools.read(context, userId, "stuid") + ";ltuid=" + tools.read(context, userId, "stuid")
                        + ";account_mid_v2=" + tools.read(context, userId, "mid") + ";cookie_token=" + tools.read(context, userId, "cookie_token")
                        + ";cookie_token_v2=" + tools.read(context, userId, "cookie_token") + ";mi18nLang=zh-cn;login_ticket=" + tools.read(context, userId, "login_ticket"));
                // 触发验证码验证
                notification.sendErrorNotification("游戏签到出错", "需要进行人机验证...");
                statusNotifier.notifyListeners("需要进行人机验证...");
                performVerificationWithCallback(record_headers);
                // 等待验证完成
                synchronized (this) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else
                break;
        }
        return response;
    }

    /**
     * 签到(最开始的)
     */
    public void signAccount() {
        if (account_list.isEmpty()) {
            notification.sendErrorNotification("游戏签到出错", "没有绑定任何" + game_name + "账号");
            statusNotifier.notifyListeners("签到失败，并没有绑定任何" + game_name + "账号，请先绑定");
            return;
        }
        String player_name = MiHoYoBBSConstants.game_to_role(game_name);
        statusNotifier.notifyListeners(game_name + ": ");
        try {
            for (Map<String, String> account : account_list) {
                Thread.sleep(new Random().nextInt(8 - 2 + 1) + 2 * 1000);
                Map<String, Object> isData = isSign(account.get("region"), account.get("game_uid"));
                if (isData.get("first_bind") != null && (Boolean) Objects.requireNonNull(isData.get("first_bind"))) {
                    notification.sendErrorNotification("游戏签到出错", player_name + account.get("nickname") + "是第一次绑定米游社，请先手动签到一次");
                    statusNotifier.notifyListeners(player_name + account.get("nickname") + "是第一次绑定米游社，请先手动签到一次");
                    continue;
                }
                int signDays = ((Number) Objects.requireNonNull(isData.get("total_sign_day"))).intValue() - 1;
                if ((Boolean) Objects.requireNonNull(isData.get("is_sign"))) {
                    statusNotifier.notifyListeners(player_name + account.get("nickname") + "今天已经签到过了~");
                    signDays += 1;
                } else {
                    Thread.sleep(new Random().nextInt(8 - 2 + 1) + 2 * 1000);
                    String req = checkIn(account);
                    JsonObject data = JsonParser.parseString(req).getAsJsonObject();
                    if (data.get("retcode").getAsInt() != 429) {
                        if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 0) {
                            statusNotifier.notifyListeners(player_name + account.get("nickname") + "签到成功~");
                            signDays += 2;
                        } else if (data.get("retcode").getAsInt() == -5003) {
                            statusNotifier.notifyListeners(player_name + account.get("nickname") + "今天已经签到过了~");
                        } else {
                            String s = "账号签到失败！\n" + req + "\n";
                            if (!data.get("data").isJsonNull() && data.getAsJsonObject("data").has("success") && data.getAsJsonObject("data").get("success").getAsInt() != 0)
                                s += "原因: 验证码\njson信息:" + req;
                            statusNotifier.notifyListeners(s);
                            notification.sendErrorNotification("游戏签到出错", s);
                            continue;
                        }
                    } else {
                        statusNotifier.notifyListeners(account.get("nickname") + "，本次签到失败");
                        continue;
                    }
                }
                statusNotifier.notifyListeners(account.get("nickname") + "已连续签到" + signDays + "天");
                statusNotifier.notifyListeners("今天获得的奖励是" + checkin_rewards.get(signDays - 1).get("name") + "x" + checkin_rewards.get(signDays - 1).get("cnt"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e.toString());
        }
    }

    private Map<String, String> geetest_code = null;

    private void performVerificationWithCallback(Map<String, String> headers) {
        Geetest.geetest(headers, new GeetestVerificationCallback() {
            @Override
            public void onVerificationSuccess(Map<String, String> code) {
                statusNotifier.notifyListeners("人机验证成功，继续执行签到...");
                geetest_code = code;
                gt3Controller.destroyButton(); // 销毁按钮
                synchronized (BBSGameDaily.this) {
                    BBSGameDaily.this.notifyAll();
                }
            }

            @Override
            public void onVerificationFailed(String error) {
                statusNotifier.notifyListeners("人机验证失败: " + error);
                statusNotifier.notifyListeners("验证失败");
                geetest_code = null;
                gt3Controller.destroyButton(); // 销毁按钮
                synchronized (BBSGameDaily.this) {
                    BBSGameDaily.this.notifyAll();
                }
                throw new RuntimeException("人机验证失败");
            }
        }, gt3Controller);
    }
}