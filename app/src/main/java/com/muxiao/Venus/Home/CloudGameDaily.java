package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;

import android.content.Context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.HashMap;
import java.util.Map;

/**
 * 云游戏每日签到（云原神 / 云绝区零）。
 *
 * <p>与 {@link BBSGameDaily} 是两条完全独立的链路：云游戏走「钱包查询即签到」模型——
 * 每日首次调用钱包接口即发放免费时长，因此**只需云游戏 token**（{@code x-rpc-combo_token}），
 * 不需要米游社 cookie/stoken，也不需要 DS 签名与验证码。
 *
 * <p>token 按用户存储在 {@code user_{id}} 中（键见 {@link #tokenKey(String, boolean)}），
 * 由设置页「云游戏签到」抽屉录入。云绝区零仅国服有对应钱包接口，
 * 国际服只提供云原神。
 */
public class CloudGameDaily {

    /** 云原神（内部标识，同时是任务名后缀的取值来源）。 */
    public static final String GAME_GENSHIN = "云原神";
    /** 云绝区零（内部标识）。 */
    public static final String GAME_ZZZ = "云绝区零";

    private static final int RETCODE_OK = 0;
    /** token 失效 / 触发防沉迷 */
    private static final int RETCODE_TOKEN_EXPIRED = -100;
    private static final int RETCODE_RATE_LIMITED = 429;

    /**
     * 当接口未直接返回发放时长、且剩余时长低于该阈值时，重新请求一次比对增量。
     * 上游同口径：剩余时长充足（≥600 分钟）时接口不再单独回报本次发放量。
     */
    private static final int FREE_TIME_CONFIRM_THRESHOLD = 600;

    private static final int DELAY_MIN_MS = 2000;
    private static final int DELAY_RANGE_MS = 4000;
    private static final int CONFIRM_DELAY_MIN_MS = 3000;
    private static final int CONFIRM_DELAY_RANGE_MS = 3000;

    private final Context context;
    private final String userId;
    private final String gameName;
    private final String displayName;
    private final boolean isOversea;
    private final tools.StatusNotifier notifier;
    private final Notification notification;

    public CloudGameDaily(Context context, String userId, String gameName, tools.StatusNotifier notifier) {
        this.context = context;
        this.userId = userId;
        this.gameName = gameName;
        this.isOversea = MiHoYoBBSConstants.is_oversea(context);
        this.displayName = displayName(context, gameName);
        this.notifier = notifier;
        this.notification = new Notification(context);
    }

    /**
     * token 在用户数据中的存储键。
     * 国际服与国服的云原神 token 互不通用，故分别存放。
     */
    public static String tokenKey(String gameName, boolean isOversea) {
        if (GAME_ZZZ.equals(gameName)) return "cloudgame_token_zzz";
        return isOversea ? "cloudgame_token_genshin_os" : "cloudgame_token_genshin";
    }

    /** 云游戏在界面上的本地化名称（"云原神"/"云绝区零"）。 */
    public static String displayName(Context context, String gameName) {
        return context.getString(GAME_ZZZ.equals(gameName) ? R.string.cloud_game_zzz : R.string.cloud_game_genshin);
    }

