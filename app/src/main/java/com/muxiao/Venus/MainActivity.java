package com.muxiao.Venus;

import com.muxiao.Venus.common.BaseActivity;
import dagger.hilt.android.AndroidEntryPoint;

import static com.muxiao.Venus.common.Constants.Prefs.APP_INFO_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_IMAGE_URI;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.LAST_VERSION;
import static com.muxiao.Venus.common.Constants.Prefs.PREF_CAPTCHA_PENDING;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;

import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.LinkFragment;
import com.muxiao.Venus.User.UserManagementFragment;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.Setting.UpdateChecker;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.common.ui.compose.MainShellView;
import com.muxiao.Venus.common.data.ConfigRepository;

/**
 * 主 Activity：底部导航（主页/用户管理/抽卡链接/设置）、背景图加载、语言/主题切换、后台人机验证入口。
 * 界面外壳在 Compose（{@link MainShellView}：背景层 + HorizontalPager + 玻璃底栏，见 docs/module-ui.md），
 * 本类只做业务编排：四个页面控制器的创建与 show/hide、背景预合成管线、页面程序化跳转、人机验证触发链。
 * 控制器生命周期对齐 FragmentStateAdapter 的 maxLifecycle：仅当前选中页处于「已恢复」态，页面选中回调与
 * onResume/onPause 统一折算为 show()/hide()（见 {@link #setShownPage}）。
 */
@AndroidEntryPoint
public class MainActivity extends BaseActivity {

    /** Compose 外壳（背景 + Pager + 底栏）。 */
    private MainShellView shell;

    /** 四个页面控制器，顺序即页面下标：0 主页 / 1 用户管理 / 2 抽卡链接 / 3 设置。 */
    private final HomeFragment homeController;
    private final UserManagementFragment usersController;
    private final LinkFragment linkController;
    private final SettingsFragment settingsController;

    /** 当前处于「已恢复」态的页面下标（-1 = 尚无）。 */
    private int shownPage = -1;

