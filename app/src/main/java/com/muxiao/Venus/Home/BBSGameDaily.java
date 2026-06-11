package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.Home.BackgroundGeetestController;
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

    private static final int MAX_RETRIES = 3;
    private static final int SIGN_DELAY_MIN_SEC = 2;
    private static final int SIGN_DELAY_RANGE_SEC = 7;
    private static final int RATE_LIMIT_COOLDOWN_MS = 10_000;
    private static final Random RANDOM = new Random();

    private void signDelay() throws InterruptedException {
        Thread.sleep((RANDOM.nextInt(SIGN_DELAY_RANGE_SEC) + SIGN_DELAY_MIN_SEC) * 1000L);
    }

    private final tools.StatusNotifier statusNotifier;
    private final Map<String, String> cookies = new HashMap<>();
    private final String gameName;
    private final String actId;
    private final List<Map<String, String>> accountList;
    private List<Map<String, Object>> checkinRewards = new ArrayList<>();
    private final Context context;
    private final String userId;
    private final GeetestController gt3Controller;
    private final Notification notification;
    private final HeaderManager headerManager;

    /**
     * @param gameName 游戏名
     */
    public BBSGameDaily(Context context, String userId, String gameName, tools.StatusNotifier statusNotifier, GeetestController gt3Controller) {
        this.gameName = gameName;
        this.context = context;
        this.userId = userId;
        this.statusNotifier = statusNotifier;
        this.gt3Controller = gt3Controller;
        this.headerManager = new HeaderManager(context);
        this.actId = MiHoYoBBSConstants.name_to_act_id(gameName);
        String gameId = MiHoYoBBSConstants.name_to_game_id(gameName);
        this.notification = new Notification(context);
        switch (gameName) {
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
        String cookieToken = tools.read(context, userId, "cookie_token");
        if (cookieToken == null)
            getCookieTokenByStoken();
        cookies.put("Cookie", cookies.get("Cookie") + ";cookie_token=" + tools.read(context, userId, "cookie_token") + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
        this.accountList = getAccountList(gameId);
        if (!accountList.isEmpty())
            this.checkinRewards = getCheckinRewards();
    }

    private Map<String, String> getGameLoginHeaders() {
        Map<String, String> gameLoginHeaders = headerManager.get_game_login_headers();
        gameLoginHeaders.putAll(cookies);
        return gameLoginHeaders;
    }

    /**
     * 更新cookieToken
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
     * 通过stoken获取cookieToken
     */
    public String getCookieTokenByStoken() {
        String stoken = tools.read(context, userId, "stoken");
        String stuid = tools.read(context, userId, "stuid");
        if ((stoken == null || stoken.isEmpty()) && (stuid == null || stuid.isEmpty())) {
            notification.sendErrorNotification("游戏签到出错", "Stoken和Suid为空，无法自动更新CookieToken");
            throw new RuntimeException("Stoken和Suid为空，无法自动更新CookieToken");
        }
        Map<String, String> gameLoginHeaders = getGameLoginHeaders();
        gameLoginHeaders.put("Cookie", "stuid=" + stuid + ";stoken=" + stoken + ";mid=" + tools.read(context, userId, "mid"));
        String response = sendGetRequest(Constants.Urls.COOKIE_TOKEN_STOKEN_URL, gameLoginHeaders, null);
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
     * @param gameId 游戏id
     * @return 账号列表List<Map < String, String>>, key为region, uid, nickname.
     */
    protected List<Map<String, String>> getAccountList(String gameId) {
        String gameName = MiHoYoBBSConstants.game_id_to_name(gameId);
        statusNotifier.notifyListeners("正在获取米哈游账号绑定的" + gameName + "账号列表...");
        Map<String, String> headers = getGameLoginHeaders();
        String response = sendGetRequest(Constants.Urls.ACCOUNT_LIST_URL, headers, Map.of("game_biz", gameId));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        // CookieToken失效，刷新CookieToken
        if (data.get("retcode").getAsInt() == -100) {
            String newCookie = updateCookieToken();
            if (newCookie == null) {
                statusNotifier.notifyListeners("CookieToken刷新失败，可以重新运行一下每日签到（再次点击运行按钮）");
                throw new RuntimeException("CookieToken刷新失败，可以重新运行一下每日签到（再次点击运行按钮）");
            }
            cookies.put("Cookie", "cookie_token=" + newCookie + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
            return getAccountList(gameId);
        } else if (data.get("retcode").getAsInt() != 0) { //获取账号列表失败
            statusNotifier.notifyListeners("获取" + gameName + "账号列表失败！");
            notification.sendErrorNotification("游戏签到", "获取" + gameName + "账号列表失败！");
            return new ArrayList<>();
        } else {   // 获取结果中的账号列表
            List<Map<String, String>> accountList = new ArrayList<>();
            for (var entry : data.getAsJsonObject("data").getAsJsonArray("list").asList()) {
                JsonObject account = entry.getAsJsonObject();
                Map<String, String> accountInfo = new HashMap<>();
                accountInfo.put("nickname", account.get("nickname").getAsString());
                accountInfo.put("game_uid", account.get("game_uid").getAsString());
                accountInfo.put("region", account.get("region").getAsString());
                accountInfo.put("game_biz", account.get("game_biz").getAsString());
                accountList.add(accountInfo);
            }
            tools.write(context, userId, gameId + "_user", new Gson().toJson(accountList));
            statusNotifier.notifyListeners("已获取到" + accountList.size() + "个" + gameName + "账号信息");
            return accountList;
        }
    }

    /**
     * 获取签到奖励列表
     *
     * @return 签到奖励列表List<Map < String, Object>>, key为name名称, cnt数量.
     */
    private List<Map<String, Object>> getCheckinRewards() {
        statusNotifier.notifyListeners("正在获取签到奖励列表...");
        for (int i = 0; i < MAX_RETRIES; i++) {
            String rewards_api = gameName.equals("绝区零") ? Constants.Urls.BBS_GAME_REWARDS_ZZZ_URL : Constants.Urls.BBS_GAME_REWARDS_URL;
            String response = sendGetRequest(rewards_api, getGameLoginHeaders(), Map.of("lang", "zh-cn", "act_id", actId));
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
                signDelay();
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
        Map<String, String> gameLoginHeaders = getGameLoginHeaders();
        String isSignApi = gameName.equals("绝区零") ? Constants.Urls.BBS_GAME_REWARDS_ZZZ_INFO_URL : Constants.Urls.BBS_GAME_REWARDS_INFO_URL;
        String response = sendGetRequest(isSignApi, gameLoginHeaders, Map.of("lang", "zh-cn", "act_id", actId, "region", region, "uid", uid));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        // CookieToken需要刷新
        if (data.get("retcode").getAsInt() == -100) {
            String newCookie = updateCookieToken();
            cookies.put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
            cookies.put("Cookie", "cookie_token=" + newCookie + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
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
        String signApi = gameName.equals("绝区零") ? Constants.Urls.BBS_GAME_REWARDS_ZZZ_SIGN_URL : Constants.Urls.BBS_GAME_REWARDS_SIGN_URL;
        String response = "";
        for (int i = 1; i <= MAX_RETRIES; i++) {
            Map<String, String> gameLoginHeader = getGameLoginHeaders();
            // 如果是之前进行了人机验证的，添加请求头
            if (geetCode != null)
                gameLoginHeader.putAll(geetCode);
            response = sendPostRequest(signApi, gameLoginHeader, Map.of("act_id", actId, "region", Objects.requireNonNull(account.get("region")), "uid", Objects.requireNonNull(account.get("game_uid"))));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 429) {
                Thread.sleep(RATE_LIMIT_COOLDOWN_MS);
                notification.sendErrorNotification("游戏签到出错", "429 Too Many Requests ，即将进入下次请求");
                statusNotifier.notifyListeners("429 Too Many Requests ，即将进入下一次请求");
                continue;
            }
            // 触发验证码
            if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 1) {
                Map<String, String> recordHeaders = headerManager.get_record_headers();
                recordHeaders.put("Cookie", "ltuid=" + tools.read(context, userId, "stuid") + ";ltoken=" + tools.read(context, userId, "stoken")
                        + ";ltoken_v2=" + tools.read(context, userId, "stoken") + ";ltuid_v2=" + tools.read(context, userId, "stuid")
                        + ";account_id=" + tools.read(context, userId, "stuid")
                        + ";account_id_v2=" + tools.read(context, userId, "stuid") + ";ltuid=" + tools.read(context, userId, "stuid")
                        + ";account_mid_v2=" + tools.read(context, userId, "mid") + ";cookie_token=" + tools.read(context, userId, "cookie_token")
                        + ";cookie_token_v2=" + tools.read(context, userId, "cookie_token") + ";mi18nLang=zh-cn;login_ticket=" + tools.read(context, userId, "login_ticket"));
                // 触发验证码验证
                notification.sendErrorNotification("游戏签到出错", "需要进行人机验证...");
                statusNotifier.notifyListeners("需要进行人机验证...");
                performVerificationWithCallback(recordHeaders);
                // 等待验证完成
                synchronized (this) {
                    try {
                        while (!verificationComplete) this.wait();
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
    public void run() {
        if (accountList.isEmpty()) {
            notification.sendErrorNotification("游戏签到出错", "没有绑定任何" + gameName + "账号");
            statusNotifier.notifyListeners(gameName + "签到失败：没有绑定任何账号，请先绑定");
            return;
        }
        String playerName = MiHoYoBBSConstants.game_to_role(gameName);
        statusNotifier.notifyListeners("开始执行" + gameName + "签到，共" + accountList.size() + "个账号");
        try {
            for (Map<String, String> account : accountList) {
                signDelay();
                Map<String, Object> isData = isSign(account.get("region"), account.get("game_uid"));
                if (isData.get("first_bind") != null && (Boolean) Objects.requireNonNull(isData.get("first_bind"))) {
                    notification.sendErrorNotification("游戏签到出错", playerName + account.get("nickname") + "是第一次绑定米游社，请先手动签到一次");
                    statusNotifier.notifyListeners(playerName + account.get("nickname") + "是第一次绑定，请先手动签到一次");
                    continue;
                }
                int signDays = ((Number) Objects.requireNonNull(isData.get("total_sign_day"))).intValue() - 1;
                if ((Boolean) Objects.requireNonNull(isData.get("is_sign"))) {
                    statusNotifier.notifyListeners(playerName + account.get("nickname") + "今天已经签到过了");
                    signDays += 1;
                } else {
                    signDelay();
                    String req = checkIn(account);
                    JsonObject data = JsonParser.parseString(req).getAsJsonObject();
                    if (data.get("retcode").getAsInt() != 429) {
                        if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 0) {
                            statusNotifier.notifyListeners(playerName + account.get("nickname") + "签到成功");
                            signDays += 2;
                        } else if (data.get("retcode").getAsInt() == -5003) {
                            statusNotifier.notifyListeners(playerName + account.get("nickname") + "今天已经签到过了");
                        } else {
                            String message = data.has("message") ? data.get("message").getAsString() : "未知错误";
                            int retcode = data.get("retcode").getAsInt();
                            boolean needCaptcha = !data.get("data").isJsonNull()
                                    && data.getAsJsonObject("data").has("success")
                                    && data.getAsJsonObject("data").get("success").getAsInt() != 0;
                            String reason = needCaptcha ? "触发验证码" : message + " (retcode=" + retcode + ")";
                            statusNotifier.notifyListeners(playerName + account.get("nickname") + "签到失败: " + reason);
                            notification.sendErrorNotification("游戏签到出错", playerName + account.get("nickname") + "签到失败: " + reason);
                            continue;
                        }
                    } else {
                        statusNotifier.notifyListeners(playerName + account.get("nickname") + "签到失败: 请求过于频繁(429)");
                        continue;
                    }
                }
                statusNotifier.notifyListeners(playerName + account.get("nickname") + "已连续签到" + signDays + "天");
                if (signDays > 0 && signDays <= checkinRewards.size()) {
                    statusNotifier.notifyListeners("今天获得的奖励是" + checkinRewards.get(signDays - 1).get("name") + "x" + checkinRewards.get(signDays - 1).get("cnt"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.toString());
        }
    }

    private Map<String, String> geetCode = null;
    private volatile boolean verificationComplete = false;

    private void performVerificationWithCallback(Map<String, String> headers) {
        verificationComplete = false;
        CaptchaVerifier.performVerification(gt3Controller, statusNotifier, gameName + "签到", headers,
                new CaptchaVerifier.VerificationCallback() {
                    @Override
                    public void onSuccess(Map<String, String> code) {
                        setGeetCodeAndComplete(code);
                    }

                    @Override
                    public void onFailure() {
                        setGeetCodeAndComplete(null);
                    }
                });
        // 后台控制器：前台验证是独立流程，回调不会触发。
        // 轮询从控制器读取验证结果。
        if (gt3Controller instanceof BackgroundGeetestController) {
            new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    Map<String, String> result = BackgroundGeetestController.getGeetestResult();
                    if (result != null) {
                        setGeetCodeAndComplete(result);
                        return;
                    }
                }
            }).start();
        }
    }

    private synchronized void setGeetCodeAndComplete(Map<String, String> code) {
        geetCode = code;
        verificationComplete = true;
        notifyAll();
    }
}