    /**
     * token 形态是否可信。
     *
     * <p><b>不能只判空</b>：云游戏页在**未登录**时照样会请求钱包接口，前端把尚未取到的
     * 变量直接拼进请求头，于是出现字符串 {@code "undefined"} / {@code "null"} 这类占位值。
     * 若原样落库，会立刻弹「已获取 Token」（实际什么都没拿到），还会覆盖掉真实 token；
     * 后续签到拿这个假 token 请求得到 -100，表现为「刚配置就失效」。
     *
     * @return true 表示形态上像真 token：真 token 是约 150~200 字符的会话凭证，此处取保守下限
     */
    public static boolean isValidToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.length() < 32) return false;
        if (t.indexOf(' ') >= 0) return false;
        return !"undefined".equalsIgnoreCase(t) && !"null".equalsIgnoreCase(t);
    }

    /** 日志用脱敏预览：只留前 6 位，避免把完整凭据写进日志文件。 */
    public static String tokenPreview(String token) {
        if (token == null) return "null";
        String t = token.trim();
        return t.length() <= 6 ? t : t.substring(0, 6) + "…(" + t.length() + ")";
    }

    /** 本次任务在进度流与通知中的名称。 */
    public static String taskName(Context context, String gameName) {
        return context.getString(R.string.task_name_cloud_game_sign_in, displayName(context, gameName));
    }

    /**
     * 该游戏在当前服务器类型下是否可用：云绝区零无国际服钱包接口。
     */
    public static boolean isSupported(String gameName, boolean isOversea) {
        return !(isOversea && GAME_ZZZ.equals(gameName));
    }

    private String notifTitle() {
        return context.getString(R.string.notif_title_cloud_game, displayName);
    }

    private void report(String message) {
        notifier.notifyListeners(taskName(context, gameName) + " " + message);
    }

    /**
     * 执行单个云游戏的签到。
     *
     * <p>结果判定与上游一致：
     * <ul>
     *   <li>{@code send_freetime > 0} → 本次确实发放，直接报成功；</li>
     *   <li>{@code send_freetime == 0} 且剩余时长 &lt; 600 分钟 → 间隔数秒再查一次，
     *       剩余时长增长即视为发放成功，否则按「已签到/已达上限」处理；</li>
     *   <li>{@code send_freetime == 0} 且剩余时长充足 → 说明接口不再回报本次发放量，
     *       按成功处理（能查到余额即视为签到请求已被受理）。</li>
     * </ul>
     *
     * @throws Exception token 缺失、失效或接口异常时抛出，由 TaskExecutor 记为 ERROR
     */
    public void run() throws Exception {
        if (!isSupported(gameName, isOversea)) {
            throw new RuntimeException(context.getString(R.string.cloud_game_unsupported, displayName));
        }
        String tokenKey = tokenKey(gameName, isOversea);
        String token = tools.read(context, userId, tokenKey);
        if (!isValidToken(token)) {
            // 与「未填写」同一出口：历史的占位值（undefined/null）也归到这里，
            // 避免拿假 token 请求换来一个 -100 而被误报成「已失效」
            String message = context.getString(R.string.cloud_game_token_missing, displayName);
            notification.sendErrorNotification(notifTitle(), message);
            throw new RuntimeException(message);
        }

        report(context.getString(R.string.snack_loading));
        tools.randomDelay(DELAY_MIN_MS, DELAY_RANGE_MS);

        JsonObject payload = requestWallet(token);
        JsonObject freeTimeObject = child(payload, "free_time");
        int freeTime = JsonAccess.optInt(freeTimeObject, "free_time", -1);
        int sendFreeTime = JsonAccess.optInt(freeTimeObject, "send_freetime", 0);

        if (sendFreeTime > 0) {
            report(context.getString(R.string.cloud_game_sign_success, sendFreeTime));
        } else if (freeTime < FREE_TIME_CONFIRM_THRESHOLD) {
            // 接口未回报发放量且余额偏低：间隔数秒再查一次，用增量确认是否真的发放
            tools.randomDelay(CONFIRM_DELAY_MIN_MS, CONFIRM_DELAY_RANGE_MS);
            JsonObject second = requestWallet(token);
            JsonObject secondFreeTime = child(second, "free_time");
            int freeTimeAfter = JsonAccess.optInt(secondFreeTime, "free_time", freeTime);
            if (freeTimeAfter > freeTime) {
                report(context.getString(R.string.cloud_game_sign_success, freeTimeAfter - freeTime));
            } else {
                report(context.getString(R.string.cloud_game_already_signed));
            }
            freeTime = freeTimeAfter;
            payload = second;
        } else {
            // 余额充足：接口不再回报本次发放量，签到请求已受理即算完成
            report(context.getString(R.string.cloud_game_already_signed));
        }

        // 余额信息（剩余免费时长 / 畅玩卡状态 / 代币数量）作为附加进度上报
        if (freeTime >= 0) report(buildStatus(payload, freeTime));
    }

    /**
     * 调用钱包接口并完成 retcode 判定，返回 {@code data} 载荷。
     * token 失效直接抛错（不自动清空用户填写的 token，避免不可逆的数据丢失）。
     */
    private JsonObject requestWallet(String token) {
        String url;
        if (isOversea) {
            url = Constants.Urls.OS_CLOUD_GAME_GENSHIN_URL;
        } else if (GAME_ZZZ.equals(gameName)) {
            url = Constants.Urls.CLOUD_GAME_ZZZ_URL;
        } else {
            url = Constants.Urls.CLOUD_GAME_GENSHIN_URL;
        }
        String response = sendGetRequest(url, buildHeaders(token), null);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        int retcode = JsonAccess.retcode(data);
        if (retcode == RETCODE_OK) {
            JsonObject payload = JsonAccess.data(data);
            if (payload == null) throw new RuntimeException(context.getString(R.string.cloud_game_bad_response));
            return payload;
        }
        if (retcode == RETCODE_TOKEN_EXPIRED) {
            String message = context.getString(R.string.cloud_game_token_expired, displayName);
            notification.sendErrorNotification(notifTitle(), message);
            throw new RuntimeException(message);
        }
        if (retcode == RETCODE_RATE_LIMITED) {
            throw new RuntimeException(context.getString(R.string.game_rate_limited));
        }
        String message = context.getString(R.string.cloud_game_failed, displayName,
                JsonAccess.message(data, ""), retcode);
        notification.sendErrorNotification(notifTitle(), message);
        throw new RuntimeException(message);
    }

    /**
     * 构建钱包接口请求头。云游戏接口只认 {@code x-rpc-combo_token}，
     * 国际服另需 game_biz / channel_id / language 标识。
     */
    private Map<String, String> buildHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("x-rpc-combo_token", token);
        if (isOversea) {
            headers.put("User-Agent", "okhttp/4.10.0");
            headers.put("x-rpc-client_type", "3");
            headers.put("x-rpc-cg_game_biz", "hk4e_global");
            headers.put("x-rpc-channel_id", "1");
            headers.put("x-rpc-language", "zh-cn");
        } else {
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/99.0.4844.84 Safari/537.36");
            if (GAME_GENSHIN.equals(gameName)) headers.put("Referer", "https://app.mihoyo.com");
        }
        return headers;
    }

    /** 组装「剩余时长 · 畅玩卡 · 代币」摘要。 */
    private String buildStatus(JsonObject payload, int freeTime) {
        JsonObject playCard = child(payload, "play_card");
        JsonObject coin = child(payload, "coin");
        String playCardStatus = JsonAccess.optString(playCard, "short_msg",
                context.getString(R.string.cloud_game_play_card_unknown));
        int coinNum = JsonAccess.optInt(coin, "coin_num", 0);
        String coinName = context.getString(GAME_ZZZ.equals(gameName)
                ? R.string.cloud_game_zzz_coin : R.string.cloud_game_genshin_coin);
        return context.getString(R.string.cloud_game_status,
                formatDuration(freeTime), playCardStatus, coinName, coinNum);
    }

    /** 分钟数格式化为「X 小时 Y 分钟」，不足 1 小时只显示分钟。 */
    private String formatDuration(int minutes) {
        if (minutes < 60) return context.getString(R.string.cloud_game_duration_minutes, minutes);
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (rest == 0) return context.getString(R.string.cloud_game_duration_hours, hours);
        return context.getString(R.string.cloud_game_duration_hours_minutes, hours, rest);
    }

    /** 读取嵌套子对象：缺失或非对象时返回 null（本地小工具，避免为单一场景扩散 JsonAccess 接口）。 */
    private static JsonObject child(JsonObject obj, String member) {
        if (obj == null) return null;
        JsonElement element = obj.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}
