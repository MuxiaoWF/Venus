package com.muxiao.Venus.Home;

import android.content.Context;

import java.util.Map;

/**
 * 任务侧的验证凭证读取器。
 *
 * <p>「挂起-续跑」模型下，任务不再在自身线程上等待验证：命中 1034 时抛
 * {@link CaptchaRequiredException} 结束本轮，用户在应用前台完成极验后结果由 {@link CaptchaStore}
 * 落库，续跑轮的任务从这里取到凭证、直接注入请求头。
 *
 * <p>凭证在构造时读一次即可：续跑轮总是新实例，同一次运行内不会中途产生新凭证（真产生了说明上一轮
 * 已判失败，任务会重新挂起）。这样也避免了在每个请求的重试循环里反复反序列化。
 */
public class CaptchaVerificationHelper {

    /** 本次运行可用的验证凭证；未验证过或已过期时为 null。 */
    private final Map<String, String> geetCode;

    public CaptchaVerificationHelper(Context context, String userId) {
        this.geetCode = CaptchaStore.getSolution(context, userId);
    }

    /** 返回本次运行可用的 geetest 结果；调用方据此决定是否注入请求头。 */
    public Map<String, String> getGeetCode() {
        return geetCode;
    }
}
