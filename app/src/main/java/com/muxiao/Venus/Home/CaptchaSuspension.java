package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.Notification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 轮末收口：把本轮所有「因需人机验证而挂起」的任务合并成一次验证请求。
 *
 * <p>职责很窄——取一次 API1 挑战、落库（{@link CaptchaStore}）、发一条可点击的通知。真正的验证在应用
 * 前台完成，结果由 {@link CaptchaStore#saveSolution} 落库；本类不等待、不阻塞。
 *
 * <p>为什么在轮末统一取挑战：多个任务并发撞 1034 时，各取一份 API1 会拿到互不匹配的 challenge，用户
 * 验完仍会 1034。合并成一次后，一份 challenge 服务本轮全部挂起项。
 */
public final class CaptchaSuspension {

    private CaptchaSuspension() {
    }

    /**
     * 尝试挂起一轮验证。
     *
     * @param context      已按应用语言包裹的 Context（任务名要本地化）
     * @param headers      任一挂起任务的请求头快照（含 Cookie，API1 用它绑定会话）
     * @param notifyUser   true = 发系统通知（后台/定时链路，用户不在界面）；false = 只落库 + 广播，
     *                     由调用方直接在首页挂出验证 UI（应用内运行，用户就在屏幕前）
     * @return {@code true} = 已落库并发出通知；{@code false} = 取挑战失败，挂起不成立，
     *         调用方应把相关任务按失败处理（否则会一直停在「待验证」）
     */
    public static boolean suspend(Context context, String userId, Collection<String> taskIds,
                                  Map<String, String> headers, Notification notification,
                                  boolean notifyUser) {
        if (taskIds == null || taskIds.isEmpty()) return false;

        // API1 域名按挂起账号的服别取：OS 账号的验证会话在 hoyolab 侧，打国服域名绑定不到会话
        boolean oversea = new com.muxiao.Venus.User.UserManager(context).isOversea(userId);
        String[] challenge = Geetest.requestChallenge(headers, oversea);
        if (challenge == null) {
            Logger.debug("VenusCaptcha", "suspend: API1 challenge request failed");
            if (notifyUser) {
                notification.sendErrorNotification(
                        context.getString(R.string.notif_task_failed),
                        context.getString(R.string.captcha_challenge_failed), true);
            }
            return false;
        }

        CaptchaStore.savePending(context, userId, taskIds, challenge[0], challenge[1], headers);

        if (AutoCaptchaSolver.autoSolve(context, userId, notification)) {
            return true;
        }

        // 应用此刻可能就在前台（后台服务在跑、用户正开着首页）：广播让它立刻弹验证，
        // 不必等下一次 onResume。不在前台时这条广播无人接收，通知仍是入口。
        // 必须带账号：验证请求按账号存放，前台收到广播后要先切到这个账号才读得到 pending
        Intent intent = new Intent(Constants.ACTION_CAPTCHA_REQUIRED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(Constants.EXTRA_CAPTCHA_USER, userId);
        context.sendBroadcast(intent);

        // 通知里列出全部待验证任务：一次验证后这些任务会一并重跑。
        // 必须带上 userId：验证请求按账号存放，点通知时要先切到该账号才读得到这份 challenge
        if (notifyUser) {
            List<String> names = new ArrayList<>(taskIds.size());
            for (String taskId : taskIds) names.add(TaskIds.displayName(context, taskId));
            notification.sendCaptchaNotification(
                    context.getString(R.string.captcha_suspend_title),
                    context.getString(R.string.captcha_suspend_content, TextUtils.join(", ", names)),
                    userId);
        }
        return true;
    }
}
