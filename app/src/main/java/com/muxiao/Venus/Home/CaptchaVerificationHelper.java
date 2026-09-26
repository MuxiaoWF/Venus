package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 验证码验证的公共逻辑，供 BBSDaily 和 BBSGameDaily 共用。
 */
public class CaptchaVerificationHelper {

    /** 等待人机验证结果的最长时间：与前台服务 WakeLock 的 10 分钟上限对齐。 */
    private static final long WAIT_TIMEOUT_MS = 10 * 60 * 1000L;

    private Map<String, String> geetCode = null;
    private volatile boolean verificationComplete = false;

    private final Context context;
    private final GeetestController gt3Controller;
    private final tools.StatusNotifier notifier;
    private final Notification notification;

    public CaptchaVerificationHelper(Context context, GeetestController gt3Controller, tools.StatusNotifier notifier, Notification notification) {
        this.context = context;
        this.gt3Controller = gt3Controller;
        this.notifier = notifier;
        this.notification = notification;
    }

    /** 返回验证得到的 geetest 结果；未完成或失败时可能为 null。 */
    public Map<String, String> getGeetCode() {
        return geetCode;
    }

    /**
     * 发起极验验证并注册回调。
     * 先重置 verificationComplete，验证成功时写入 geetCode 并唤醒等待线程；
     * 失败时将 geetCode 置为 null 同样唤醒；结果供 waitForCompletion() 阻塞消费。
     *
     * <p>等待验证码期间<b>不切换任务状态</b>：任务自 TaskExecutor 起就是 IN_PROGRESS，
     * 「等待人机验证」属于运行中的一部分，应始终保持 RUNNING 展示
     * （原实现切 WARNING，而列表将 WARNING 渲染为绿色「已签」，造成
     * 「遇到验证码即显示完成」的观感错误）。这里仅向日志通知等待/恢复事件。
     *
     * @param headers  含 Cookie 的请求头，供 API2 二次验证绑定会话
     * @param taskName 任务名，用于状态提示
     */
    public void performVerificationWithCallback(Map<String, String> headers, String taskName) {
        verificationComplete = false;
        notifier.notifyListeners(taskName + context.getString(R.string.geetest_waiting));
        Geetest.geetest(context, headers, new GeetestVerificationCallback() {
            @Override
            public void onVerificationSuccess(Map<String, String> code) {
                notification.dismissErrorNotification();
                gt3Controller.destroyButton();
                notifier.notifyListeners(taskName + context.getString(R.string.geetest_done));
                setGeetCodeAndComplete(code);
            }

            @Override
            public void onVerificationFailed(String error) {
                notifier.notifyListeners(context.getString(R.string.geetest_failed, error));
                // 上报验证码失败任务名（后台定时链路据此排除出同日重试，见 BackgroundGeetestController）
                gt3Controller.onCaptchaFailed(taskName);
                gt3Controller.destroyButton();
                setGeetCodeAndComplete(null);
            }
        }, gt3Controller);
    }

    /**
     * 阻塞等待验证完成，最多等待 {@link #WAIT_TIMEOUT_MS}。
     *
     * <p>三条退出路径：
     * <ul>
     *   <li>验证回调完成（成功/失败）→ {@code verificationComplete} 置位后唤醒返回；</li>
     *   <li>超时（用户长时间未完成验证或无 UI 可交互）→ 提示后返回，调用方据
     *       {@link #getGeetCode()} 为 null 走既有的验证失败分支，避免线程永久挂起；</li>
     *   <li>线程被中断（任务取消）→ 立即还原中断标志并返回。原实现在中断后仍留在
     *       {@code while} 循环中，因中断标志已置位会导致 {@code wait()} 立刻抛异常 → 忙等自旋。</li>
     * </ul>
     */
    public synchronized void waitForCompletion() {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (!verificationComplete) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                notifier.notifyListeners(context.getString(R.string.geetest_wait_timeout));
                return;
            }
            try {
                this.wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 写入验证结果（成功后非 null；失败传 null）并置 verificationComplete，唤醒 waitForCompletion。 */
    private synchronized void setGeetCodeAndComplete(Map<String, String> code) {
        if (code != null || geetCode == null) {
            geetCode = code;
        }
        verificationComplete = true;
        notifyAll();
    }
}
