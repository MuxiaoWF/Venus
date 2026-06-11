package com.muxiao.Venus.Home;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.google.gson.Gson;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 后台人机验证控制器。
 * 遇到验证时发送通知引导用户进入 MainActivity 完成验证，
 * 验证结果通过广播回传，唤醒等待线程继续执行任务。
 */
public class BackgroundGeetestController implements GeetestController {

    private static final String TAG = "BackgroundGeetestCtrl";

    private final Context context;
    private final tools.StatusNotifier notifier;

    private GT3ConfigBean pendingConfigBean;
    private GT3GeetestUtils gt3Utils;
    private BroadcastReceiver resultReceiver;
    private volatile Map<String, String> verificationResult;
    private volatile boolean verificationDone = false;

    public BackgroundGeetestController(Context context, tools.StatusNotifier notifier) {
        this.context = context;
        this.notifier = notifier;
    }

    @Override
    public void createUtils() {
        gt3Utils = new GT3GeetestUtils(context);
    }

    @Override
    public void createButton(GT3ConfigBean gt3ConfigBean) {
        // 保存配置，发通知让用户进入应用完成验证
        this.pendingConfigBean = gt3ConfigBean;
        notifier.notifyListeners("需要进行人机验证，请点击通知进入应用完成验证");

        // 注册广播接收器等待验证结果
        registerResultReceiver();

        // 发送通知引导用户进入 MainActivity
        sendCaptchaNotification();
    }

    @Override
    public GT3GeetestUtils getGeetestUtils() {
        return gt3Utils;
    }

    @Override
    public void destroyButton() {
        cancelNotification();
        unregisterResultReceiver();
        pendingConfigBean = null;
    }

    @Override
    public void destroyUtils() {
        if (gt3Utils != null) {
            gt3Utils.destroyUtils();
            gt3Utils = null;
        }
    }

    @Override
    public void updateTaskStatusWaring(String taskName) {
        notifier.notifyListeners(taskName + "：等待人机验证");
    }

    @Override
    public void updateTaskStatusInProgress(String taskName) {
        notifier.notifyListeners(taskName + "：验证完成，继续执行");
    }

    /**
     * 获取验证结果（阻塞调用，等待用户完成验证或超时）
     *
     * @return 验证结果，null 表示验证失败或超时
     */
    public Map<String, String> waitForResult(long timeoutMs) {
        synchronized (this) {
            if (verificationDone) return verificationResult;
            try {
                this.wait(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return verificationResult;
        }
    }

    /**
     * 由 MainActivity 调用，通知验证成功
     */
    public static void notifyVerificationSuccess(Context context, Map<String, String> code) {
        Intent intent = new Intent(Constants.ACTION_CAPTCHA_RESULT);
        intent.putExtra(Constants.EXTRA_CAPTCHA_SUCCESS, true);
        intent.putExtra(Constants.EXTRA_CAPTCHA_CODE_JSON, new Gson().toJson(code));
        context.sendBroadcast(intent);
    }

    /**
     * 由 MainActivity 调用，通知验证失败
     */
    public static void notifyVerificationFailure(Context context, String error) {
        Intent intent = new Intent(Constants.ACTION_CAPTCHA_RESULT);
        intent.putExtra(Constants.EXTRA_CAPTCHA_SUCCESS, false);
        intent.putExtra(Constants.EXTRA_CAPTCHA_ERROR, error);
        context.sendBroadcast(intent);
    }

    private void registerResultReceiver() {
        if (resultReceiver != null) return;
        resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!Constants.ACTION_CAPTCHA_RESULT.equals(intent.getAction())) return;

                boolean success = intent.getBooleanExtra(Constants.EXTRA_CAPTCHA_SUCCESS, false);
                synchronized (BackgroundGeetestController.this) {
                    if (success) {
                        String json = intent.getStringExtra(Constants.EXTRA_CAPTCHA_CODE_JSON);
                        verificationResult = new Gson().fromJson(json,
                                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
                    } else {
                        verificationResult = null;
                        String error = intent.getStringExtra(Constants.EXTRA_CAPTCHA_ERROR);
                        Log.w(TAG, "验证失败: " + error);
                    }
                    verificationDone = true;
                    BackgroundGeetestController.this.notifyAll();
                }
                cancelNotification();
            }
        };
        IntentFilter filter = new IntentFilter(Constants.ACTION_CAPTCHA_RESULT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(resultReceiver, filter);
        }
    }

    private void unregisterResultReceiver() {
        if (resultReceiver != null) {
            try {
                context.unregisterReceiver(resultReceiver);
            } catch (Exception ignored) {}
            resultReceiver = null;
        }
    }

    private void sendCaptchaNotification() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction("ACTION_HANDLE_CAPTCHA");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "venus_error_channel")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("需要人机验证")
                .setContentText("点击此处进入应用完成验证，任务将继续执行")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(Constants.NOTIFICATION_ID_CAPTCHA, builder.build());
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(Constants.NOTIFICATION_ID_CAPTCHA);
    }
}
