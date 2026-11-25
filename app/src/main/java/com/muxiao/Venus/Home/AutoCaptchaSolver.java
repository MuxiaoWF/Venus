package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.data.ConfigRepository;
import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 极验自动打码调度：挂起后尝试全程本地求解——协议走 {@link GeetestV3Solver}，答案由
 * {@link SolverInference} 识别，validate 经 {@link Geetest#verifySecondPass} 完成后落库续跑。
 * 失败返回 false，回退原有验证路径。
 */
public final class AutoCaptchaSolver {

    /** 单次挂起的最大求解尝试数（轨迹风控会导致「正确但失败」，需重试）。 */
    private static final int MAX_ATTEMPTS = 2;

    private AutoCaptchaSolver() {
    }

    /** 自动打码总开关（设置页「后台运行」抽屉内）。 */
    public static boolean isEnabled(Context context) {
        return "true".equals(new ConfigRepository(context)
                .get(Constants.Prefs.AUTO_CAPTCHA_ENABLED, "false"));
    }

    public static void setEnabled(Context context, boolean enabled) {
        new ConfigRepository(context).putString(Constants.Prefs.AUTO_CAPTCHA_ENABLED,
            Boolean.toString(enabled));
    }

    /** 识别组件（native 库 + 模型）是否已下载就位。 */
    public static boolean assetsReady(Context context) {
        return SolverInference.isAvailable(context);
    }

    /**
     * 尝试自动求解（后台线程调用）。成功时已落 Solution、排续跑；失败时 Pending 原样保留。
     * @return true = 已接管；false = 回退人工
     */
    public static boolean autoSolve(Context context, String userId, Notification notification) {
        if (!isEnabled(context)) return false;
        CaptchaStore.Pending pending = CaptchaStore.getPending(context, userId);
        if (pending == null) return false;
        if (!assetsReady(context)) {
            Logger.debug(TAG_LOG, "auto-solve skipped: assets not ready for " + userId);
            return false;
        }
        Logger.debug(TAG_LOG, "auto-solve start: " + userId + " tasks=" + pending.taskIds().size());

        boolean oversea = new UserManager(context).isOversea(userId);
        GeetestV3Solver.AnswerProvider provider = SolverInference.provider(context);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GeetestV3Solver.Result result = GeetestV3Solver.solve(
                    pending.gt, pending.challenge, provider, 4000L);
            if (!result.success) {
                Logger.debug(TAG_LOG, "auto-solve attempt " + attempt + " failed: " + result.error);
                continue;
            }
            Map<String, String> code = Geetest.verifySecondPass(context, pending.headers,
                    pending.challenge, result.validate, oversea);
            if (code == null) {
                Logger.debug(TAG_LOG, "auto-solve attempt " + attempt + ": API2 rejected validate");
                continue;
            }
            // 与人工验证成功后的收口完全同口径：落 Solution、清 Pending、撤通知、排续跑
            CaptchaStore.saveSolution(context, userId, code);
            CaptchaStore.clearPending(context, userId);
            if (notification != null) notification.dismissCaptchaNotification();
            tools.writeLog(context, "captcha auto-solved: " + userId
                    + " (picType=" + result.picType + ", attempt=" + attempt + ")");
            DailyTaskScheduler.enqueueCaptchaResume(context, userId, pending.taskIds());
            return true;
        }
        tools.writeLog(context, "captcha auto-solve failed after " + MAX_ATTEMPTS
                + " attempts (" + userId + "), fall back to manual");
        return false;
    }

    private static final String TAG_LOG = "VenusCaptcha";
}
