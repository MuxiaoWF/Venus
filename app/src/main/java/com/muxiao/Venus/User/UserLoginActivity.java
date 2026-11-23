package com.muxiao.Venus.User;

import com.muxiao.Venus.common.BaseActivity;
import dagger.hilt.android.AndroidEntryPoint;


import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3ErrorBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.GT3Listener;
import com.muxiao.Venus.Home.JsonAccess;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.DeviceUtils;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 国服用户登录：扫码（gameToken → stoken → ltoken）与账密（RSA 加密 → loginByPassword 直取 stoken+mid → ltoken），
 * 两条链路写入相同的凭证槽位（stoken/mid/stuid/ltoken），下游无需区分来源。支持重新登录模式（先清旧数据）。
 */
@AndroidEntryPoint
public class UserLoginActivity extends BaseActivity {
    /** 登录方式：扫码 */
    private static final int MODE_QRCODE = 0;
    /** 登录方式：账号密码 */
    private static final int MODE_PASSWORD = 1;
    /** 登录方式：浏览器 Cookie 导入（仅国服，绕开极验） */
    private static final int MODE_COOKIE = 2;
    /** 登录方式：短信验证码（仅国服，走 passport ma-cn-verifier / ma-cn-passport） */
    private static final int MODE_SMS = 3;

    /** 单张二维码最多轮询次数（约 2 分钟，间隔见 {@link #QR_POLL_INTERVAL_MS}） */
    private static final int MAX_QR_POLL_COUNT = 120;
    /** 二维码过期后自动换码的最大次数（新版 passport 用 -106 表达过期） */
    private static final int MAX_QR_REFRESH_COUNT = 3;
    /** 新版扫码轮询间隔，与参考实现一致取 1s */
    private static final long QR_POLL_INTERVAL_MS = 1000L;
    /** 二维码已过期（passport queryQRLoginStatus） */
    private static final int RETCODE_QR_EXPIRED = -106;

    /** Compose 版页面宿主：输入框 / 二维码 / 状态日志都在这里。 */
    private com.muxiao.Venus.common.ui.compose.UserLoginScreenView pane;
    private UserManager user_manager;
    /** 登录页输入框里的名字：仅用于校验与展示，不能当存储键。 */
    private String username;
    /** 本次登录要写入的存储 id，可能与 {@link #username} 不同（同名异服时带后缀）。 */
    private volatile String storageId;
    private ExecutorService executor_service;
    private tools.StatusNotifier status_notifier;
    private boolean relogin_mode = false; // 是否为重新登录模式
    private String relogin_username = null; // 重新登录的用户名
    private int login_mode = MODE_QRCODE; // 当前登录方式

    /** 短信验证码登录：sendCaptcha 返回的 action_type，登录时必须原样回传（一次性） */
    private volatile String sms_action_type = null;
    /** action_type 对应的手机号：登录时手机号若已改动，视为需重新获取验证码 */
    private volatile String sms_action_phone = null;
    /** 本次短信登录提交的手机号与验证码（主线程写入，后台任务读取） */
    private volatile String sms_phone_input = null;
    private volatile String sms_code_input = null;

    /** 登录页选好、尚未落盘的头像；登录成功才写入该账号（失败不留孤儿文件）。 */
    private volatile Uri pendingAvatar;