    public MainActivity() {
        homeController = new HomeFragment(this);
        usersController = new UserManagementFragment(this);
        linkController = new LinkFragment(this);
        settingsController = new SettingsFragment(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        // 必须放在 installSplashScreen 之后：闪屏库内部 setTheme(postSplashScreenTheme) 会重置
        // 主题、冲掉此前 applyStyle 的换肤 overlay，导致窗口背景回落到系统动态色。
        SettingsFragment.applyAppTheme(this);

        super.onCreate(savedInstanceState);

        // 状态栏/导航栏全出血（inset 由外壳与各页在 Compose 侧自行消费）
        EdgeToEdge.enable(this);

        checkForUpdatesIfNeeded();
        checkAndUpdateConfig(this);

        // ActivityResult 启动器：必须在本方法内（STARTED 之前）注册
        registerLaunchers();

        // Compose 外壳：页面视图由各控制器惰性创建（外壳首次组合时逐页调用一次）
        shell = new MainShellView(this);
        shell.setPageFactory((index, context) -> {
            switch (index) {
                case 1: return usersController.createView(context);
                case 2: return linkController.createView(context);
                case 3: return settingsController.createView(context);
                case 0:
                default: return homeController.createView(context);
            }
        });
        // 页面选中 → 控制器 show/hide（对齐 maxLifecycle 语义）
        shell.setOnPageSelectedListener(this::setShownPage);

        // 背景图发布给外壳背景层；玻璃采样源为 Compose GraphicsLayer（见 MainShellView）
        setupBackground();
        // 监听背景 prefs 变化即时重合成：切页不触发 Activity 生命周期，只靠 onResume 会让
        // 设置页内切换淡化风格「无感」；300ms 防抖合并滑条连续写值。
        getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(backgroundPrefListener);

        setContentView(shell);

        homeController.setHomeReadyCallback(this::tryTriggerCaptchaIfReady);

        handleCaptchaIntent(getIntent());
    }

    /** 注册全部 ActivityResult 启动器并分发到对应控制器（控制器惰性创建视图，注册先于视图）。 */
    private void registerLaunchers() {
        // 抽卡链接页：VPN 抓包授权 + 电池优化豁免
        ActivityResultLauncher<Intent> linkVpnLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> linkController.onVpnPermissionResult(
                result.getResultCode() == RESULT_OK));
        ActivityResultLauncher<Intent> linkBatteryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> linkController.startCaptureAfterChecks());
        // 用户管理页：头像照片选择器
        ActivityResultLauncher<PickVisualMediaRequest> userAvatarPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            usersController::onAvatarPicked);
        // 设置页：背景图选择（Photo Picker / 老设备回退）+ CropActivity 裁剪 + 电池优化豁免
        ActivityResultLauncher<PickVisualMediaRequest> settingsPickMedia = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            settingsController::onBackgroundImagePicked);
        ActivityResultLauncher<Intent> settingsSelectImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.onLegacyImagePicked(
                result.getResultCode() == RESULT_OK
                    && result.getData() != null ? result.getData().getData() : null));
        ActivityResultLauncher<Intent> settingsCropLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.onCropResult(result.getResultCode(), result.getData()));
        ActivityResultLauncher<Intent> settingsBatteryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.updateBatteryOptValue());
        // 设置页：云游戏 Token 抓取页（返回后刷新「已配置」角标）
        ActivityResultLauncher<Intent> settingsCloudTokenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.onCloudTokenFetched());
        // 分发（控制器在视图创建前只持有引用，attach 后使用）
        linkController.setLaunchers(linkVpnLauncher, linkBatteryLauncher);
        usersController.setAvatarPicker(userAvatarPicker);
        settingsController.setLaunchers(settingsPickMedia, settingsSelectImageLauncher,
            settingsCropLauncher, settingsBatteryLauncher, settingsCloudTokenLauncher);
    }

    /**
     * 页面选中回调（含外壳首次组合的初始页）：上一个选中页 hide、当前页 show。
     * 已暂停状态下不会收到页面回调（Pager 不可交互），故无需额外的暂停分支。
     */
    private void setShownPage(int index) {
        if (shownPage == index) return;
        int previous = shownPage;
        shownPage = index;
        if (previous >= 0) controllerAt(previous).hide();
        controllerAt(index).show();
        // 页面已就绪再展开目标抽屉：抽屉宿主是页面首次组合时才装配的
        if (index == 3) consumePendingSettingsPane();
    }

    private PageController controllerAt(int index) {
        switch (index) {
            case 1: return usersController;
            case 2: return linkController;
            case 3: return settingsController;
            case 0:
            default: return homeController;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        triggerCaptchaIfPending();
        refreshBackgroundIfPrefsChanged();
        if (shownPage >= 0) controllerAt(shownPage).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shownPage >= 0) controllerAt(shownPage).hide();
    }

    @Override
    protected void onDestroy() {
        getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(backgroundPrefListener);
        backgroundReloadHandler.removeCallbacksAndMessages(null);
        // 控制器资源回收（广播接收器/极验按钮等；Activity 销毁即全量释放）
        homeController.release();
        usersController.release();
        linkController.release();
        settingsController.release();
        super.onDestroy();
    }

    /** 待触发的后台人机验证标记（避免轮询）。 */
    private boolean captchaPending = false;

    /** 检查是否有待处理的后台人机验证，有则触发（就绪回调见 {@link #tryTriggerCaptchaIfReady()}）。 */
    private void triggerCaptchaIfPending() {
        ConfigRepository configRepo = new ConfigRepository(this);
        boolean pending = configRepo.getBoolean(PREF_CAPTCHA_PENDING, false);
        Logger.debug("VenusCaptcha", "triggerCaptchaIfPending: pending=" + pending);
        if (!pending) return;

        // 清除标记，防止重复触发（仓储写入为 Write-Through：缓存即时生效，后续读取无问题）
        configRepo.putBoolean(PREF_CAPTCHA_PENDING, false);
        captchaPending = true;
        shell.setCurrentPage(0, false);

        // 主页控制器可能尚未就绪（视图未创建）：直接尝试一次；未就绪则等就绪回调。
        tryTriggerCaptchaIfReady();
    }

    /** 主页控制器就绪时尝试触发后台人机验证；未就绪则等就绪回调，避免轮询。 */
    private void tryTriggerCaptchaIfReady() {
        if (!captchaPending) return;
        if (homeController.isReady()) {
            captchaPending = false;
            homeController.performBackgroundCaptchaVerification();
        }
    }

    // 解析来自后台人机验证通知的 Intent：置位 captchaPending 并切到首页等待触发
    private void handleCaptchaIntent(Intent intent) {
        Logger.debug("VenusCaptcha", "handleCaptchaIntent called, action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && Constants.ACTION_HANDLE_CAPTCHA.equals(intent.getAction())) {
            intent.setAction(null);
            new ConfigRepository(this).putBoolean(PREF_CAPTCHA_PENDING, true);
            shell.setCurrentPage(0, false);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleCaptchaIntent(intent);
        triggerCaptchaIfPending();
    }

    /** 供控制器程序化跳转（账号行/运行签到跳用户管理页）；show/hide 由页面选中回调联动。 */
    public void navigateToPage(int position) {
        shell.setCurrentPage(position, true);
    }

    /** 待展开的设置抽屉（0 = 无），见 {@link #openSettingsPane(int)}。 */
    private int pendingSettingsPane = 0;
    /** 待下发给设置页的凭证配置目标账号（null = 跟随当前账号），与 {@link #pendingSettingsPane} 同寿命。 */
    private String pendingCredentialUser;

    /**
     * 当前账号被本页之外改动（用户管理页的凭证 chip 会先切账号、再跳设置页）。必须立刻通知首页：
     * 账号行文案由控制器直接写进 Compose 状态、不经过 ViewModel 的 UiState，只等首页下次 show()
     * 会在切页动画期间露出旧账号。
     */
    public void onCurrentUserChanged() {
        homeController.refreshUserContext();
    }

    /**
     * 当前账号的头像被更换或重置：首页需显式重载——头像文件按用户名定址、内容原地覆盖，用户名与路径都不变，
     * 不通知会一直显示旧图（Compose 不重组 + Glide 命中缓存）。
     */
    public void onAvatarChanged() {
        homeController.refreshAvatar();
        // 抽卡页也展示当前账号头像，同一份文件、同样原地覆盖
        linkController.refreshAvatar();
    }

    /**
     * 切到设置页并展开指定抽屉——「凭证快捷配置」的统一入口。调用方：用户管理页用户卡的云游戏/森空岛 chip、
     * 首页未配置的划线项。展开必须等页面 show() 之后执行，因为抽屉宿主是设置页首次组合时才装配的。
     */
    public void openSettingsPane(int pane) {
        openSettingsPane(pane, null);
    }

    /**
     * 切到设置页、展开指定抽屉，并为它指定「凭证配置目标账号」。
     * 凭证按账号存放，配置别人的凭证不该以切换全局当前账号为代价（会连带首页数据与任务上下文）。
     */
    public void openSettingsPane(int pane, String credentialUser) {
        pendingSettingsPane = pane;
        pendingCredentialUser = credentialUser;
        if (shownPage == 3) {
            // 已在设置页：setShownPage 不会被触发，直接消费
            consumePendingSettingsPane();
        } else {
            navigateToPage(3);
        }
    }

    private void consumePendingSettingsPane() {
        int pane = pendingSettingsPane;
        if (pane == 0) return;
        pendingSettingsPane = 0;
        String credentialUser = pendingCredentialUser;
        pendingCredentialUser = null;
        settingsController.expandPane(pane, credentialUser);
    }

    /**
     * 加载背景图：后台解码 → 预合成（按预设降饱和提亮/压暗，再按不透明度与主题底色掺合）→ 主线程发布给外壳背景层。
     * 淡化只做一次且全烘进位图——先预处理再叠 alpha 等于双重淡化，画面发蒙且预设档差距被压缩。
     * 同时在后台生成底栏毛玻璃用的模糊小样（连续降采样），主线程只做矩阵对齐；权限不足/解码失败回退纯色基底并提示。
     */
    private void setupBackground() {
        Uri backgroundImageUri = SettingsFragment.getBackgroundImageUri(this);
        float backgroundAlpha = SettingsFragment.getBackgroundAlpha(this);
        int backgroundPreset = SettingsFragment.getBackgroundPreset(this);
        appliedBackgroundPreset = backgroundPreset;
        appliedBackgroundAlpha = backgroundAlpha;

        // 无图：不发背景层——玻璃采样源子树自带主题底色基底（见 MainShellView.Content），
        // 底栏透镜继续磨砂折射滚到玻璃下的真实内容
        if (backgroundImageUri == null) {
            shell.publishBackground(null);
            return;
        }

        // 底色掺合色统一走 VenusColorSchemes（非 Composable 纯函数，主线程解析一次）
        boolean darkNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        final int surfaceColor = com.muxiao.Venus.common.ui.compose.VenusColorSchemes
                .surfaceArgb(this, SettingsFragment.getSelectedThemeIndex(this), darkNight);

        com.muxiao.Venus.common.AppExecutors.get().execute(() -> {
            String error = null;
            Bitmap composited = null;
            try {
                // 解码 + 淡化 + 掺合一次性在后台算好（管线已抽到 AppBackground，供其它页面复用）
                composited = com.muxiao.Venus.common.AppBackground.loadComposited(
                        MainActivity.this, backgroundImageUri, backgroundPreset, backgroundAlpha, surfaceColor);
            } catch (SecurityException e) {
                // 权限不足，清除背景设置
                getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(BACKGROUND_IMAGE_URI)
                        .apply();
                error = getString(R.string.err_no_background_permission,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            } catch (Exception e) {
                error = getString(R.string.err_background_setup_error,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            }
            final Bitmap result = composited;
            final String errorMsg = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (errorMsg != null) {
                    shell.publishBackground(null);
                    show_error_dialog(MainActivity.this, errorMsg);
                    return;
                }
                // 发布背景位图：玻璃采样源子树的背景 Image 同帧入画（GraphicsLayer 录制）
                // 淡化已全部烘进位图：背景层恒为全不透明，绝不再叠加 alpha
                shell.publishBackground(result);
            });
        });
    }

    /** 已应用到 UI 的背景预设档 / 不透明度，供 onResume 检测变化后重新预合成。 */
    private int appliedBackgroundPreset = -1;
    private float appliedBackgroundAlpha = -1f;

    /** 背景 prefs 变化 → 防抖后重新预合成（background_prefs 内全部键均为背景相关，无需逐键判断）。 */
    private final android.os.Handler backgroundReloadHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable backgroundReloadRunnable = this::setupBackground;
    private final SharedPreferences.OnSharedPreferenceChangeListener backgroundPrefListener =
            (prefs, key) -> {
                backgroundReloadHandler.removeCallbacks(backgroundReloadRunnable);
                backgroundReloadHandler.postDelayed(backgroundReloadRunnable, 300);
            };

    /**
     * 淡化风格/不透明度变化都烘在位图里，无需重启，走完整重合成（后台线程，一次性开销）；
     * 首次添加背景图（此前为纯色基底）也在这里兜底触发。
     */
    private void refreshBackgroundIfPrefsChanged() {
        int preset = SettingsFragment.getBackgroundPreset(this);
        float alpha = SettingsFragment.getBackgroundAlpha(this);
        // 背景图的增删改本身走 background_prefs 监听（防抖触发），这里只兜底
        // 淡化风格/不透明度变化（onResume 对账，覆盖监听未覆盖的时序）
        if (preset != appliedBackgroundPreset || alpha != appliedBackgroundAlpha) {
            setupBackground();
        }
    }


    /** 自动更新开关开启时启动 UpdateChecker 检查新版本。 */
    private void checkForUpdatesIfNeeded() {
        boolean autoUpdateEnabled = getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AUTO_UPDATE_ENABLED, true);

        if (autoUpdateEnabled) {
            UpdateChecker updateChecker = new UpdateChecker(this);
            updateChecker.checkForUpdatesIfNeeded();
        }
    }

    /** 版本号变化（覆盖安装升级）时清除旧配置并从云端拉取最新配置。 */
    public static void checkAndUpdateConfig(Context context) {
        SharedPreferences appPrefs = context.getSharedPreferences(APP_INFO_PREFS_NAME, Context.MODE_PRIVATE);
        int lastVersion = appPrefs.getInt(LAST_VERSION, 0);
        int currentVersion = BuildConfig.VERSION_CODE;
        if (currentVersion > lastVersion) {
            // 应用已更新，清除旧配置（回退到内置默认值）
            ConfigRepository configRepo = new ConfigRepository(context);
            boolean captchaPending = configRepo.getBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, false);
            configRepo.clear();
            if (captchaPending) configRepo.putBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, true);
            appPrefs.edit().putInt(LAST_VERSION, currentVersion).apply();
            new Thread(() -> MiHoYoBBSConstants.update_config_from_web(context)).start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