    /** 相册选头像：Photo Picker 不需要存储权限，选完只做预览，落盘推迟到登录成功。 */
    private final ActivityResultLauncher<PickVisualMediaRequest> avatarPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) return;
                pendingAvatar = uri;
                if (pane != null) pane.setPendingAvatar(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用选定的主题（含换肤 overlay）
        SettingsFragment.applyAppTheme(this);
        pane = new com.muxiao.Venus.common.ui.compose.UserLoginScreenView(this);
        setContentView(pane);

        EdgeToEdge.enable(this);

        executor_service = Executors.newSingleThreadExecutor();
        user_manager = new UserManager(this);

        Intent intent = getIntent();
        relogin_mode = intent.getBooleanExtra(Constants.EXTRA_RELOGIN_MODE, false);
        relogin_username = intent.getStringExtra("USERNAME");

        // 状态栏内边距由 Compose 的 statusBarsPadding 处理
        pane.setOnBackClickListener(this::finish);

        // 如果是重新登录模式，用户名固定展示在按钮上并隐藏输入框
        // 用 displayName：存储 id 可能带内部后缀
        if (relogin_mode && relogin_username != null)
            pane.setRelogin(user_manager.displayName(relogin_username));

        // 登录方式切换：账号密码/短信/Cookie 均仅国服可用，国际服隐藏切换入口
        if (MiHoYoBBSConstants.is_oversea(this)) {
            pane.setOversea(true);
            applyLoginMode(MODE_QRCODE);
        } else {
            pane.setOnModeSelectListener(this::applyLoginMode);
            applyLoginMode(MODE_QRCODE);
        }

        status_notifier = new tools.StatusNotifier();
        // 日志追加与自动滚底都在 Compose 侧完成
        status_notifier.addListener(status -> runOnUiThread(() -> pane.appendLog(status)));
        pane.setOnLoginClickListener(() -> {
            pane.clearLog();
            handleLogin();
        });
        // 软键盘「完成 / 下一步」同样触发登录
        pane.setOnSubmitListener(() -> {
            pane.clearLog();
            handleLogin();
        });
        // 短信登录：获取验证码是独立于「登录」提交的第二个动作，由宿主单独派发
        pane.setOnSendCodeClickListener(this::handleSendCode);
        // 头像：先选后存（Compose 侧只做预览，落盘在 finish_login_success）
        pane.setOnPickAvatarClickListener(this::pickAvatar);
        pane.setOnClearAvatarClickListener(() -> pendingAvatar = null);
    }

    /** 打开系统相册选头像（Photo Picker，无需存储权限）。 */
    private void pickAvatar() {
        avatarPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    /**
     * 切换登录方式，联动输入区与二维码区的可见性
     */
    private void applyLoginMode(int mode) {
        login_mode = mode;
        // 输入区显隐、按钮文案（含重登录文案）、二维码收起都由 Compose 侧依 loginMode 派生
        pane.setLoginMode(mode);
    }

    /** 统一收起扫码卡片（含二维码图与提示），避免旧二维码残留；显示走 pane.setQrVisible(true)。 */
    private void setQrVisible() {
        pane.setQrVisible(false);
    }

    /**
    * 校验用户名/密码与重名冲突，重登录模式先清空旧凭证，再按登录方式提交 LoginTask。
    */
    private void handleLogin() {
        username = pane.getUsername().trim();
        boolean isOversea = MiHoYoBBSConstants.is_oversea(this);

        if (username.isEmpty()) {
            status_notifier.notifyListeners(getString(R.string.login_input_username));
            return;
        }
        if (login_mode == MODE_PASSWORD) {
            if (isOversea) {
                status_notifier.notifyListeners(getString(R.string.login_password_only_cn));
                return;
            }
            String account = pane.getAccount().trim();
            String password = pane.getPassword();
            if (account.isEmpty() || password.isEmpty()) {
                status_notifier.notifyListeners(getString(R.string.login_input_account));
                return;
            }
        }
        if (login_mode == MODE_COOKIE && pane.getCookie().trim().isEmpty()) {
            status_notifier.notifyListeners(getString(R.string.login_cookie_empty));
            return;
        }
        if (login_mode == MODE_SMS) {
            if (isOversea) {
                status_notifier.notifyListeners(getString(R.string.login_sms_only_cn));
                return;
            }
            String phone = pane.getPhone().trim();
            if (!isValidPhone(phone)) {
                status_notifier.notifyListeners(getString(R.string.login_sms_phone_invalid));
                return;
            }
            if (pane.getSmsCode().trim().isEmpty()) {
                status_notifier.notifyListeners(getString(R.string.login_sms_code_empty));
                return;
            }
            // action_type 与手机号绑定：发码后又改过手机号，旧的 action_type 已经对不上了
            if (sms_action_type == null || !phone.equals(sms_action_phone)) {
                status_notifier.notifyListeners(getString(R.string.login_sms_not_sent));
                return;
            }
        }
        if (!relogin_mode && user_manager.isUserExists(username)) {
            if (user_manager.isUserMatchingServerType(username, isOversea)) {
                // 同服同名：这就是已有账号本身。不能在这里重复登录——会覆盖它的凭证。
                // 需要刷新凭证请走用户管理页的「重新登录」入口（那边按 id 精确操作）
                status_notifier.notifyListeners(getString(R.string.snack_username_exists));
                return;
            }
            // 同名但属于另一个服务器：放行。storageId 会追加该服后缀，两个账号从此各用一套存储
        }

        // 重登录沿用原 id，避免再追加一层后缀
        storageId = (relogin_mode && relogin_username != null)
                ? relogin_username
                : user_manager.resolveStorageId(username, isOversea);

        // 如果是重新登录模式，先清除旧的token数据（经仓储清除缓存 + 旧 SP + DataStore 三处，
        // 只清旧 SP 时读取仍会命中缓存/DataStore，导致重新登录后继续使用旧凭证）
        if (relogin_mode && relogin_username != null) {
            new com.muxiao.Venus.common.data.UserRepository(this).clear(relogin_username);
        }

        change_component_status(false);
        // 明文账号密码 / Cookie / 短信验证码仅在本次任务的内存中传递，不写入任何持久化存储
        String account = login_mode == MODE_PASSWORD ? pane.getAccount().trim() : null;
        String password = login_mode == MODE_PASSWORD ? pane.getPassword() : null;
        String cookie = login_mode == MODE_COOKIE ? pane.getCookie().trim() : null;
        sms_phone_input = login_mode == MODE_SMS ? pane.getPhone().trim() : null;
        sms_code_input = login_mode == MODE_SMS ? pane.getSmsCode().trim() : null;
        LoginTask login_task = new LoginTask(this, login_mode, account, password, cookie);
        executor_service.execute(login_task);
    }

    /**
    * 「获取验证码」：校验手机号后单独派发短信下发任务；与登录共用单线程池，登录进行中点击只会排队而非并发打接口。
    */
    private void handleSendCode() {
        if (MiHoYoBBSConstants.is_oversea(this)) {
            status_notifier.notifyListeners(getString(R.string.login_sms_only_cn));
            return;
        }
        String phone = pane.getPhone().trim();
        if (!isValidPhone(phone)) {
            status_notifier.notifyListeners(getString(R.string.login_sms_phone_invalid));
            return;
        }
        pane.clearLog();
        change_component_status(false);
        executor_service.execute(new SendCodeTask(this, phone));
    }

    /** 国服短信登录只接受 11 位大陆手机号（1 开头，第二位 3-9）。 */
    private static boolean isValidPhone(String phone) {
        return phone != null && phone.matches("1[3-9]\\d{9}");
    }

    /**
     * 登录进行中禁用输入与按钮、完成后恢复，避免重复提交。
     */
    private void change_component_status(Boolean status) {
        // 按钮与三个输入框的可用态统一由宿主状态驱动
        pane.setInputsEnabled(status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor_service != null && !executor_service.isShutdown())
            executor_service.shutdown();
        if (status_notifier != null)
            status_notifier.removeAllListeners();
        // 清理二维码图片资源：先解绑再回收，避免 Compose 侧仍持有已回收的 Bitmap
        if (pane != null) {
            pane.setQrVisible(false);
            pane.setQrBitmap(null);
        }
    }


    /**
    * 极验统一入口：v4 走 WebView 加载极验官方 gt4.js，v3 走 sensebot（GT3），两者都返回可直接重提的 x-rpc-aigis 头。
    * 账密登录、短信发码、短信登录三个入口共用（都可能在响应头收到 aigis 任务）；须在后台线程调用（内部阻塞等前台结果）。
    */
    private String resolveAigisHeader(PasswordLogin.AigisInfo info) {
        String header;
        try {
            header = info.useV4 ? solveAigisV4(info) : solveAigis(info);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie.getMessage(), ie);
        }
        if (header == null)
            throw new RuntimeException(getString(info.useV4
                    ? R.string.login_aigis_v4_failed : R.string.login_aigis_failed));
        return header;
    }

    /**
    * 在前台用 WebView 拉起极验 v4 并阻塞后台线程等待结果。与 v3 差异（详见 {@link GeetestV4Dialog}）：无 challenge，
    * 参数为 captchaId + riskType + session_id，结果在 onSuccess 取；成功返回重提头，关闭/加载失败/超时返回 null。
    */
    private String solveAigisV4(PasswordLogin.AigisInfo info) throws InterruptedException {
        status_notifier.notifyListeners(getString(R.string.login_aigis_v4_start));
        final CountDownLatch latch = new CountDownLatch(1);
        // 极验 validate JSON，由 WebView 的 JS 桥回调写入（null = 未通过）
        final String[] validate = new String[1];
        final Dialog[] dialogHolder = new Dialog[1];

        runOnUiThread(() -> {
            Dialog dialog = GeetestV4Dialog.show(UserLoginActivity.this, info, json -> {
                validate[0] = json;
                latch.countDown();
            });
            dialogHolder[0] = dialog;
            // 宿主 Activity 不可用（已在退出）时直接结束等待，避免卡满 5 分钟
            if (dialog == null)
                latch.countDown();
        });

        // 与 GT3 链路一致：最多等待 5 分钟
        boolean completed = latch.await(5, TimeUnit.MINUTES);
        if (!completed) {
            // 超时后收起弹窗，让 WebView 随 Dialog 一起销毁
            runOnUiThread(() -> {
                Dialog dialog = dialogHolder[0];
                if (dialog != null && dialog.isShowing())
                    dialog.dismiss();
            });
            return null;
        }
        if (validate[0] == null || validate[0].isEmpty())
            return null;
        return PasswordLogin.buildAigisV4Header(info, validate[0]);
    }

    /**
    * 在前台拉起极验 GT3 自定义流程（无需按钮）并阻塞后台线程等待；成功返回 x-rpc-aigis 重提头，关闭/失败/超时返回 null。
    */
    private String solveAigis(PasswordLogin.AigisInfo info) throws InterruptedException {
        status_notifier.notifyListeners(getString(R.string.login_aigis_start));
        final CountDownLatch latch = new CountDownLatch(1);
        // 极验三参数：[0]=challenge [1]=validate [2]=seccode，由极验内部线程回调写入
        final String[] geetest = new String[3];
        final GT3GeetestUtils[] utilsHolder = new GT3GeetestUtils[1];

        runOnUiThread(() -> {
            GT3GeetestUtils utils = new GT3GeetestUtils(UserLoginActivity.this);
            utilsHolder[0] = utils;
            GT3ConfigBean bean = new GT3ConfigBean();
            bean.setPattern(1);
            bean.setLang("zh");
            bean.setCanceledOnTouchOutside(false);
            bean.setListener(new GT3Listener() {
                @Override
                public void onButtonClick() {
                    // gt/challenge 来自 aigis 响应头，直接配置后调起验证窗口
                    Map<String, Object> api1 = new HashMap<>();
                    api1.put("gt", info.gt);
                    api1.put("challenge", info.challenge);
                    api1.put("success", 1);
                    bean.setApi1Json(new JSONObject(api1));
                    utils.getGeetest();
                }

                @Override
                public void onDialogResult(String result) {
                    // 回调在极验内部线程，残缺结果时保持 null 走失败分支
                    try {
                        JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
                        geetest[0] = obj.get("geetest_challenge").getAsString();
                        geetest[1] = obj.get("geetest_validate").getAsString();
                        geetest[2] = obj.get("geetest_seccode").getAsString();
                    } catch (RuntimeException e) {
                        geetest[0] = null;
                    }
                    latch.countDown();
                }

                @Override
                public void onClosed(int i) {
                    latch.countDown();
                }

                @Override
                public void onFailed(GT3ErrorBean errorBean) {
                    latch.countDown();
                }

                @Override
                public void onReceiveCaptchaCode(int i) {
                }

                @Override
                public void onStatistics(String s) {
                }

                @Override
                public void onSuccess(String s) {
                }
            });
            utils.init(bean);
            // 自定义流程：内部触发 onButtonClick → getGeetest 弹窗
            utils.startCustomFlow();
        });

        // 阻塞后台线程，最多等待 5 分钟；超时后 geetest 仍为 null，返回 null
        // 返回值（是否在超时前唤醒）无需区分：超时与「用户取消」都走下面的空值分支
        //noinspection ResultOfMethodCallIgnored
        latch.await(5, TimeUnit.MINUTES);
        // 回到后台线程后在主线程释放 SDK 资源
        runOnUiThread(() -> {
            if (utilsHolder[0] != null) {
                utilsHolder[0].destory();
                utilsHolder[0] = null;
            }
        });

        if (geetest[0] == null || geetest[1] == null || geetest[2] == null)
            return null;
        return PasswordLogin.buildAigisHeader(info, geetest[0], geetest[1], geetest[2]);
    }

    /**
    * 下发短信验证码任务。与 {@link LoginTask} 分开：它是 UI 上的第二个提交入口（「获取验证码」），
    * 且返回的 action_type 要留给随后的登录任务使用。
    */
    public class SendCodeTask implements Runnable {
        private final Context context;
        private final HeaderManager header_manager;
        private final String phone;

        SendCodeTask(Context context, String phone) {
            this.context = context;
            this.header_manager = new HeaderManager(context);
            this.phone = phone;
        }

        @Override
        public void run() {
            try {
                // 与登录任务一致：先确保设备 ID 就绪（设备类请求头依赖它）
                new DeviceUtils(context).waitForDeviceId();
                status_notifier.notifyListeners("\n" + getString(R.string.login_sms_sending) + "\n");

                SmsLogin.CaptchaResult result;
                try {
                    result = SmsLogin.sendCaptcha(context, header_manager, phone, null);
                } catch (PasswordLogin.AigisRequiredException e) {
                    // 发码被要求极验：拉起 GT3 后带同一个手机号重提
                    result = SmsLogin.sendCaptcha(context, header_manager, phone,
                            resolveAigisHeader(e.getAigisInfo()));
                }

                // action_type 与手机号一起记下：登录时校验两者是否仍然匹配
                sms_action_type = result.actionType;
                sms_action_phone = phone;
                // 服务端给了倒计时就照用，没给按 60s 兜底；UI 据此禁用重发按钮
                int seconds = result.countdown > 0 ? result.countdown : 60;
                status_notifier.notifyListeners(getString(R.string.login_sms_sent, phone) + "\n");
                runOnUiThread(() -> {
                    pane.setCodeCountdown(seconds);
                    change_component_status(true);
                });
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                status_notifier.notifyListeners("\n" + getString(R.string.dialog_error) + ": " + error_message + "\n\n");
                runOnUiThread(() -> change_component_status(true));
            }
        }
    }

    public class LoginTask implements Runnable {
        private final Context context;
        private final HeaderManager header_manager;
        /** 旧版扫码接口（国际服）用的 app_id；国服已走 passport，不再使用该值 */
        private final String app_id = "2";
        private String device_id;
        private final boolean isOversea;
        private final int mode;
        private final String account;
        private final String password;
        /** Cookie 导入模式下的原始 Cookie 文本（仅内存持有，不落盘、不打印） */
        private final String cookie;

        public LoginTask(Context context, int mode, String account, String password, String cookie) {
            this.context = context;
            this.header_manager = new HeaderManager(context);
            this.isOversea = MiHoYoBBSConstants.is_oversea(context);
            this.mode = mode;
            this.account = account;
            this.password = password;
            this.cookie = cookie;
        }

        @Override
        // 后台线程入口：获取设备 ID 后按登录方式分流到扫码或密码登录
        public void run() {
            try {
                DeviceUtils device_utils = new DeviceUtils(context);
                this.device_id = device_utils.waitForDeviceId();

                if (mode == MODE_PASSWORD)
                    run_password_login();
                else if (mode == MODE_COOKIE)
                    run_cookie_login();
                else if (mode == MODE_SMS)
                    run_sms_login();
                else
                    run_qrcode_login();
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                status_notifier.notifyListeners("\n" + getString(R.string.dialog_error) + ": " + error_message + "\n\n" + getString(R.string.login_failed) + "\n");
                runOnUiThread(() -> {
                    change_component_status(true);
                    setQrVisible();
                });
            }
        }

        /**
        * 扫码登录：取码 → 渲染 → 轮询。国服走 passport 新版（createQRLogin / queryQRLoginStatus），
        * 二维码过期（-106）自动换码重来；国际服仍走 hk4e-sdk 的 combo/panda 旧接口（ticket 需从二维码 URL 截取）。
        */
        private void run_qrcode_login() throws Exception {
            status_notifier.notifyListeners("\n" + getString(R.string.login_start_task) + "\n");
            for (int round = 0; round <= MAX_QR_REFRESH_COUNT; round++) {
                QrSession session = isOversea ? fetch_qr_oversea() : fetch_qr_cn();
                show_qr_code(session.url);
                status_notifier.notifyListeners(getString(R.string.login_qr_generated));
                if (isOversea) {
                    // 旧接口没有「过期码」：返回即已确认（落库与收尾都在方法内完成）
                    poll_qr_oversea(session);
                    return;
                }
                // 返回 true 表示已确认；false = 二维码过期，换一张继续等
                if (poll_qr_cn(session))
                    return;
                status_notifier.notifyListeners(getString(R.string.login_qr_expired) + "\n");
            }
            throw new RuntimeException(getString(R.string.login_qr_timeout));
        }

        /**
         * 账号密码登录：loginByPassword 直接返回 stoken 与 mid，再换 ltoken
         */
        private void run_password_login() {
            status_notifier.notifyListeners("\n" + getString(R.string.login_password_start) + "\n");
            PasswordLogin.Result result;
            try {
                result = PasswordLogin.login(context, header_manager, account, password, null);
            } catch (PasswordLogin.AigisRequiredException e) {
                // 拉起极验完成人机验证，拿到重提头后重新提交同一登录请求
                result = PasswordLogin.login(context, header_manager, account, password,
                        resolveAigisHeader(e.getAigisInfo()));
            }
            status_notifier.notifyListeners(getString(R.string.login_password_token_ok));

            tools.write(context, storageId, "stoken", result.stoken);
            tools.write(context, storageId, "mid", result.mid);
            if (result.aid != null)
                tools.write(context, storageId, "stuid", result.aid);
            if (result.loginTicket != null && !result.loginTicket.isEmpty())
                tools.write(context, storageId, "login_ticket", result.loginTicket);
            if (result.realnameRequired || result.needRealperson)
                status_notifier.notifyListeners(getString(R.string.login_password_realname));

            get_ltoken_by_stoken();
            finish_login_success();
        }

        /**
        * 短信登录：用发码阶段拿到的 action_type + 用户输入的验证码换 stoken，之后与账密登录同一套收尾（写凭证 → 换 ltoken）。
        */
        private void run_sms_login() {
            status_notifier.notifyListeners("\n" + getString(R.string.login_sms_start) + "\n");
            SmsLogin.Result result;
            try {
                result = SmsLogin.login(context, header_manager, sms_phone_input,
                        sms_code_input, sms_action_type, null);
            } catch (PasswordLogin.AigisRequiredException e) {
                // 登录这一步同样可能被要求极验，重提时带上同一个 action_type 与验证码
                result = SmsLogin.login(context, header_manager, sms_phone_input,
                        sms_code_input, sms_action_type, resolveAigisHeader(e.getAigisInfo()));
            }
            status_notifier.notifyListeners(getString(R.string.login_sms_token_ok));

            tools.write(context, storageId, "stoken", result.stoken);
            tools.write(context, storageId, "mid", result.mid);
            if (result.aid != null)
                tools.write(context, storageId, "stuid", result.aid);
            if (result.loginTicket != null && !result.loginTicket.isEmpty())
                tools.write(context, storageId, "login_ticket", result.loginTicket);
            if (result.realnameRequired || result.needRealperson)
                status_notifier.notifyListeners(getString(R.string.login_password_realname));

            // 验证码与 action_type 都是一次性的，用掉即失效，避免回退后重复提交
            sms_action_type = null;
            sms_action_phone = null;
            sms_code_input = null;
            runOnUiThread(() -> pane.clearSmsCode());

            get_ltoken_by_stoken();
            finish_login_success();
        }

        /**
        * Cookie 导入登录：解析浏览器粘贴的米游社 Cookie，得到 stuid/mid/stoken/ltoken，与扫码、密码登录写入同一组凭证槽位。
        * 全程不经过极验（aigis），是密码登录被 -3102 风控拦下时的兜底入口。
        */
        private void run_cookie_login() {
            status_notifier.notifyListeners("\n" + getString(R.string.login_cookie_start) + "\n");
            CookieLogin.Result result = CookieLogin.importFromCookie(context, header_manager, cookie);

            // 只覆盖本次真实拿到的字段：空值不写，避免把已有可用凭证清空
            tools.write(context, storageId, "stuid", result.stuid);
            if (result.mid != null && !result.mid.isEmpty())
                tools.write(context, storageId, "mid", result.mid);
            if (result.stoken != null && !result.stoken.isEmpty())
                tools.write(context, storageId, "stoken", result.stoken);
            if (result.ltoken != null && !result.ltoken.isEmpty())
                tools.write(context, storageId, "ltoken", result.ltoken);
            if (result.cookieToken != null && !result.cookieToken.isEmpty())
                tools.write(context, storageId, "cookie_token", result.cookieToken);

            status_notifier.notifyListeners(getString(R.string.login_cookie_parsed));
            // 只有 ltoken 时社区签到可用；游戏签到与抽卡链接依赖 stoken，明确提示而不是假装成功
            if (result.onlyLtoken())
                status_notifier.notifyListeners(getString(R.string.login_cookie_no_stoken));
            if (result.verifyRetcode != 0)
                status_notifier.notifyListeners(
                        getString(R.string.login_cookie_verify_warn, result.verifyRetcode));
            status_notifier.notifyListeners(getString(R.string.login_cookie_token_ok));
            finish_login_success();
        }


        /** 各登录方式共用的收尾：写入服务器类型、登记用户、恢复界面。 */
        private void finish_login_success() {
            // 重登录沿用该账号自身的服务器类型
            boolean overseaToWrite = relogin_mode && relogin_username != null
                    ? user_manager.isOversea(relogin_username) : isOversea;
            tools.write(context, storageId, "server_type", overseaToWrite ? "1" : "0");
            // 头像按存储 id 落盘
            String avatarUser = relogin_mode && relogin_username != null ? relogin_username : storageId;
            Uri avatarToSave = pendingAvatar;
            if (avatarToSave != null) {
                AvatarStore.saveFromUri(getApplicationContext(), avatarUser, avatarToSave);
            }
            runOnUiThread(() -> {
                setQrVisible();
                // 清空密码输入，避免明文长期驻留界面
                pane.clearPassword();
                // 头像已落盘：清掉预览态，避免下次登录被重复应用
                pendingAvatar = null;
                pane.setPendingAvatar(null);
                if (relogin_mode && relogin_username != null) {
                    user_manager.setCurrentUser(relogin_username);
                } else {
                    // 登记账号与当前用户都用存储 id
                    user_manager.addUser(storageId);
                    user_manager.setCurrentUser(storageId);
                }
                change_component_status(true);
            });
            // 提示用展示名
            status_notifier.notifyListeners("\n" + getString(R.string.login_success,
                    user_manager.displayName(storageId)) + "\n");
        }

        /**
        * 一次扫码会话：二维码内容 + 轮询凭证。passport 接口的 ticket 由响应直接给出，无需从二维码 URL 截取。
        */
        private final class QrSession {
            final String url;
            final String ticket;

            QrSession(String url, String ticket) {
                this.url = url;
                this.ticket = ticket;
            }
        }

        private void show_qr_code(String url) {
            try {
                QRCodeWriter qrCodeWriter = new QRCodeWriter();
                Map<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                // 根据屏幕密度调整二维码大小
                int qrSize = (int) (getResources().getDisplayMetrics().density * 200);
                int size = Math.max(qrSize, 300);
                BitMatrix bitMatrix = qrCodeWriter.encode(url, BarcodeFormat.QR_CODE, size, size, hints);
                Bitmap bitmap = Bitmap.createBitmap(bitMatrix.getWidth(), bitMatrix.getHeight(), Bitmap.Config.RGB_565);
                for (int x = 0; x < bitMatrix.getWidth(); x++)
                    for (int y = 0; y < bitMatrix.getHeight(); y++)
                        bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                // 直接交给 Compose 侧的 state，避免额外的 PNG 压缩与 UI 线程二次解码开销。
                // 视图会持有它，所以这里不能 recycle；下一次换码或 onDestroy 时自然被替换/回收。
                runOnUiThread(() -> {
                    pane.setQrBitmap(bitmap);
                    pane.setQrVisible(true);
                });
            } catch (Exception e) {
                throw new RuntimeException(getString(R.string.login_stoken_create_failed,
                        -1, tools.describeError(e)), e);
            }
        }

        /**
        * 国服取码：passport {@code createQRLogin}，body 固定 {@code "{}"}，DS 用 X4 salt + 该 body
        * （见 HeaderManager#get_passport_qr_headers）。
        */
        private QrSession fetch_qr_cn() {
            String body = "{}";
            Map<String, String> headers = header_manager.get_passport_qr_headers(body);
            String response = tools.postJson(Constants.Urls.QR_LOGIN_CREATE_URL, headers, body).body;
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = JsonAccess.retcode(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, response));
            JsonObject data = JsonAccess.data(result);
            String url = JsonAccess.optString(data, "url", null);
            String ticket = JsonAccess.optString(data, "ticket", null);
            if (url == null || ticket == null)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, response));
            return new QrSession(url, ticket);
        }

        /** 国际服取码：hk4e-sdk 旧接口，ticket 需从二维码 URL 的 ticket= 参数里截取。 */
        private QrSession fetch_qr_oversea() {
            Map<String, Object> body = new HashMap<>();
            body.put("app_id", app_id);
            body.put("device", device_id);
            String response = tools.sendPostRequest(Constants.Urls.OS_LOGIN_QR_URL, new HashMap<>(), body);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = JsonAccess.retcode(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, response));
            JsonObject data = JsonAccess.data(result);
            String url = JsonAccess.optString(data, "url", null);
            if (url == null)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, response));
            String[] parts = url.split("ticket=");
            if (parts.length < 2)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed,
                        retcode, "URL does not contain ticket parameter"));
            // 旧接口的 URL 里 ticket 后面还跟着 & 参数，截到 & 为止
            return new QrSession(url, parts[1].split("&")[0]);
        }

        /**
        * 国服轮询：{@code queryQRLoginStatus}，body 为 {"ticket":"..."}（DS 必须与该 body 一致）。
        * 状态机 Init / Created / Scanned / Confirmed；Confirmed 时响应直接带 tokens[] 与 user_info，省掉二次换票。
        *
        * @return true = 已确认并完成落库与收尾；false = 二维码过期，需换码
        */
        private boolean poll_qr_cn(QrSession session) throws Exception {
            String last_status = "";
            for (int times = 1; times <= MAX_QR_POLL_COUNT; times++) {
                JsonObject body_json = new JsonObject();
                body_json.addProperty("ticket", session.ticket);
                String body = body_json.toString();
                Map<String, String> headers = header_manager.get_passport_qr_headers(body);
                String response = tools.postJson(Constants.Urls.QR_LOGIN_QUERY_URL, headers, body).body;
                JsonObject result = JsonParser.parseString(response).getAsJsonObject();
                int retcode = JsonAccess.retcode(result);
                // -106：二维码已过期（新版接口用它表达过期，旧接口没有这个码）
                if (retcode == RETCODE_QR_EXPIRED)
                    return false;
                if (retcode != 0)
                    throw new RuntimeException(getString(R.string.login_stoken_query_failed, retcode, response));
                JsonObject data = JsonAccess.data(result);
                String status = JsonAccess.optString(data, "status", null);
                if (status == null) status = "";
                if ("Init".equals(status) || "Created".equals(status) || "Scanned".equals(status)) {
                    // 状态不变时不重复刷日志
                    if (!status.equals(last_status)) {
                        status_notifier.notifyListeners(getString(
                                "Scanned".equals(status) ? R.string.login_waiting_confirm : R.string.login_waiting_scan) + times);
                        last_status = status;
                    }
                    TimeUnit.MILLISECONDS.sleep(QR_POLL_INTERVAL_MS);
                    continue;
                }
                if ("Confirmed".equals(status)) {
                    save_cn_qr_credentials(data);
                    return true;
                }
                status_notifier.notifyListeners(getString(R.string.login_unknown_status) + status + times);
                throw new RuntimeException(getString(R.string.login_unknown_status) + status + times);
            }
            throw new RuntimeException(getString(R.string.login_qr_timeout));
        }

        /**
        * 新版扫码 Confirmed 的凭证落库：{@code tokens[0].token} 即 stoken，{@code user_info} 里有 mid 与 aid（等价旧链路的 stuid）。
        */
        private void save_cn_qr_credentials(JsonObject data) {
            if (data == null)
                throw new RuntimeException(getString(R.string.login_payload_missing));
            String stoken = null;
            if (data.has("tokens") && data.get("tokens").isJsonArray()) {
                JsonArray tokens = data.getAsJsonArray("tokens");
                if (!tokens.isEmpty() && tokens.get(0).isJsonObject())
                    stoken = JsonAccess.optString(tokens.get(0).getAsJsonObject(), "token", null);
            }
            JsonObject user_info = data.has("user_info") && data.get("user_info").isJsonObject()
                    ? data.getAsJsonObject("user_info") : null;
            String mid = JsonAccess.optString(user_info, "mid", null);
            String aid = JsonAccess.optString(user_info, "aid", null);
            if (stoken == null || mid == null || aid == null)
                throw new RuntimeException(getString(R.string.login_payload_missing));
            tools.write(context, storageId, "stoken", stoken);
            tools.write(context, storageId, "mid", mid);
            tools.write(context, storageId, "stuid", aid);
            get_ltoken_by_stoken();
            finish_login_success();
        }

        /**
        * 国际服轮询：旧接口，状态字段是 {@code stat}，Confirmed 时从 {@code payload.raw} 取 game_token 与 uid 再去换 stoken。
        *
        * <p>旧接口没有「二维码过期」这一态，因此正常返回即代表已确认并完成落库与收尾，失败一律抛异常。
        */
        private void poll_qr_oversea(QrSession session) throws Exception {
            String last_status = "";
            for (int times = 1; times <= MAX_QR_POLL_COUNT; times++) {
                Map<String, Object> body = new HashMap<>();
                body.put("app_id", app_id);
                body.put("ticket", session.ticket);
                body.put("device", device_id);
                String response = tools.sendPostRequest(Constants.Urls.OS_LOGIN_CHECK_URL, null, body);
                JsonObject result = JsonParser.parseString(response).getAsJsonObject();
                int retcode = JsonAccess.retcode(result);
                if (retcode != 0)
                    throw new RuntimeException(getString(R.string.login_stoken_query_failed, retcode, response));
                JsonObject data = JsonAccess.data(result);
                if (data == null)
                    throw new RuntimeException(getString(R.string.login_stoken_query_failed, retcode, response));
                String stat = JsonAccess.optString(data, "stat", null);
                if (stat == null) stat = "";
                // Init（等待扫码）与 Scanned（等待确认）仅提示文案不同，合并处理
                if ("Init".equals(stat) || "Scanned".equals(stat)) {
                    if (!stat.equals(last_status)) {
                        status_notifier.notifyListeners(getString(
                                "Init".equals(stat) ? R.string.login_waiting_scan : R.string.login_waiting_confirm) + times);
                        last_status = stat;
                    }
                    TimeUnit.MILLISECONDS.sleep((int) (Math.random() * 500 + 1500));
                    continue;
                }
                if ("Confirmed".equals(stat)) {
                    if (!data.has("payload") || !data.get("payload").isJsonObject())
                        throw new RuntimeException(getString(R.string.login_payload_missing));
                    JsonObject payload = data.getAsJsonObject("payload");
                    String raw_text = JsonAccess.optString(payload, "raw", null);
                    if (raw_text == null)
                        throw new RuntimeException(getString(R.string.login_raw_missing));
                    JsonObject raw = JsonParser.parseString(raw_text).getAsJsonObject();
                    String uid = JsonAccess.optString(raw, "uid", null);
                    String game_token = JsonAccess.optString(raw, "token", null);
                    if (uid == null || game_token == null)
                        throw new RuntimeException(getString(R.string.login_raw_missing));
                    get_stoken_by_game_token(uid, game_token);
                    finish_login_success();
                    return;
                }
                status_notifier.notifyListeners(getString(R.string.login_unknown_status) + stat + times);
                throw new RuntimeException(getString(R.string.login_unknown_status) + stat + times);
            }
            throw new RuntimeException(getString(R.string.login_qr_timeout));
        }

        /**
        * 用 game_token 换 stoken。仅国际服链路使用（hk4e-sdk 旧扫码只下发 game_token）；国服新版扫码 Confirmed 直接给 stoken 与 mid。
        */
        private void get_stoken_by_game_token(String stuid, String game_token) {
            Map<String, Object> body = new HashMap<>();
            body.put("account_id", Long.parseLong(stuid));
            body.put("game_token", game_token);
            Map<String, String> game_token_headers = header_manager.get_game_token_headers();
            String stokenUrl = isOversea ? Constants.Urls.OS_STOKEN_URL : Constants.Urls.STOKEN_URL;
            String response = tools.sendPostRequest(stokenUrl, game_token_headers, body);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = JsonAccess.retcode(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_stoken_token_failed, retcode, response));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject().has("data")
                    && result.get("data").isJsonObject() ? result.getAsJsonObject("data") : null;
            JsonObject userInfo = data != null && data.has("user_info") && data.get("user_info").isJsonObject()
                    ? data.getAsJsonObject("user_info") : null;
            JsonObject tokenObj = data != null && data.has("token") && data.get("token").isJsonObject()
                    ? data.getAsJsonObject("token") : null;
            String mid = JsonAccess.optString(userInfo, "mid", null);
            String stoken = JsonAccess.optString(tokenObj, "token", null);
            if (mid == null || stoken == null)
                throw new RuntimeException(getString(R.string.login_stoken_token_failed, retcode, response));
            tools.write(context, storageId, "stoken", stoken);
            tools.write(context, storageId, "mid", mid);
            tools.write(context, storageId, "game_token", game_token);
            tools.write(context, storageId, "stuid", stuid);
            get_ltoken_by_stoken();
        }

        /**
         * 通过stoken获取ltoken，扫码登录与账号密码登录拿到 stoken 后共用
         */
        private void get_ltoken_by_stoken() {
            Map<String, String> bbs_headers = header_manager.get_bbs_headers();
            bbs_headers.put("Cookie", "stoken=" + tools.read(context, storageId, "stoken") + ";mid=" + tools.read(context, storageId, "mid"));
            String ltokenUrl = isOversea ? Constants.Urls.OS_LTOKEN_URL : Constants.Urls.LTOKEN_URL;
            String response = tools.sendGetRequest(ltokenUrl, bbs_headers, null);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = JsonAccess.retcode(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_ltoken_failed, retcode, response));
            JsonObject ltokenData = result.has("data") && result.get("data").isJsonObject()
                    ? result.getAsJsonObject("data") : null;
            if (ltokenData == null || !ltokenData.has("ltoken") || ltokenData.get("ltoken").isJsonNull())
                throw new RuntimeException(getString(R.string.login_ltoken_failed, retcode, response));
            String ltoken = ltokenData.get("ltoken").getAsString();
            tools.write(context, storageId, "ltoken", ltoken);
        }
    }


    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
